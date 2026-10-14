// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.solver

import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One of ASTAP's star databases: the [file] it is published in, its size to download and
 * on the phone, and the field heights it suits, in degrees.
 */
data class StarDatabase(
    val name: String,
    val file: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val minFieldDeg: Double,
    val maxFieldDeg: Double,
)

/** ASTAP's star databases, from the densest for small fields to the one for the widest. */
val STAR_DATABASES =
    listOf(
        StarDatabase("D05", "d05_star_database.zip", 102_151_943, 108_830_225, 0.6, 6.0),
        StarDatabase("D20", "d20_star_database.zip", 399_561_156, 422_762_340, 0.3, 6.0),
        StarDatabase("D50", "d50_star_database.zip", 901_308_858, 951_326_950, 0.2, 6.0),
        StarDatabase("D80", "d80_star_database.deb", 1_213_392_756, 1_332_217_055, 0.15, 6.0),
        StarDatabase("G05", "g05_star_database.zip", 101_635_930, 105_423_065, 3.0, 20.0),
        StarDatabase("W08", "w08_star_database_mag08_astap.zip", 327_319, 495_184, 20.0, 80.0),
    )

/**
 * The database for a field [fieldHeightDeg] high: D50 up to 6°, as for the Seestar, D80
 * below D50's smallest field, and G05 and W08 for wider ones. Without a field, D50.
 */
fun recommendedDatabase(fieldHeightDeg: Double?): StarDatabase {
    val name =
        when {
            fieldHeightDeg == null -> "D50"
            fieldHeightDeg < D50_MIN_FIELD -> "D80"
            fieldHeightDeg <= D_MAX_FIELD -> "D50"
            fieldHeightDeg <= G05_MAX_FIELD -> "G05"
            else -> "W08"
        }
    return STAR_DATABASES.first { it.name == name }
}

/** The height of the connected camera's field in degrees, with the profile's optics; null without them. */
fun fieldHeightDeg(state: EquipmentState): Double? {
    val camera = ((state.connection as? Connection.Connected)?.equipment?.camera)?.info
    return camera?.let { state.active?.optics?.pixelScaleArcsec(it.pixelSizeY) }?.let { camera.height * it / ARCSEC_PER_DEGREE }
}

/**
 * The folder of star databases on the phone, and how more arrive there: downloaded from ASTAP's
 * site, on WiFi only and resuming where they stopped, or installed from a file. A
 * database is on the phone only once all its files are.
 */
interface StarDatabaseStore {
    val state: StateFlow<StarDatabaseFiles>

    /** Downloads [database] and installs it; [owner] is the profile that asked, given back in [StarDatabaseFiles.arriving]. */
    fun download(database: StarDatabase, owner: String)

    /** Stops a download and forgets what it had. */
    fun cancel(name: String)

    /**
     * Installs the database in the file at [location], a ZIP or a Debian package as ASTAP
     * publishes them, and returns its name.
     * @throws StarDatabaseException when the file holds no database or cannot be read.
     */
    suspend fun import(location: String): String

    /** Deletes [name] from the phone, to free its space. */
    fun delete(name: String)
}

/** The databases on the phone, those [arriving], the free space where they go, and the last download that [failed]. */
data class StarDatabaseFiles(
    val installed: Set<String> = emptySet(),
    val arriving: Map<String, Arrival> = emptyMap(),
    val freeBytes: Long = 0,
    val failed: String? = null,
)

/** A database on its way for profile [owner]: [doneBytes] of [totalBytes] of the [step]. */
data class Arrival(val owner: String, val step: ArrivalStep, val doneBytes: Long = 0, val totalBytes: Long = 0)

enum class ArrivalStep { WaitingForWifi, Downloading, Installing }

/** A file that could not be installed: it holds no star database, or [message] says why. */
class StarDatabaseException(val notADatabase: Boolean, message: String) : Exception(message)

/** Why a database did not start arriving, for the screen to say it. */
sealed interface DatabaseProblem {
    data class NoSpace(val name: String, val neededBytes: Long, val freeBytes: Long) : DatabaseProblem

    data object NotADatabase : DatabaseProblem

    data class ImportFailed(val message: String) : DatabaseProblem
}

/** What the screens show: the store's files and the last problem. */
data class StarDatabasesState(val files: StarDatabaseFiles = StarDatabaseFiles(), val problem: DatabaseProblem? = null)

/**
 * The star databases of the profiles. A database downloaded or imported for a
 * profile becomes its database once it is on the phone; until then the profile keeps the one it
 * had, so a database that is not on the phone is never chosen here.
 */
class StarDatabases(private val equipment: EquipmentController, private val store: StarDatabaseStore, private val scope: CoroutineScope) {
    private val problem = MutableStateFlow<DatabaseProblem?>(null)

    /** The profile each arriving database is for; seen here too, so that a quick download is not missed. */
    private val owners = store.state.value.arriving.mapValues { it.value.owner }.toMutableMap()

    val state: StateFlow<StarDatabasesState> =
        combine(store.state, problem, ::StarDatabasesState).stateIn(scope, SharingStarted.Eagerly, StarDatabasesState(store.state.value))

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { files ->
                files.arriving.forEach { (name, arrival) -> owners[name] = arrival.owner }
                owners.filterKeys { it in files.installed && it !in files.arriving }.forEach { (name, owner) ->
                    owners.remove(name)
                    use(name, owner)
                }
                owners.keys.retainAll(files.arriving.keys + files.installed)
            }
        }
    }

    /** Downloads [database] for the profile in use, if the phone has room for the download and its files. */
    fun download(database: StarDatabase) {
        val profile = equipment.state.value.active ?: return
        val free = store.state.value.freeBytes
        val needed = database.downloadBytes + database.installedBytes
        if (free < needed) {
            problem.value = DatabaseProblem.NoSpace(database.name, needed, free)
            return
        }
        problem.value = null
        owners[database.name] = profile.id
        store.download(database, profile.id)
    }

    fun cancel(name: String) {
        owners.remove(name)
        store.cancel(name)
    }

    /** Installs the database in the file at [location] and makes it the database of the profile in use. */
    fun import(location: String) {
        val profile = equipment.state.value.active ?: return
        problem.value = null
        scope.launch {
            try {
                use(store.import(location), profile.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StarDatabaseException) {
                problem.value = if (e.notADatabase) DatabaseProblem.NotADatabase else DatabaseProblem.ImportFailed(e.message.orEmpty())
            }
        }
    }

    /** Makes [name] the database of profile [owner], if it is on the phone. */
    fun use(name: String, owner: String = equipment.state.value.active?.id.orEmpty()) {
        if (name !in store.state.value.installed) return
        val profile = equipment.state.value.profiles.firstOrNull { it.id == owner } ?: return
        if (profile.options.starDatabase != name) equipment.save(profile.copy(options = profile.options.copy(starDatabase = name)))
    }

    /** Deletes [name] from the phone, unless it is the database of the profile in use. */
    fun delete(name: String) {
        if (equipment.state.value.active?.options?.starDatabase == name) return
        store.delete(name)
    }

    /** Forgets the last problem, once the user has seen it. */
    fun dismissProblem() {
        problem.value = null
    }
}

private const val ARCSEC_PER_DEGREE = 3600.0
private const val D50_MIN_FIELD = 0.2
private const val D_MAX_FIELD = 6.0
private const val G05_MAX_FIELD = 20.0
