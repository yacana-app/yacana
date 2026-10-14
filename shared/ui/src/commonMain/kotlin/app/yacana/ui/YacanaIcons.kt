// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons: Tabler Icons 3.48 (outline, MIT), the set the designs use, so each icon is the
 * one its design shows. Path data is copied from github.com/tabler/tabler-icons.
 */
@Suppress("LargeClass") // One icon per value, each the one its design shows.
object YacanaIcons {
    /** Tabler's "moon": the Night section, as in the designs' bottom bar. */
    val Night by lazy {
        tabler(
            "Night",
            "M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454l0 .008",
        )
    }

    /** Tabler's "telescope": the Equipment section, and the mount. */
    val Equipment by lazy {
        tabler(
            "Equipment",
            "M6 21l6 -5l6 5",
            "M12 13v8",
            "M3.294 13.678l.166 .281c.52 .88 1.624 1.265 2.605 .91l14.242 -5.165a1.023 1.023 0 0 0 .565 -1.456l-2" +
                ".62 -4.705a1.087 1.087 0 0 0 -1.447 -.42l-.056 .032l-12.694 7.618c-1.02 .613 -1.357 1.897 -.76 2.905" +
                "l-.001 0",
            "M14 5l3 5.5",
        )
    }

    /** Tabler's "stars": the Sky section. */
    val Sky by lazy {
        tabler(
            "Sky",
            "M17.8 19.817l-2.172 1.138a.392 .392 0 0 1 -.568 -.41l.415 -2.411l-1.757 -1.707a.389 .389 0 0 1 .217 " +
                "-.665l2.428 -.352l1.086 -2.193a.392 .392 0 0 1 .702 0l1.086 2.193l2.428 .352a.39 .39 0 0 1 .217 .665" +
                "l-1.757 1.707l.414 2.41a.39 .39 0 0 1 -.567 .411l-2.172 -1.138",
            "M6.2 19.817l-2.172 1.138a.392 .392 0 0 1 -.568 -.41l.415 -2.411l-1.757 -1.707a.389 .389 0 0 1 .217 -" +
                ".665l2.428 -.352l1.086 -2.193a.392 .392 0 0 1 .702 0l1.086 2.193l2.428 .352a.39 .39 0 0 1 .217 .665l" +
                "-1.757 1.707l.414 2.41a.39 .39 0 0 1 -.567 .411l-2.172 -1.138",
            "M12 9.817l-2.172 1.138a.392 .392 0 0 1 -.568 -.41l.415 -2.411l-1.757 -1.707a.389 .389 0 0 1 .217 -.6" +
                "65l2.428 -.352l1.086 -2.193a.392 .392 0 0 1 .702 0l1.086 2.193l2.428 .352a.39 .39 0 0 1 .217 .665l-1" +
                ".757 1.707l.414 2.41a.39 .39 0 0 1 -.567 .411l-2.172 -1.138",
        )
    }

    /** Tabler's "list-details": the Sequence section. */
    val Sequence by lazy {
        tabler(
            "Sequence",
            "M13 5h8",
            "M13 9h5",
            "M13 15h8",
            "M13 19h5",
            "M3 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4",
            "M3 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4",
        )
    }

    /** Tabler's "photo": the Imaging section, and images and files. */
    val Imaging by lazy {
        tabler(
            "Imaging",
            "M15 8h.01",
            "M3 6a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12",
            "M3 16l5 -5c.928 -.893 2.072 -.893 3 0l5 5",
            "M14 14l1 -1c.928 -.893 2.072 -.893 3 0l3 3",
        )
    }

    /** Tabler's "eye": red mode in the top bar, as the designs' eye. */
    val RedMode by lazy {
        tabler(
            "RedMode",
            "M10 12a2 2 0 1 0 4 0a2 2 0 0 0 -4 0",
            "M21 12c-2.4 4 -5.4 6 -9 6c-3.6 0 -6.6 -2 -9 -6c2.4 -4 5.4 -6 9 -6c3.6 0 6.6 2 9 6",
        )
    }

    /** Tabler's "chevron-left": back, at the start of the top bar. */
    val Back by lazy { tabler("Back", "M15 6l-6 6l6 6") }

    /** Tabler's "settings": settings, the gear in the top bar. */
    val Settings by lazy {
        tabler(
            "Settings",
            "M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.3" +
                "7 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.9" +
                "4 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 " +
                "1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 " +
                "-.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 " +
                "2.296 .07 2.572 -1.065",
            "M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
        )
    }

    /** Tabler's "chevron-right": a row that opens a screen. */
    val ChevronRight by lazy { tabler("ChevronRight", "M9 6l6 6l-6 6") }

