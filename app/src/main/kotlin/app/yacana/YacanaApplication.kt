// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.flats.FlatsAssistant
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.LiveStacking
import app.yacana.domain.polar.PolarAlignment
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.sequence.BlockTemplates
import app.yacana.domain.sequence.SavedSequences
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.sequence.Targets
import app.yacana.domain.session.SessionGuard
import app.yacana.domain.session.log
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.Sesame
import app.yacana.domain.sky.SkyBrowsing
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.StarDatabases
import app.yacana.engine.RustImageEngine
import app.yacana.equipment.AndroidEquipmentPlatform
import app.yacana.imaging.AndroidFrameStore
import app.yacana.session.AndroidPhoneBattery
import app.yacana.session.JournalFiles
import app.yacana.session.SessionNotifier
import app.yacana.session.SessionService
import app.yacana.sky.AndroidSkyCaches
import app.yacana.solver.AndroidStarDatabaseStore
import app.yacana.solver.AstapPlateSolver
import app.yacana.ui.FirstSteps
import app.yacana.ui.catalogueText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.File

/**
 * The objects that outlive the screens, wired by hand (ARCHITECTURE.md): the connected equipment keeps
 * going when the activity is recreated.
 */
class YacanaApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val display by lazy { AppDisplaySettings(this) }
    val firstSteps by lazy { AppFirstSteps(this) }
    private val platform by lazy { AndroidEquipmentPlatform(this) }
    val engine by lazy { RustImageEngine() }
    val frameStore by lazy { AndroidFrameStore(this) }
    val journal by lazy { JournalFiles(File(filesDir, "logs")) }
    val versionName: String by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
    val equipment by lazy { EquipmentController(ProfileStore(Path(filesDir.path, "profiles")), platform, scope, journal = journal) }

    /** The live stack, kept in the app's files to go on after a restart. */
    val stacking by lazy { LiveStacking(engine, frameStore, File(filesDir, "live-stack.bin").path, journal = journal) }
    val capture by lazy {
        CaptureController(
            equipment,
            engine,
            frameStore,
            platform,
            scope,
            software = "Yacana $versionName",
            journal = journal,
            stacking = stacking,
        )
    }
    val plateSolving by lazy { PlateSolving(equipment, capture, AstapPlateSolver(this), scope, journal) }
    val starDatabases by lazy { StarDatabases(equipment, AndroidStarDatabaseStore(this, scope), scope) }
    val centring by lazy { Centring(equipment, capture, plateSolving, scope, journal) }
    val polarAlignment by lazy { PolarAlignment(equipment, capture, plateSolving, scope, journal) }
    val autofocus by lazy { Autofocus(equipment, capture, scope, journal) }
    val flats by lazy { FlatsAssistant(equipment, capture, scope, journal) }
    val sequence by lazy {
        SequenceRunner(equipment, capture, centring, autofocus, scope, journal, progressFile = Path(filesDir.path, "sequence-run.json"))
    }
    val guard by lazy { SessionGuard(equipment, sequence, capture, AndroidPhoneBattery(this), scope, journal) }

    val skyCaches by lazy { AndroidSkyCaches(this, scope) }

    // SIMBAD goes over the internet: a client of its own, not bound to the equipment's WiFi, which may have none.
    val objects by lazy { ObjectSearch(catalogueText(), Path(filesDir.path, "found_objects.tsv"), Sesame(HttpClient(OkHttp)), scope) }
    val favourites by lazy { Favourites(Path(filesDir.path, "favourites.txt")) }
    val skyBrowsing by lazy { SkyBrowsing(Path(filesDir.path, "sky.json")) }
    val targets by lazy { Targets(Path(filesDir.path, "targets.json")) }
    val savedSequences by lazy { SavedSequences(Path(filesDir.path, "sequences")) }
    val blockTemplates by lazy { BlockTemplates(Path(filesDir.path, "templates.json")) }

    override fun onCreate() {
        super.onCreate()
        // The session's guard watches from the start, and the night's notifications with it.
        SessionNotifier(this, equipment, sequence, guard, autofocus).watch(scope)
        // While the equipment is connected, the session service keeps the phone awake.
        scope.launch {
            equipment.state.map { it.connection }.distinctUntilChanged().collect { connection ->
                if (connection is Connection.Connecting || connection is Connection.Connected) {
                    if (!SessionService.start(this@YacanaApplication)) journal.log("session_not_started", "reason" to "app in background")
                }
            }
        }
    }
}

/** The first steps show once, until the user finishes or skips them. */
class AppFirstSteps(context: Context) : FirstSteps {
    private val prefs = context.getSharedPreferences("first_steps", Context.MODE_PRIVATE)
    private val mutableDone = MutableStateFlow(prefs.getBoolean(KEY_DONE, false))

    override val done: StateFlow<Boolean> = mutableDone.asStateFlow()

    override fun finish() {
        mutableDone.value = true
        prefs.edit { putBoolean(KEY_DONE, true) }
    }

    private companion object {
        const val KEY_DONE = "done"
    }
}
