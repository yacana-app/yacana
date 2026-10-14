# Style guide

How Yacana's screens look and behave, for anyone drawing or building one. The screens as they are live in the app's reference screenshots, `app/src/test/screenshots`, in both themes and both orientations; the components, in `shared/ui/src/commonMain/kotlin/app/yacana/ui/design/`. To draw a new screen, use the [design kit](kit.html).

## Principles

1. **By task, not by panel.** The app follows the night: prepare, align, focus, frame, capture, watch and pack up. At each moment it shows what is needed and leaves the rest one tap away, without removing any of N.I.N.A.'s power.
2. **The frequent in view, the advanced one tap away.** There is no expert mode: each screen has its own "more", and the sequences have an advanced editor.
3. **The app works out what N.I.N.A. asks for.** Scale, star database, autofocus step: they come from the equipment's profile, and the Seestar's comes ready-made.
4. **Every step explains itself.** What a setting does, what opens next and what to tap is said on the screen, in a line under it, never only in the manual.
5. **Made for the night and the cold.** Red mode in every pixel, big targets, no system keyboard at night, and press and hold for anything risky.
6. **Nothing fails silently.** The session bar is on every screen and the notification on the lock screen; an alert says what happened, what the app is doing and what you can do.
7. **Nothing is called good too early.** Guidance, such as the polar alignment's, keeps showing which way to go until the exact value; the user decides when it is good enough.

## Structure

- **Five sections**, in the bottom bar, or in a side rail with the phone in landscape: Night, Equipment, Sky, Sequence and Imaging.
- **Full-screen assistants** for polar alignment, autofocus and flats. They close with a cross and have no bars.
- **Settings**, from the gear, with no section bar either.
- **Sheets** come up from the bottom over everything, bottom bar included, for choices and short forms. They close by swiping down, with their own button, or by tapping outside.
- **Phone only**, in portrait and in landscape. Every screen works in both; landscape uses the width where it helps (Imaging, framing, polar alignment). Tablets use the phone's layout.

## Colour

Two themes, no light theme and no dynamic colour. Material 3 colour roles, set in `ui/theme/Theme.kt`.

| Role | Dark | Night red |
| --- | --- | --- |
| Background | `#0B0F14` | `#000000` |
| Cards (`surfaceContainer`) | `#151B24` | `#140000` |
| Raised surface, grey buttons (`surfaceContainerHigh`) | `#1F2733` | `#240000` |
| Lines (`outline`) | `#2C3644` | `#3D0000` |
| Text | `#E8ECF1` | `#E00000` |
| Muted text (`onSurfaceVariant`) | `#98A4B4` | `#A80000` |
| Accent, the llama's eye | `#E8B04A` | `#FF0000` |
| Accent fill, its text | `#E8B04A`, `#2B1D03` | `#4A0000`, `#FF0000` |
| Good | `#5DCAA5` | `#E00000` |
| Error | `#F07A7A` | `#E00000` |
| The telescope on the map | `#5EA8FF` | `#E00000` |

- **Gold is for the one main action** of a screen or a card, and for what is chosen. Everything else is grey.
- **Never say something by colour alone.** In red mode green, amber and red look the same: a state also has its icon or its word.
- **Night red is red on black in every pixel**, photos and the logo included. The screenshot test fails if a red-mode screen has any green or blue.

## Type

- Material 3's type scale with the system font.
- Figures have fixed width (`tnum`), so numbers do not jump when they change.
- Numbers follow the language: a decimal comma in every language but English, and coordinates in degrees, minutes and seconds.
- Readouts that must be read from a metre away, such as the polar error, are large.

## Components

All in `ui/design/`; use them before making a new one.

| Component | What for |
| --- | --- |
| `SectionCard` | A card: the lower surface, rounded, inset from the edges. A small muted heading may go first |
| `DeviceRow` | A row with an icon, a name, a line under it and something at the end: a chevron, a switch, a value |
| `AccentButton` | The main action, filled in the accent |
| `SurfaceButton` | Another action, on the raised surface |
| `SettingChip` | A pill button: a setting that opens its choices, or one of a set; the chosen one in the accent |
| `HoldButton` | A risky action (park, stop a sequence, delete): it fills while pressed and acts after about a second |
| `Stepper` | A value with − and + |
| `NumberEntry`, `NumberPad` | Numbers on the app's own keypad, never the system keyboard |
| `BottomSheet` | A sheet, with an optional header (title, count, actions) |
| `SearchField` | A search box: a pill with a magnifier |
| `Readout`, `ReadingGrid` | Values with their labels |
| `StatusLine`, `StatusMark` | A state, with its icon and its words |

## Icons

[Tabler Icons](https://tabler.io/icons), outline, copied as path data into `ui/YacanaIcons.kt`. One icon per meaning across the app; an icon alone needs a content description, and an action that is not obvious has its word too.

## Sizes

- Touch targets of at least 48 dp, and 56 dp for the main actions.
- Cards are inset 14 dp from the edges; rows have 10 dp above and below.
- The map's round buttons are 40 dp, in a column at its side.

## Texts

- The app speaks ten languages; English is the base. A new text goes into every `strings.xml`, and it must fit in all of them (`YACANA_TEXTS=1` renders every screen in each language).
- Short, plain and concrete: say what happens, with the numbers that matter ("10 frames of 60 s take about 11 min").
- Name places the way the screen does: *Settings › About*.

## A new screen or a change

1. Draw it with the [design kit](kit.html): copy the template phone or a similar example into a copy of the kit, and check it in both themes.
2. Agree on it in the issue or the pull request before building it.
3. Build it with the components above, in both orientations.
4. Record its reference screenshots (`./gradlew :app:recordRoborazziDebug`), look at them in dark and red, portrait and landscape, and check its texts in the ten languages.

The logo and its rules are in [`docs/brand/`](../brand/README.md).