    /** Tabler's "chevron-left": the month before, in the night's calendar. */
    val ChevronLeft by lazy { tabler("ChevronLeft", "M15 6l-6 6l6 6") }

    /** Tabler's "plus": add, such as a device in Equipment. */
    val Add by lazy {
        tabler(
            "Add",
            "M12 5l0 14",
            "M5 12l14 0",
        )
    }

    /** Tabler's "user": the profile in use, in Settings. */
    val Profile by lazy {
        tabler(
            "Profile",
            "M8 7a4 4 0 1 0 8 0a4 4 0 0 0 -8 0",
            "M6 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2",
        )
    }

    /** Tabler's "eye": display, in Settings. */
    val Display by lazy {
        tabler(
            "Display",
            "M10 12a2 2 0 1 0 4 0a2 2 0 0 0 -4 0",
            "M21 12c-2.4 4 -5.4 6 -9 6c-3.6 0 -6.6 -2 -9 -6c2.4 -4 5.4 -6 9 -6c3.6 0 6.6 2 9 6",
        )
    }

    /** Tabler's "file-export": the log and diagnostics, in Settings. */
    val Diagnostics by lazy {
        tabler(
            "Diagnostics",
            "M14 3v4a1 1 0 0 0 1 1h4",
            "M11.5 21h-4.5a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v5m-5 6h7m-3 -3l3 3l-3 3",
        )
    }

    /** Tabler's "info-circle": about, and other information. */
    val Info by lazy {
        tabler(
            "Info",
            "M3 12a9 9 0 1 0 18 0a9 9 0 0 0 -18 0",
            "M12 9h.01",
            "M11 12h1v4h1",
        )
    }

    /** Tabler's "license": the open-source licences, in "Settings · about". */
    val License by lazy {
        tabler(
            "License",
            "M15 21h-9a3 3 0 0 1 -3 -3v-1h10v2a2 2 0 0 0 4 0v-14a2 2 0 1 1 2 2h-2m2 -4h-11a3 3 0 0 0 -3 3v11",
            "M9 7l4 0",
            "M9 11l4 0",
        )
    }

    /** Tabler's "backspace": delete on the app's keypad. */
    val Backspace by lazy {
        tabler(
            "Backspace",
            "M20 6a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-11l-5 -5a1.5 1.5 0 0 1 0 -2l5 -5l11 0",
            "M12 10l4 4m0 -4l-4 4",
        )
    }

    /** Tabler's "plug-connected": a connection. */
    val PlugConnected by lazy {
        tabler(
            "PlugConnected",
            "M7 12l5 5l-1.5 1.5a3.536 3.536 0 1 1 -5 -5l1.5 -1.5",
            "M17 12l-5 -5l1.5 -1.5a3.536 3.536 0 1 1 5 5l-1.5 1.5",
            "M3 21l2.5 -2.5",
            "M18.5 5.5l2.5 -2.5",
            "M10 11l-2 2",
            "M13 14l-2 2",
        )
    }

    /** Tabler's "chevron-down": a choice that opens. */
    val ChevronDown by lazy { tabler("ChevronDown", "M6 9l6 6l6 -6") }

    /** Tabler's "camera": the camera. */
    val Camera by lazy {
        tabler(
            "Camera",
            "M5 7h1a2 2 0 0 0 2 -2a1 1 0 0 1 1 -1h6a1 1 0 0 1 1 1a2 2 0 0 0 2 2h1a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h" +
                "-14a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2",
            "M9 13a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
        )
    }

    /** Tabler's "focus-2": the focuser. */
    val Focuser by lazy {
        tabler(
            "Focuser",
            "M11.5 12a.5 .5 0 1 0 1 0a.5 .5 0 1 0 -1 0",
            "M5 12a7 7 0 1 0 14 0a7 7 0 1 0 -14 0",
            "M12 3l0 2",
            "M3 12l2 0",
            "M12 19l0 2",
            "M19 12l2 0",
        )
    }

    /** Tabler's "filter": the filter wheel, and the history's filter. */
    val Filter by lazy {
        tabler(
            "Filter",
            "M4 4h16v2.172a2 2 0 0 1 -.586 1.414l-4.414 4.414v7l-6 2v-8.5l-4.48 -4.928a2 2 0 0 1 -.52 -1.345v-2.2" +
                "27",
        )
    }

    /** Tabler's "toggle-right": a switch that is on, and tracking. */
    val ToggleOn by lazy {
        tabler(
            "ToggleOn",
            "M14 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M2 12a6 6 0 0 1 6 -6h8a6 6 0 0 1 6 6a6 6 0 0 1 -6 6h-8a6 6 0 0 1 -6 -6",
        )
    }

