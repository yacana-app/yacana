// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.yacana.domain.profile.DeviceRole
import app.yacana.ports.SkyPosition
import app.yacana.ui.Section

/** A screen of the app: the key of an entry in a Navigation 3 back stack (ARCHITECTURE.md). */
sealed interface Screen {
    /** The first screen of a section. */
    data class Root(val section: Section) : Screen

    /** Settings, opened from the gear. */
    data object Settings : Screen

    data object Display : Screen

    /** The app's language, from Settings. */
    data object Language : Screen

    data object About : Screen

    /** Everything the app carries and the data it uses, with their licences. */
    data object Licences : Screen

    /** One of [Licences], by its id in `OPEN_SOURCE`. */
    data class Licence(val id: String) : Screen

    data object Permissions : Screen

    /** Where frames go, their format and their names. */
    data object ImageFiles : Screen

    /** ASTAP's options and star database. */
    data object PlateSolving : Screen

    /** The profile in use: site, options, horizon, sharing. */
    data object Profile : Screen

    data object AddEquipment : Screen

    /** A new profile, from equipment found on the WiFi. */
    data object NewProfile : Screen

    /** The controls of one connected device. */
    data class Device(val role: DeviceRole) : Screen

    /** The three-point polar alignment assistant, full screen. */
    data object PolarAlignment : Screen

    /** The autofocus assistant, full screen. */
    data object Autofocus : Screen

    /** The autofocus's options. */
    data object AutofocusSettings : Screen

    /** "Imaging · history": the session's frames and their quality. */
    data object History : Screen

    /** "Settings · sky map": NINA's caches and the map's layers. */
    data object SkyMapSettings : Screen

    /** "Sky · object page": an object of the catalogue, by its designation. */
    data class SkyObject(val designation: String) : Screen

    /**
     * "Sky · framing": the framing of an object of the catalogue, by its [designation], or of a spot
     * of the sky [at], from the map, which [designation] then names.
     */
    data class Framing(val designation: String, val at: SkyPosition? = null) : Screen

    /** "Sequence · simple editor": tonight's sequence, opened from "My sequences". */
    data object SequenceTonight : Screen

    /** "Settings · safety": the session's safe pause, safe stop and low battery. */
    data object SafetySettings : Screen

    /** "Night · diary": what happened tonight, opened from the night's summary. */
    data object NightDiary : Screen

    /** The flats assistant, full screen, opened from the template "Flats". */
    data object Flats : Screen

    /** "Sequence · advanced editor": tonight's tree at [path], opened from "Advanced" in tonight's editor. */
    data class Advanced(val path: List<Int> = emptyList()) : Screen

    /** "Sequence · add an instruction": the catalogue to add to the level at [path], called [holder], at [category]'s entries. */
    data class AdvancedAdd(val path: List<Int>, val holder: String, val category: String? = null) : Screen
}

/**
 * Where the user is: the selected section and one back stack per section (ARCHITECTURE.md).
 * The sections are a selected value, not part of a stack, and each keeps its own history.
 */
@Stable
class Navigator(initial: Section = Section.Night) {
    var section: Section by mutableStateOf(initial)
        private set

    private val stacks = Section.entries.associateWith { mutableStateListOf<Screen>(Screen.Root(it)) }

    /** The back stack of the selected section. Its first screen is always the section's root. */
    val backStack: SnapshotStateList<Screen> get() = stacks.getValue(section)

    /** Shows [section]. Selecting the section already shown goes back to its root. */
    fun select(section: Section) {
        if (section == this.section) {
            backStack.removeRange(1, backStack.size)
        } else {
            this.section = section
        }
    }

    /** Shows [section]'s root, from wherever the user is. */
    fun root(section: Section) {
        this.section = section
        backStack.removeRange(1, backStack.size)
    }

    fun open(screen: Screen) {
        if (backStack.last() != screen) backStack.add(screen)
    }

    /**
     * Shows the advanced editor at [path], its levels from the night down over what was under the
     * editor, so that back goes up one level at a time, as its breadcrumb.
     */
    fun openAdvanced(path: List<Int>) {
        while (backStack.size > 1 && (backStack.last() is Screen.Advanced || backStack.last() is Screen.AdvancedAdd)) {
            backStack.removeAt(backStack.lastIndex)
        }
        for (depth in 0..path.size) backStack.add(Screen.Advanced(path.take(depth)))
    }

    /** Goes back one screen. Returns false at a section's root, where the system handles back. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}
