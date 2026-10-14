// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.AlpacaClient
import app.yacana.alpaca.AlpacaDevice
import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.Camera
import app.yacana.alpaca.CoverCalibrator
import app.yacana.alpaca.FilterWheel
import app.yacana.alpaca.Focuser
import app.yacana.alpaca.SafetyMonitor
import app.yacana.alpaca.SwitchDevice
import app.yacana.alpaca.Telescope
import app.yacana.alpaca.findServers
import app.yacana.alpaca.optional
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileDevices
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Connects the devices of a profile.
 *
 * @param http the HTTP client for Alpaca; on Android it is bound to the equipment's network.
 * @param discovery finds a device again by its unique ID when its server's address changed.
 */
class EquipmentConnector(private val http: HttpClient, private val discovery: AlpacaDiscovery, private val clock: Clock = Clock.System) {
    /**
     * Connects every device of [profile] and starts reading their state in [scope].
     *
     * - A device that is not at its saved address is looked for by its unique ID, with discovery;
     *   [ConnectedEquipment.profile] then has the new address, for the caller to save.
     * - A device that fails to connect is left out and its error goes into [ConnectedEquipment.failures].
     * - If the profile allows it, the mount gets the UTC time and [site].
     * - A filter change moves the focuser by the filters' [focusOffsets], read on each change
     *   so that they can be edited while connected.
     */
    @Suppress("LongMethod") // One role after another, each read once as it connects.
    suspend fun connect(
        profile: Profile,
        scope: CoroutineScope,
        site: Location? = profile.location,
        focusOffsets: () -> Map<String, Int> = { profile.options.autofocus.filterOffsets },
        sendTimeAndSite: Boolean = profile.options.sendTimeAndLocation,
    ): ConnectedEquipment {
        val resolved = profile.copy(devices = locate(profile))
        val job = SupervisorJob(scope.coroutineContext[Job])
        val devicesScope = CoroutineScope(scope.coroutineContext + job)
        val clients = mutableMapOf<String, AlpacaClient>()
        fun client(ref: DeviceRef) = clients.getOrPut(ref.serverUrl) { AlpacaClient(http, ref.serverUrl) }

        val failures = mutableMapOf<DeviceRole, String>()
        val connected = mutableListOf<AlpacaDevice>()
        suspend fun <T> role(role: DeviceRole, create: suspend (DeviceRef) -> T): T? {
            val ref = resolved.devices.byRole()[role] ?: return null
            return orNull({ failures[role] = it }) { create(ref) }
        }

        val mount =
            role(DeviceRole.Mount) { ref ->
                val telescope = Telescope(client(ref), ref.number).also { it.connect() }.also(connected::add)
                val limit = HorizonLimit(resolved.options.minAltitudeDeg, resolved.options.horizon)
                MountMediator(telescope, telescope.capabilities(), limit, devicesScope, clock)
            }
        val camera =
            role(DeviceRole.Camera) { ref ->
                val camera = Camera(client(ref), ref.number).also { it.connect() }.also(connected::add)
                CameraMediator(camera, camera.info(), devicesScope, clock)
            }
        val focuser =
            role(DeviceRole.Focuser) { ref ->
                val focuser = Focuser(client(ref), ref.number).also { it.connect() }.also(connected::add)
                FocuserMediator(focuser, focuser.info(), devicesScope, clock)
            }
        val filterWheel =
            role(DeviceRole.FilterWheel) { ref ->
                val wheel = FilterWheel(client(ref), ref.number).also { it.connect() }.also(connected::add)
                FilterWheelMediator(wheel, wheel.names(), devicesScope, clock, focuser, focusOffsets)
            }
        val switches =
            role(DeviceRole.Switches) { ref ->
                val device = SwitchDevice(client(ref), ref.number).also { it.connect() }.also(connected::add)
                SwitchesMediator(device, (0 until device.count()).map { device.info(it) }, devicesScope, clock)
            }
        val flatPanel =
            role(DeviceRole.FlatPanel) { ref ->
                val panel = CoverCalibrator(client(ref), ref.number).also { it.connect() }.also(connected::add)
                FlatPanelMediator(panel, orNull { panel.maxBrightness() } ?: 0, devicesScope, clock)
            }
        val safetyMonitor =
            role(DeviceRole.SafetyMonitor) { ref ->
                val monitor = SafetyMonitor(client(ref), ref.number).also { it.connect() }.also(connected::add)
                SafetyMonitorMediator(monitor, devicesScope, clock)
            }
        val timeAndSite =
            mount?.takeIf { sendTimeAndSite }?.let { sendTimeAndSite(it.telescope, site) }

        return ConnectedEquipment(
            resolved,
            mount,
            camera,
            focuser,
            filterWheel,
            switches,
            flatPanel,
            safetyMonitor,
            failures,
            timeAndSite,
            onRelease = { job.cancel() },
        ) {
            job.cancel()
            connected.forEach { device -> orNull { device.disconnect() } }
        }
    }