    /** Tabler's "toggle-left": a switch that is off. */
    val ToggleOff by lazy {
        tabler(
            "ToggleOff",
            "M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M2 12a6 6 0 0 1 6 -6h8a6 6 0 0 1 6 6a6 6 0 0 1 -6 6h-8a6 6 0 0 1 -6 -6",
        )
    }

    /** Tabler's "arrow-up": north on the mount's pad. */
    val ArrowUp by lazy {
        tabler(
            "ArrowUp",
            "M12 5l0 14",
            "M18 11l-6 -6",
            "M6 11l6 -6",
        )
    }

    /** Tabler's "arrow-down": south on the mount's pad. */
    val ArrowDown by lazy {
        tabler(
            "ArrowDown",
            "M12 5l0 14",
            "M18 13l-6 6",
            "M6 13l6 6",
        )
    }

    /** Tabler's "arrow-left": east on the mount's pad. */
    val ArrowLeft by lazy {
        tabler(
            "ArrowLeft",
            "M5 12l14 0",
            "M5 12l6 6",
            "M5 12l6 -6",
        )
    }

    /** Tabler's "arrow-right": west on the mount's pad. */
    val ArrowRight by lazy {
        tabler(
            "ArrowRight",
            "M5 12l14 0",
            "M13 18l6 -6",
            "M13 6l6 6",
        )
    }

    /** Tabler's "hand-stop": stop. */
    val Stop by lazy {
        tabler(
            "Stop",
            "M8 13v-7.5a1.5 1.5 0 0 1 3 0v6.5",
            "M11 5.5v-2a1.5 1.5 0 1 1 3 0v8.5",
            "M14 5.5a1.5 1.5 0 0 1 3 0v6.5",
            "M17 7.5a1.5 1.5 0 0 1 3 0v8.5a6 6 0 0 1 -6 6h-2h.208a6 6 0 0 1 -5.012 -2.7a69.74 69.74 0 0 1 -.196 -" +
                ".3c-.312 -.479 -1.407 -2.388 -3.286 -5.728a1.5 1.5 0 0 1 .536 -2.022a1.867 1.867 0 0 1 2.28 .28l1.47" +
                " 1.47",
        )
    }

    /** Tabler's "target": go to. */
    val Target by lazy {
        tabler(
            "Target",
            "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M7 12a5 5 0 1 0 10 0a5 5 0 1 0 -10 0",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
        )
    }

    /** Tabler's "crosshair": sync. */
    val Crosshair by lazy {
        tabler(
            "Crosshair",
            "M4 8v-2a2 2 0 0 1 2 -2h2",
            "M4 16v2a2 2 0 0 0 2 2h2",
            "M16 4h2a2 2 0 0 1 2 2v2",
            "M16 20h2a2 2 0 0 0 2 -2v-2",
            "M9 12l6 0",
            "M12 9l0 6",
        )
    }

    /** Tabler's "parking": park. */
    val Park by lazy {
        tabler(
            "Park",
            "M3 5a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14",
            "M10 16v-8h2.667c.736 0 1.333 .895 1.333 2s-.597 2 -1.333 2h-2.667",
        )
    }

    /** Tabler's "parking-off": unpark. */
    val Unpark by lazy {
        tabler(
            "Unpark",
            "M8 4h10a2 2 0 0 1 2 2v10m-.582 3.41c-.362 .365 -.864 .59 -1.418 .59h-12a2 2 0 0 1 -2 -2v-12c0 -.554 " +
                ".225 -1.056 .59 -1.418",
            "M9 16v-7m3 -1h1a2 2 0 0 1 1.817 2.836m-2.817 1.164h-3",
            "M3 3l18 18",
        )
    }

    /** Tabler's "home": home. */
    val Home by lazy {
        tabler(
            "Home",
            "M5 12l-2 0l9 -9l9 9l-2 0",
            "M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2 -2v-7",
            "M9 21v-6a2 2 0 0 1 2 -2h2a2 2 0 0 1 2 2v6",
        )
    }

    /** Tabler's "flame": a heater. */
    val Flame by lazy {
        tabler(
            "Flame",
            "M12 10.941c2.333 -3.308 .167 -7.823 -1 -8.941c0 3.395 -2.235 5.299 -3.667 6.706c-1.43 1.408 -2.333 3" +
                ".294 -2.333 5.588c0 3.704 3.134 6.706 7 6.706c3.866 0 7 -3.002 7 -6.706c0 -1.712 -1.232 -4.403 -2.33" +
                "3 -5.588c-2.084 3.353 -3.257 3.353 -4.667 2.235",
        )
    }

    /** Tabler's "bolt": a reading, such as a current. */
    val Bolt by lazy { tabler("Bolt", "M13 3l0 7l6 0l-8 11l0 -7l-6 0l8 -11") }

