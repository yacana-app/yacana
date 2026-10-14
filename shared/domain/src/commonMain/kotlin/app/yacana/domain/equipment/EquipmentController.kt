// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.AlpacaClient
import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.AlpacaServer
import app.yacana.alpaca.ConfiguredDevice
import app.yacana.alpaca.findServers
import app.yacana.alpaca.server
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileFile
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** What the platform gives the equipment: HTTP and discovery on the equipment's WiFi, and the phone's location. */
interface EquipmentPlatform {
    /** An HTTP client bound to the equipment's WiFi (ARCHITECTURE.md), or null while the phone has no WiFi. */
    fun httpClient(): HttpClient?

    val discovery: AlpacaDiscovery

    /** The handle of the equipment's network, for the engine's sockets (ARCHITECTURE.md), or null for the default network. */
    fun networkHandle(): Long?

    /** The phone's location, or null without the permission or without a fix. */
    suspend fun location(): Location?
}

/** The connection to the active profile's equipment. */
sealed interface Connection {
    data object Disconnected : Connection

    data object Connecting : Connection

    data class Connected(val equipment: ConnectedEquipment) : Connection

    /** Nothing could connect, and [reason] says why. */
    data class Failed(val reason: String) : Connection
}

data class EquipmentState(
    val profiles: List<Profile> = emptyList(),
    val active: Profile? = null,
    val connection: Connection = Connection.Disconnected,
)

/** The phone has no WiFi, so it cannot reach any Alpaca device. */
class NoWifiException : Exception("The phone is not on a WiFi network")

/**
 * The Equipment section's model: the profiles and the one in use, finding
 * equipment, and connecting it. The connection lives in [scope], so it outlives the screens.
 */
