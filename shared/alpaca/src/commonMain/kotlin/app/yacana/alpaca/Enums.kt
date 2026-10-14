// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

// The ASCOM enumerations, in the order of their values: the first entry is 0.

/** How the mount is aligned: altitude-azimuth, polar (fork or one arm, like the Seestar on a wedge) or German equatorial. */
enum class AlignmentMode { AltAz, Polar, GermanPolar }

/** The coordinate system of the mount's right ascension and declination. Topocentric means JNow. */
enum class EquatorialSystem { Other, Topocentric, J2000, J2050, B1950 }

enum class DriveRate { Sidereal, Lunar, Solar, King }

/** The side of the pier the mount is pointing from. ASCOM's "unknown" (-1) is null. */
enum class PierSide { East, West }

enum class CameraState { Idle, Waiting, Exposing, Reading, Download, Error }

/** The sensor type. For a one-shot colour camera, [Rggb] plus the Bayer offsets give the real pattern. */
enum class SensorType { Monochrome, Color, Rggb, Cmyg, Cmyg2, Lrgb }

enum class GuideDirection { North, South, East, West }

/** The mount's axes: right ascension (or azimuth), declination (or altitude), and the rotator. */
enum class TelescopeAxis { Primary, Secondary, Tertiary }

/** The flat panel's cover: absent, closed, moving, open, unknown or in error. */
enum class CoverStatus { NotPresent, Closed, Moving, Open, Unknown, Error }

/** The flat panel's light: absent, off, warming up, ready, unknown or in error. */
enum class CalibratorStatus { NotPresent, Off, NotReady, Ready, Unknown, Error }

/** The entry with ASCOM value [value]. */
internal inline fun <reified T : Enum<T>> ascomEnum(value: Int): T =
    enumValues<T>().getOrNull(value) ?: throw AlpacaProtocolException("Unknown ${T::class.simpleName} value $value")