    /** Tabler's "check": what is selected or done. */
    val Check by lazy { tabler("Check", "M5 12l5 5l10 -10") }

    /** Tabler's "minus": less. */
    val Minus by lazy { tabler("Minus", "M5 12l14 0") }

    /** Tabler's "wifi": the local network permission. */
    val Wifi by lazy {
        tabler(
            "Wifi",
            "M12 18l.01 0",
            "M9.172 15.172a4 4 0 0 1 5.656 0",
            "M6.343 12.343a8 8 0 0 1 11.314 0",
            "M3.515 9.515c4.686 -4.687 12.284 -4.687 17 0",
        )
    }

    /** Tabler's "map-pin": the location permission. */
    val MapPin by lazy {
        tabler(
            "MapPin",
            "M9 11a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
            "M17.657 16.657l-4.243 4.243a2 2 0 0 1 -2.827 0l-4.244 -4.243a8 8 0 1 1 11.314 0",
        )
    }

    /** Tabler's "bell": notifications. */
    val Bell by lazy {
        tabler(
            "Bell",
            "M10 5a2 2 0 1 1 4 0a7 7 0 0 1 4 6v3a4 4 0 0 0 2 3h-16a4 4 0 0 0 2 -3v-3a7 7 0 0 1 4 -6",
            "M9 17v1a3 3 0 0 0 6 0v-1",
        )
    }

    /** Tabler's "battery-3": the battery setting. */
    val Battery by lazy {
        tabler(
            "Battery",
            "M6 7h11a2 2 0 0 1 2 2v.5a.5 .5 0 0 0 .5 .5a.5 .5 0 0 1 .5 .5v3a.5 .5 0 0 1 -.5 .5a.5 .5 0 0 0 -.5 .5" +
                "v.5a2 2 0 0 1 -2 2h-11a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2",
            "M7 10l0 4",
            "M10 10l0 4",
            "M13 10l0 4",
        )
    }

    /** Tabler's "clock-pause": keeping the app when unused. */
    val ClockPause by lazy {
        tabler(
            "ClockPause",
            "M20.942 13.018a9 9 0 1 0 -7.909 7.922",
            "M12 7v5l2 2",
            "M17 17v5",
            "M21 17v5",
        )
    }

    /** Tabler's "refresh": Xiaomi's Autostart. */
    val Refresh by lazy {
        tabler(
            "Refresh",
            "M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4",
            "M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4",
        )
    }

    /** Tabler's "lock": permissions, in Settings. */
    val Lock by lazy {
        tabler(
            "Lock",
            "M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6",
            "M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0",
            "M8 11v-4a4 4 0 1 1 8 0v4",
        )
    }

    /** Tabler's "search": searching. */
    val Search by lazy {
        tabler(
            "Search",
            "M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0",
            "M21 21l-6 -6",
        )
    }

    /** Tabler's "loader-2": something under way. */
    val Loader by lazy { tabler("Loader", "M12 3a9 9 0 1 0 9 9") }

    /** Tabler's "router": an Alpaca server that is not a Seestar. */
    val Router by lazy {
        tabler(
            "Router",
            "M3 15a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2l0 -4",
            "M17 17l0 .01",
            "M13 17l0 .01",
            "M15 13l0 -2",
            "M11.75 8.75a4 4 0 0 1 6.5 0",
            "M8.5 6.5a8 8 0 0 1 13 0",
        )
    }

    /** Tabler's "pencil": something to edit. */
    val Pencil by lazy {
        tabler(
            "Pencil",
            "M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4",
            "M13.5 6.5l4 4",
        )
    }

    /** Tabler's "clock": the time. */
    val Clock by lazy {
        tabler(
            "Clock",
            "M3 12a9 9 0 1 0 18 0a9 9 0 0 0 -18 0",
            "M12 7v5l3 3",
        )
    }

    /** Tabler's "file-import": import a file. */
    val FileImport by lazy {
        tabler(
            "FileImport",
            "M14 3v4a1 1 0 0 0 1 1h4",
            "M5 13v-8a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2h-5.5m-9.5 -2h7m-3 -3l3 3l-3 3",
        )
    }

    /** Tabler's "trash": delete. */
    val Trash by lazy {
        tabler(
            "Trash",
            "M4 7l16 0",
            "M10 11l0 6",
            "M14 11l0 6",
            "M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12",
            "M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3",
        )
    }

    /** Tabler's "folder": a folder. */
    val Folder by lazy {
        tabler(
            "Folder",
            "M5 4h4l3 3h7a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2",
        )
    }

    /** Tabler's "arrow-back-up": back to the default. */
    val Undo by lazy {
        tabler(
            "Undo",
            "M9 14l-4 -4l4 -4",
            "M5 10h11a4 4 0 1 1 0 8h-1",
        )
    }