@Suppress("TooManyFunctions") // One function per thing the Equipment section can do.
class EquipmentController(
    private val store: ProfileStore,
    private val platform: EquipmentPlatform,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val journal: Journal = Journal.None,
) {
    private val mutableState = MutableStateFlow(EquipmentState())
    private var connecting: Job? = null

    val state: StateFlow<EquipmentState> = mutableState.asStateFlow()

    init {
        reload()
    }

    /** Uses profile [id] from now on. Disconnect first: a connected profile does not change. */
    fun activate(id: String) {
        store.activeId = id
        reload()
    }

    fun save(profile: Profile) {
        store.save(profile)
        reload()
    }

    fun delete(id: String) {
        store.delete(id)
        reload()
    }

    /** The Alpaca servers on the equipment's network, with their devices. */
    suspend fun discover(): List<AlpacaServer> = findServers(platform.discovery, http())

    /** The server at [address], such as `192.168.1.10:32323` or a full URL, with its devices. */
    suspend fun server(address: String): AlpacaServer = AlpacaClient(http(), normalize(address)).server()

    /** Makes a profile for [server], the Seestar's own if it is one, saves it and uses it. */
    fun use(server: AlpacaServer): Profile = keep(ProfilePresets.forServer(server))

    /** Makes the profile of the Seestar's wide-angle camera, named [name], saves it and uses it. */
    fun useWideAngle(server: AlpacaServer, name: String): Profile = keep(ProfilePresets.seestarS50ProWideAngle(server, name))

    private fun keep(profile: Profile): Profile {
        store.save(profile)
        store.activeId = profile.id
        reload()
        return profile
    }

    /**
     * Puts [device] of [server] in the active profile, in its role, replacing the device there
     * ("Equipment · add device"). Disconnect first: a connected profile does not change.
     */
    fun add(server: AlpacaServer, device: ConfiguredDevice) {
        val profile = state.value.active ?: return
        val role = DeviceRole.of(device.type) ?: return
        save(profile.copy(devices = profile.devices.with(role, DeviceRef(server.url, device.number, device.uniqueId, device.name))))
    }

    /** The active profile as a file to share. */
    fun exportActive(): String? = state.value.active?.let(ProfileFile::encode)

    /** Saves a profile shared from another phone, keeping its ID so that it replaces an older copy, and uses it. */
    fun import(text: String): Profile {
        val profile = ProfileFile.decode(text).profile
        store.save(profile)
        store.activeId = profile.id
        reload()
        return profile
    }

    /**
     * Connects the active profile. If the profile takes its site from the phone, the phone's
     * location is read first and saved in the profile, so the last one known is there next time.
     */
    fun connect() {
        val profile = state.value.active ?: return
        if (connecting?.isActive == true || state.value.connection is Connection.Connected) return
        setConnection(Connection.Connecting)
        journal.log("connecting", "profile" to profile.name)
        connecting =
            scope.launch {
                val result = connection(profile)
                when (result) {
                    is Connection.Connected -> journal.log(
                        "connected",
                        "profile" to result.equipment.profile.name,
                        "failures" to result.equipment.failures.mapKeys { it.key.name },
                        "clockOffsetMs" to result.equipment.timeAndSite?.mountClockOffset?.inWholeMilliseconds,
                        "siteErrors" to result.equipment.timeAndSite?.errors,
                    )

                    is Connection.Failed -> journal.log("connection_failed", "reason" to result.reason)

                    else -> Unit
                }
                setConnection(result)
            }
    }

    /**
     * A connection to [profile]'s equipment; [again] after a lost link, when the mount already has
     * the time and the site, which another app may be using it with meanwhile.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever goes wrong, the user sees why it did not connect.
    private suspend fun connection(profile: Profile, again: Boolean = false): Connection = try {
        val http = http()
        val located = located(profile, again)
        val offsets = { state.value.active?.options?.autofocus?.filterOffsets.orEmpty() }
        val equipment =
            EquipmentConnector(http, platform.discovery, clock).connect(
                located,
                scope,
                focusOffsets = offsets,
                sendTimeAndSite = located.options.sendTimeAndLocation && !again,
            )
        // The camera's sensor stays in the profile, for its field on the map without connecting.
        val sensor =
            equipment.camera?.info?.let { camera ->
                equipment.profile.optics.copy(
                    sensorWidthPx = camera.width,
                    sensorHeightPx = camera.height,
                    pixelSizeMicrons = camera.pixelSizeX,
                )
            }
        val withSensor = sensor?.let { equipment.profile.copy(optics = it) } ?: equipment.profile
        // And the wheel's filters, for planning the sequence without connecting.
        val kept = equipment.filterWheel?.names?.let { withSensor.copy(devices = withSensor.devices.copy(filterNames = it)) } ?: withSensor
        if (kept != located) save(kept)
        if (equipment.failures.isNotEmpty() && equipment.failures.size == equipment.profile.devices.byRole().size) {
            equipment.disconnect()
            Connection.Failed(equipment.failures.values.first())
        } else {
            Connection.Connected(equipment)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Connection.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    /**
     * Connects the active profile again after its link was lost: the connection before
     * stops reading its devices without disconnecting them, which would disconnect them for the new
     * one as well, and a new connection is made with the HTTP client of the WiFi as it is now.
     * Returns whether it connected.
     */
    suspend fun reconnect(): Boolean {
        val profile = state.value.active ?: return false
        connecting?.cancel()
        (state.value.connection as? Connection.Connected)?.equipment?.release()
        setConnection(Connection.Connecting)
        val result = connection(profile, again = true)
        setConnection(result)
        journal.log("reconnect", "connected" to (result is Connection.Connected), "reason" to (result as? Connection.Failed)?.reason)
        return result is Connection.Connected
    }

    fun disconnect() {
        journal.log("disconnect")
        connecting?.cancel()
        val connection = state.value.connection
        setConnection(Connection.Disconnected)
        if (connection is Connection.Connected) scope.launch { connection.equipment.disconnect() }
    }

    /**
     * Reads the phone's location now into the active profile, when it takes the phone's:
     * as the app starts and as the switch is turned on, not only when connecting. False without
     * the permission, with the phone's location off, or with no fix.
     */
    suspend fun refreshPhoneLocation(): Boolean {
        val location = state.value.active?.takeIf { it.options.locationFromPhone }?.let { platform.location() } ?: return false
        // The profile as it is once the fix has come, which may have changed meanwhile.
        state.value.active?.takeIf { it.options.locationFromPhone && it.location != location }?.let { save(it.copy(location = location)) }
        return true
    }

    /**
     * [profile] with the phone's location as it connects, when it takes the phone's. A
     * reconnection keeps the place it had: each fix lights the phone's location dot, green even in
     * red mode.
     */
    private suspend fun located(profile: Profile, again: Boolean): Profile {
        val location = if (profile.options.locationFromPhone && !again) platform.location() else null
        return location?.let { profile.copy(location = it) }?.also { if (it != profile) save(it) } ?: profile
    }

    private fun http(): HttpClient = platform.httpClient() ?: throw NoWifiException()

    private fun reload() {
        val profiles = store.list()
        val active = profiles.firstOrNull { it.id == store.activeId } ?: profiles.singleOrNull()
        mutableState.update { it.copy(profiles = profiles, active = active) }
    }

    private fun setConnection(connection: Connection) = mutableState.update { it.copy(connection = connection) }

    private companion object {
        fun normalize(address: String): String {
            val trimmed = address.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