    /**
     * Sends the phone's UTC time and the site to the mount, then reads them back (the same
     * time within 1 s, and the same site). Members the mount does not implement are skipped.
     */
    private suspend fun sendTimeAndSite(telescope: Telescope, site: Location?): TimeAndSite {
        val errors = mutableListOf<String>()
        suspend fun step(what: String, block: suspend () -> Unit) {
            orNull({ errors += "$what: $it" }) { optional { block() } }
        }
        step("time") { telescope.setUtcDate(clock.now()) }
        if (site != null) {
            step("latitude") { telescope.setSiteLatitude(site.latitude) }
            step("longitude") { telescope.setSiteLongitude(site.longitude) }
            step("elevation") { telescope.setSiteElevation(site.elevationM) }
        }
        return TimeAndSite(
            mountClockOffset = orNull({ errors += "reading the time: $it" }) { optional { telescope.utcDate() - clock.now() } },
            latitude = orNull({ errors += "reading the latitude: $it" }) { optional { telescope.siteLatitude() } },
            longitude = orNull({ errors += "reading the longitude: $it" }) { optional { telescope.siteLongitude() } },
            errors = errors,
        )
    }

    /** The profile's devices, each at the address where its unique ID answers now. */
    private suspend fun locate(profile: Profile): ProfileDevices {
        val refs = profile.devices.byRole().values
        val found = mutableMapOf<String, String>()
        for (url in refs.map { it.serverUrl }.distinct()) {
            val devices = withTimeoutOrNull(LOOKUP_TIMEOUT) { orNull { AlpacaClient(http, url).configuredDevices() } }
            devices?.forEach { found[it.uniqueId] = url }
        }
        if (refs.any { it.uniqueId.isNotEmpty() && it.uniqueId !in found }) {
            findServers(discovery, http).forEach { server -> server.devices.forEach { found.getOrPut(it.uniqueId) { server.url } } }
        }
        return profile.devices.map { ref -> found[ref.uniqueId]?.let { ref.copy(serverUrl = it) } ?: ref }
    }

    private companion object {
        val LOOKUP_TIMEOUT: Duration = 3.seconds
    }
}

/**
 * The devices of a profile while connected. A role is null if the profile has no device for it
 * or if it failed to connect; [failures] says why for the latter.
 */
class ConnectedEquipment internal constructor(
    val profile: Profile,
    val mount: MountMediator?,
    val camera: CameraMediator?,
    val focuser: FocuserMediator?,
    val filterWheel: FilterWheelMediator?,
    val switches: SwitchesMediator?,
    val flatPanel: FlatPanelMediator?,
    val safetyMonitor: SafetyMonitorMediator?,
    val failures: Map<DeviceRole, String>,
    /** What happened when sending the time and site to the mount, or null if it was not sent. */
    val timeAndSite: TimeAndSite?,
    private val onRelease: () -> Unit = {},
    private val onDisconnect: suspend () -> Unit,
) {
    /** Stops reading the devices and disconnects them. */
    suspend fun disconnect() = onDisconnect()

    /**
     * Stops reading the devices and leaves them connected, for a new connection to the same ones:
     * disconnecting them would disconnect them for the new one too, as their connected state is
     * the device's, not each client's.
     */
    fun release() = onRelease()
}

/** The mount's clock and site after sending them. Null values are members the mount does not have. */
data class TimeAndSite(
    /** The mount's clock minus the phone's: within ±1 s when the time was taken. */
    val mountClockOffset: Duration?,
    val latitude: Double?,
    val longitude: Double?,
    /** Steps the mount refused, such as "time: …". */
    val errors: List<String>,
)

/** Runs [block]; if it fails for any reason but cancellation, passes the reason to [onError] and returns null. */
@Suppress("TooGenericExceptionCaught") // One device's fault must not stop the others from connecting.
private suspend fun <T> orNull(onError: (String) -> Unit = {}, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError(e.message ?: e::class.simpleName.orEmpty())
    null
}