    /** Tabler's "language": the app's language in Settings. */
    val Language by lazy {
        tabler(
            "Language",
            "M4 5h7",
            "M9 3v2c0 4.418 -2.239 8 -5 8",
            "M5 9c0 2.144 2.952 3.908 6.7 4",
            "M12 20l4 -9l4 9",
            "M19.1 18h-6.2",
        )
    }

    /** Tabler's "device-mobile": the phone's own language. */
    val DeviceMobile by lazy {
        tabler(
            "DeviceMobile",
            "M6 5a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-14z",
            "M11 4h2",
            "M12 17v.01",
        )
    }

    /** Tabler's "database": a star database. */
    val Database by lazy {
        tabler(
            "Database",
            "M4 6a8 3 0 1 0 16 0a8 3 0 1 0 -16 0",
            "M4 6v6a8 3 0 0 0 16 0v-6",
            "M4 12v6a8 3 0 0 0 16 0v-6",
        )
    }

    /** Tabler's "x": closes a full-screen assistant. */
    val Close by lazy { tabler("Close", "M18 6l-12 12", "M6 6l12 12") }

    /** Tabler's "compass": the polar alignment. */
    val Compass by lazy {
        tabler(
            "Compass",
            "M8 16l2 -6l6 -2l-2 6l-6 2",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
            "M12 3l0 2",
            "M12 19l0 2",
            "M3 12l2 0",
            "M19 12l2 0",
        )
    }

    /** Tabler's "arrow-big-up" and its turns: which way to turn a screw of the wedge. */
    val ArrowBigUp by lazy {
        tabler(
            "ArrowBigUp",
            "M9 20v-8h-3.586a1 1 0 0 1 -.707 -1.707l6.586 -6.586" +
                "a1 1 0 0 1 1.414 0l6.586 6.586a1 1 0 0 1 -.707 1.707h-3.586v8a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z",
        )
    }

    val ArrowBigDown by lazy {
        tabler(
            "ArrowBigDown",
            "M15 4v8h3.586a1 1 0 0 1 .707 1.707l-6.586 6.586a1 1 0 0 1 -1.414 0l-6.586 -6.586" +
                "a1 1 0 0 1 .707 -1.707h3.586v-8a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1z",
        )
    }

    val ArrowBigLeft by lazy {
        tabler(
            "ArrowBigLeft",
            "M20 15h-8v3.586a1 1 0 0 1 -1.707 .707l-6.586 -6.586" +
                "a1 1 0 0 1 0 -1.414l6.586 -6.586a1 1 0 0 1 1.707 .707v3.586h8a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1z",
        )
    }

    val ArrowBigRight by lazy {
        tabler(
            "ArrowBigRight",
            "M4 9h8v-3.586a1 1 0 0 1 1.707 -.707l6.586 6.586a1 1 0 0 1 0 1.414l-6.586 6.586" +
                "a1 1 0 0 1 -1.707 -.707v-3.586h-8a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1z",
        )
    }

