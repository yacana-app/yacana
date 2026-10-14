// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui

import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.flats.FlatsAssistant
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.polar.PolarAlignment
import app.yacana.domain.sequence.BlockTemplates
import app.yacana.domain.sequence.SavedSequences
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.sequence.Targets
import app.yacana.domain.session.JournalLines
import app.yacana.domain.session.SessionGuard
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.SkyBrowsing
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.StarDatabases
import app.yacana.ui.resources.Res
import app.yacana.ui.settings.LanguageSettings
import app.yacana.ui.settings.SystemLanguage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * The permissions and settings the night needs, each explained in the first steps.
 * [KeepWhenUnused] stops Android from pausing the app between nights weeks apart, and
 * [Autostart] is Xiaomi's own switch, without which a session the system closes does not come
 * back (spike S2).
 */
enum class AppPermission { LocalNetwork, Location, Notifications, Battery, KeepWhenUnused, Autostart }

/**
 * Where a permission stands. [needed] is false where this Android version does not have it,
 * and [blocked] means the system no longer asks, so only its settings screen can grant it.
 */
data class PermissionStatus(val permission: AppPermission, val granted: Boolean, val needed: Boolean = true, val blocked: Boolean = false)

interface PermissionsController {
    val statuses: StateFlow<List<PermissionStatus>>

    /** Asks the system, or opens the settings screen where the permission is granted. */
    fun request(permission: AppPermission)
}

/** Saving and opening files with the system file picker, so that the user chooses where. */
interface FilePicker {
    fun save(suggestedName: String, text: String)

    fun open(onText: (String) -> Unit)

    /** Lets the user pick a file of any kind, such as a star database; [onPicked] gets where it is. */
    fun openFile(onPicked: (String) -> Unit)

    /** Lets the user pick a folder, such as a sky cache as NINA keeps it; [onPicked] gets where it is. */
    fun openFolder(onPicked: (String) -> Unit)

    /** Lets the user choose where a new ZIP goes; [onPicked] gets where, to write it. */
    fun newZip(suggestedName: String, onPicked: (String) -> Unit)
}

/** Whether the first steps (permissions, then the equipment) were done, so they show only once. */
interface FirstSteps {
    val done: StateFlow<Boolean>

    fun finish()
}

/** The folder frames are saved in, which the user changes with the system's folder picker. */
interface FrameFolder {
    val location: StateFlow<String>

    fun change()
}

/**
 * The beeps of the flats when the phone itself is their light, face down on the telescope where
 * its screen cannot be read: one as they start, and another as they end.
 */
fun interface Beeper {
    fun beep(end: Boolean)
}

/** Saves the session log as a ZIP where the user chooses. */
fun interface LogExport {
    fun export()
}

/** Everything the screens get from the platform and the domain. */
data class AppServices(
    val display: DisplaySettings,
    val permissions: PermissionsController,
    val files: FilePicker,
    val firstSteps: FirstSteps,
    val equipment: EquipmentController,
    val capture: CaptureController,
    val plateSolving: PlateSolving,
    val starDatabases: StarDatabases,
    val centring: Centring,
    val polarAlignment: PolarAlignment,
    val autofocus: Autofocus,
    /** Runs the sequence. */
    val sequence: SequenceRunner,
    /** Watches the session: reconnects, pauses safely and stops safely. */
    val guard: SessionGuard? = null,
    /** The flats assistant; none where flats are not taken, as in some screenshots. */
    val flats: FlatsAssistant? = null,
    /** The catalogue and the objects found on the internet. */
    val objects: ObjectSearch,
    /** The objects marked with the star of their page; only while the app is open by default. */
    val favourites: Favourites = Favourites(),
    /** Tonight's targets, as framed and added to the sequence; only while the app is open by default. */
    val targets: Targets = Targets(),
    /** "My sequences": the saved sequences; only while the app is open by default. */
    val savedSequences: SavedSequences = SavedSequences(),
    /** The advanced editor's templates; only while the app is open by default. */
    val blockTemplates: BlockTemplates = BlockTemplates(),
    /** NINA's sky caches on the phone; null where there is no storage for them. */
    val skyCaches: SkyCacheStore? = null,
    val frameFolder: FrameFolder,
    val logExport: LogExport,
    /** The session journal read back, for the night's diary and summary. */
    val journal: JournalLines = JournalLines { emptyList() },
    /** The time and zone screens show, such as the next file name; fixed in screenshot tests. */
    val clock: Clock = Clock.System,
    val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** The app's language: the system's, or one chosen; the system's alone where it cannot change. */
    val languages: LanguageSettings = SystemLanguage(),
    /** The flats' beeps when the phone is their light; silent where there is no sound, as in screenshots. */
    val beeper: Beeper = Beeper {},
    /** Where the map was left; only while the app is open by default. */
    val skyBrowsing: SkyBrowsing = SkyBrowsing(),
)

/** The app's catalogue of deep-sky objects, as `catalog/build.py` makes it, for [ObjectSearch]. */
fun catalogueText(): suspend () -> String = { Res.readBytes("files/catalogue.tsv").decodeToString() }