    /** Tabler's "circle-check": a step done. */
    val CircleCheck by lazy { tabler("CircleCheck", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M9 12l2 2l4 -4") }

    /** Tabler's "circle": a step still to come. */
    val Circle by lazy { tabler("Circle", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0") }

    /** Tabler's "zoom-in": the target, enlarged. */
    val ZoomIn by lazy { tabler("ZoomIn", "M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M7 10l6 0", "M10 7l0 6", "M21 21l-6 -6") }

    /** Tabler's "circle-dot": the star database in use. */
    val CircleDot by lazy { tabler("CircleDot", "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0") }

    /** Tabler's "circle-x": a discarded frame. */
    val CircleX by lazy {
        tabler(
            "CircleX",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
            "M10 10l4 4m0 -4l-4 4",
        )
    }

    /** Tabler's "sunset-2": sunset, on the night's card. */
    val Sunset by lazy {
        tabler(
            "Sunset",
            "M3 13h1",
            "M20 13h1",
            "M5.6 6.6l.7 .7",
            "M18.4 6.6l-.7 .7",
            "M8 13a4 4 0 1 1 8 0",
            "M3 17h18",
            "M7 20h5",
            "M16 20h1",
            "M12 5v-1",
        )
    }

    /** Tabler's "sunrise": sunrise, on the night's card. */
    val Sunrise by lazy {
        tabler(
            "Sunrise",
            "M3 17h1m16 0h1m-15.4 -6.4l.7 .7m12.1 -.7l-.7 .7m-9.7 5.7a4 4 0 0 1 8 0",
            "M3 21l18 0",
            "M12 9v-6l3 3m-6 0l3 -3",
        )
    }

    /** Tabler's "star": the night's best hours. */
    val Star by lazy { tabler("Star", STAR) }

    /** The star filled: an object among the favourites, so that it shows without colour too, in red mode. */
    val StarFilled by lazy { tabler("StarFilled", STAR, filled = true) }

    private const val STAR =
        "M12 17.75l-6.172 3.245l1.179 -6.873l-5 -4.867l6.9 -1l3.086 -6.253l3.086 6.253l6.9 1l-5 4.867l1.179 6.873l-6.158 -3.245"

    /** Tabler's "hand-move": how to move the sky map. */
    val HandMove by lazy {
        tabler(
            "HandMove",
            "M8 13v-8.5a1.5 1.5 0 0 1 3 0v7.5",
            "M11 11.5v-2a1.5 1.5 0 0 1 3 0v2.5",
            "M14 10.5a1.5 1.5 0 0 1 3 0v1.5",
            "M17 11.5a1.5 1.5 0 0 1 3 0v4.5a6 6 0 0 1 -6 6h-2h.208a6 6 0 0 1 -5.012 -2.7l-.196 -.3c-.312 -.479 -1" +
                ".407 -2.388 -3.286 -5.728a1.5 1.5 0 0 1 .536 -2.022a1.867 1.867 0 0 1 2.28 .28l1.47 1.47",
            "M2.541 5.594a13.487 13.487 0 0 1 2.46 -1.427",
            "M14 3.458c1.32 .354 2.558 .902 3.685 1.612",
        )
    }

    /** Tabler's "stack-2": the sky map's layers. */
    val Layers by lazy {
        tabler(
            "Layers",
            "M12 4l-8 4l8 4l8 -4l-8 -4",
            "M4 12l8 4l8 -4",
            "M4 16l8 4l8 -4",
        )
    }

    /** Tabler's "aperture": A telescope's aperture. */
    val Aperture by lazy {
        tabler(
            "Aperture",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
            "M3.6 15h10.55",
            "M6.551 4.938l3.26 10.034",
            "M17.032 4.636l-8.535 6.201",
            "M20.559 14.51l-8.535 -6.201",
            "M12.257 20.916l3.261 -10.034",
        )
    }

    /** Tabler's "file-export": A ZIP to take out of the phone. */
    val FileExport by lazy {
        tabler(
            "FileExport",
            "M14 3v4a1 1 0 0 0 1 1h4",
            "M11.5 21h-4.5a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v5m-5 6h7m-3 -3l3 3l-3 3",
        )
    }

    /** Tabler's "adjustments-horizontal": the Sky Atlas's filters. */
    val Adjustments by lazy {
        tabler(
            "Adjustments",
            "M12 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M4 6l8 0",
            "M16 6l4 0",
            "M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M4 12l2 0",
            "M10 12l10 0",
            "M15 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M4 18l11 0",
            "M19 18l1 0",
        )
    }

    /** Tabler's "alert-triangle": a warning, as when the plan does not fit before dawn. */
    val Alert by lazy {
        tabler(
            "Alert",
            "M12 9v4",
            "M10.363 3.591l-8.106 13.534a1.914 1.914 0 0 0 1.636 2.871h16.214a1.914 1.914 0 0 0 1.636 -2.87l-8.10" +
                "6 -13.536a1.914 1.914 0 0 0 -3.274 0",
            "M12 16h.01",
        )
    }

    /** Tabler's "layout-grid": the mosaic template's panels, and "Frame here" on the sky map. */
    val Mosaic by lazy {
        tabler(
            "Mosaic",
            "M4 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4",
            "M14 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4",
            "M4 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4",
            "M14 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4",
        )
    }

    /** Tabler's "wifi-off": the link with the equipment lost, as "Night · alert". */
    val WifiOff by lazy {
        tabler(
            "WifiOff",
            "M12 18l.01 0",
            "M9.172 15.172a4 4 0 0 1 5.656 0",
            "M6.343 12.343a7.963 7.963 0 0 1 3.864 -2.14m4.163 .155a7.965 7.965 0 0 1 3.287 2",
            "M3.515 9.515a12 12 0 0 1 3.544 -2.455m3.101 -.92a12 12 0 0 1 10.325 3.374",
            "M3 3l18 18",
        )
    }

    /** Tabler's "bulb": a tip, as the warning's. */
    val Bulb by lazy {
        tabler(
            "Bulb",
            "M3 12h1m8 -9v1m8 8h1m-15.4 -6.4l.7 .7m12.1 -.7l-.7 .7",
            "M9 16a5 5 0 1 1 6 0a3.5 3.5 0 0 0 -1 3a2 2 0 0 1 -4 0a3.5 3.5 0 0 0 -1 -3",
            "M9.7 17l4.6 0",
        )
    }

    /** Tabler's "shield-check": the session's safety settings. */
    val Shield by lazy {
        tabler(
            "Shield",
            "M11.46 20.846a12 12 0 0 1 -7.96 -14.846a12 12 0 0 0 8.5 -3a12 12 0 0 0 8.5 3a12 12 0 0 1 -.09 7.06",
            "M15 19l2 2l4 -4",
        )
    }

    /** Tabler's "player-play": starts the night. */
    val Play by lazy { tabler("Play", "M7 4v16l13 -8l-13 -8") }

    /** Tabler's "player-pause": pauses the night's sequence. */
    val Pause by lazy {
        tabler(
            "Pause",
            "M6 6a1 1 0 0 1 1 -1h2a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1l0 -12",
            "M14 6a1 1 0 0 1 1 -1h2a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1l0 -12",
        )
    }

    /** Tabler's "focus-2": the next autofocus. */
    val Focus by lazy {
        tabler(
            "Focus",
            "M11.5 12a.5 .5 0 1 0 1 0a.5 .5 0 1 0 -1 0",
            "M5 12a7 7 0 1 0 14 0a7 7 0 1 0 -14 0",
            "M12 3l0 2",
            "M3 12l2 0",
            "M12 19l0 2",
            "M19 12l2 0",
        )
    }

    /** Tabler's "parking": when the mount parks. */
    val Parking by lazy {
        tabler(
            "Parking",
            "M3 5a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14",
            "M10 16v-8h2.667c.736 0 1.333 .895 1.333 2s-.597 2 -1.333 2h-2.667",
        )
    }

    /** Tabler's "shield-check": Night's equipment without problems. */
    val ShieldCheck by lazy {
        tabler(
            "ShieldCheck",
            "M11.46 20.846a12 12 0 0 1 -7.96 -14.846a12 12 0 0 0 8.5 -3a12 12 0 0 0 8.5 3a12 12 0 0 1 -.09 7.06",
            "M15 19l2 2l4 -4",
        )
    }

    /** Tabler's "cloud": frames discarded through clouds, in the night's diary. */
    val Cloud by lazy {
        tabler(
            "Cloud",
            "M6.657 18c-2.572 0 -4.657 -2.007 -4.657 -4.483c0 -2.475 2.085 -4.482 4.657 -4.482c.393 -1.762 1.794 " +
                "-3.2 3.675 -3.773c1.88 -.572 3.956 -.193 5.444 1c1.488 1.19 2.162 3.007 1.77 4.769h.99c1.913 0 3.464" +
                " 1.56 3.464 3.486c0 1.927 -1.551 3.487 -3.465 3.487h-11.878",
        )
    }

    /** Tabler's "box": the flat panel's cover. */
    val Box by lazy {
        tabler(
            "Box",
            "M12 3l8 4.5l0 9l-8 4.5l-8 -4.5l0 -9l8 -4.5",
            "M12 12l8 -4.5",
            "M12 12l0 9",
            "M12 12l-8 -4.5",
        )
    }

    /** Tabler's "arrow-bar-up": opens the flat panel's cover. */
    val ArrowBarUp by lazy {
        tabler(
            "ArrowBarUp",
            "M12 4l0 10",
            "M12 4l4 4",
            "M12 4l-4 4",
            "M4 20l16 0",
        )
    }

    /** Tabler's "arrow-bar-down": closes the flat panel's cover. */
    val ArrowBarDown by lazy {
        tabler(
            "ArrowBarDown",
            "M12 20l0 -10",
            "M12 20l4 -4",
            "M12 20l-4 -4",
            "M4 4l16 0",
        )
    }

    /** Tabler's "download": a download in progress. */
    val Download by lazy {
        tabler(
            "Download",
            "M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2",
            "M7 11l5 5l5 -5",
            "M12 4l0 12",
        )
    }

    /** Tabler's "snowflake": cooling the camera. */
    val Snowflake by lazy {
        tabler(
            "Snowflake",
            "M10 4l2 1l2 -1",
            "M12 2v6.5l3 1.72",
            "M17.928 6.268l.134 2.232l1.866 1.232",
            "M20.66 7l-5.629 3.25l.01 3.458",
            "M19.928 14.268l-1.866 1.232l-.134 2.232",
            "M20.66 17l-5.629 -3.25l-2.99 1.738",
            "M14 20l-2 -1l-2 1",
            "M12 22v-6.5l-3 -1.72",
            "M6.072 17.732l-.134 -2.232l-1.866 -1.232",
            "M3.34 17l5.629 -3.25l-.01 -3.458",
            "M4.072 9.732l1.866 -1.232l.134 -2.232",
            "M3.34 7l5.629 3.25l2.99 -1.738",
        )
    }

    /** Tabler's "hourglass": waiting a while. */
    val Hourglass by lazy {
        tabler(
            "Hourglass",
            "M6.5 7h11",
            "M6.5 17h11",
            "M6 20v-2a6 6 0 1 1 12 0v2a1 1 0 0 1 -1 1h-10a1 1 0 0 1 -1 -1",
            "M6 4v2a6 6 0 1 0 12 0v-2a1 1 0 0 0 -1 -1h-10a1 1 0 0 0 -1 1",
        )
    }

    /** Tabler's "mountain": a target's altitude. */
    val Mountain by lazy {
        tabler(
            "Mountain",
            "M3 20h18l-6.921 -14.612a2.3 2.3 0 0 0 -4.158 0l-6.921 14.612",
            "M7.5 11l2 2.5l2.5 -2.5l2 3l2.5 -2",
        )
    }

    /** Tabler's "note": a note in the diary. */
    val Note by lazy {
        tabler(
            "Note",
            "M13 20l7 -7",
            "M13 20v-6a1 1 0 0 1 1 -1h6v-7a2 2 0 0 0 -2 -2h-12a2 2 0 0 0 -2 2v12a2 2 0 0 0 2 2h7",
        )
    }

    /** Tabler's "arrows-split": a parallel container. */
    val Parallel by lazy {
        tabler(
            "Parallel",
            "M21 17h-8l-3.5 -5h-6.5",
            "M21 7h-8l-3.495 5",
            "M18 10l3 -3l-3 -3",
            "M18 20l3 -3l-3 -3",
        )
    }

    /** Tabler's "grip-vertical": dragging a row. */
    val Grip by lazy {
        tabler(
            "Grip",
            "M8 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M8 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M8 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M14 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M14 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M14 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        )
    }

    /** Tabler's "copy": duplicating. */
    val Copy by lazy {
        tabler(
            "Copy",
            "M7 9.667a2.667 2.667 0 0 1 2.667 -2.667h8.666a2.667 2.667 0 0 1 2.667 2.667v8.666a2.667 2.667 0 0 1 " +
                "-2.667 2.667h-8.666a2.667 2.667 0 0 1 -2.667 -2.667l0 -8.666",
            "M4.012 16.737a2.005 2.005 0 0 1 -1.012 -1.737v-10c0 -1.1 .9 -2 2 -2h10c.75 0 1.158 .385 1.5 1",
        )
    }

    /** Tabler's "template": a template. */
    val Template by lazy {
        tabler(
            "Template",
            "M4 5a1 1 0 0 1 1 -1h14a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1l0 -2",
            "M4 13a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -6",
            "M14 12l6 0",
            "M14 16l6 0",
            "M14 20l6 0",
        )
    }

    /** Tabler's "device-floppy": saving as a template. */
    val Save by lazy {
        tabler(
            "Save",
            "M6 4h10l4 4v10a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2",
            "M10 14a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M14 4l0 4l-6 0l0 -4",
        )
    }

    /** Tabler's "stack-2": the live stack's count over Imaging's viewer. */
    val Stack by lazy { tabler("Stack", "M12 4l-8 4l8 4l8 -4l-8 -4", "M4 12l8 4l8 -4", "M4 16l8 4l8 -4") }

    /** Tabler's "calendar": another night on the night's card. */
    val Calendar by lazy {
        tabler(
            "Calendar",
            "M4 7a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12",
            "M16 3v4",
            "M8 3v4",
            "M4 11h16",
            "M11 15h1",
            "M12 15v3",
        )
    }

    /** Tabler's "calendar-event": back to tonight, in the night's calendar. */
    val CalendarEvent by lazy {
        tabler(
            "CalendarEvent",
            "M4 7a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12",
            "M16 3v4",
            "M8 3v4",
            "M4 11h16",
            "M8 15h2v2h-2z",
        )
    }

    /** Tabler's "corner-down-right": moving into a container. */
    val MoveInto by lazy { tabler("MoveInto", "M6 6v6a3 3 0 0 0 3 3h10l-4 -4m0 8l4 -4") }

    /** Tabler's "repeat": the loop button beside Imaging's shutter. */
    val Repeat by lazy { tabler("Repeat", "M4 12v-3a3 3 0 0 1 3 -3h13m-3 -3l3 3l-3 3", "M20 12v3a3 3 0 0 1 -3 3h-13m3 3l-3 -3l3 -3") }

    /** Tabler's "dots-vertical": the menu of more options in a top bar. */
    val More by lazy {
        tabler(
            "More",
            "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        )
    }

    /** An outline icon: 24 × 24, with 2 px round strokes. */
    private fun tabler(name: String, vararg paths: String, filled: Boolean = false): ImageVector = ImageVector
        .Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            paths.forEach { path ->
                addPath(
                    addPathNodes(path),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
