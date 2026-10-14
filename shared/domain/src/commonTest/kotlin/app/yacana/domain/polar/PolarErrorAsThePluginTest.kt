// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.polar

import app.yacana.domain.equipment.Atmosphere
import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * With the same three solutions, time and site, the error is within 2″ per axis of the one
 * the Three Point Polar Alignment plugin's code computes. The expected values come from the plugin
 * itself, version 2.2.6.7 in N.I.N.A. 3.2, through `tools/nina-reference/tppa.sh`, for sites in both
 * hemispheres, fields near the pole and near the meridian, with and without refraction.
 */
class PolarErrorAsThePluginTest {
    @Test
    fun theErrorIsThePluginsWithin2ArcsecondsPerAxis() {
        CASES.forEach { (conditions, fields, plugin) ->
            val case = "$conditions $fields".split(" ")
            val expected = plugin.split(" ")
            val site = Location(case[0].toDouble(), case[1].toDouble(), case[2].toDouble())
            val at = Instant.parse(case[3])
            val air = Atmosphere(case[4].toDouble(), case[5].toDouble(), case[6].toDouble(), case[7].toDouble())
            val points = (0..2).map { SolvedPoint(SkyPosition(case[9 + 2 * it].toDouble() / 15, case[10 + 2 * it].toDouble()), at) }

            val error = PolarErrorMeasurement(points, site, air, correctForRefraction = case[8].toBoolean()).initial

            val altitude = abs(error.altitudeDeg - expected[0].toDouble()) * 3600
            val azimuth = abs(error.azimuthDeg - expected[1].toDouble()) * 3600
            assertTrue(altitude <= 2.0 && azimuth <= 2.0, "$case: altitude $altitude″ and azimuth $azimuth″ from the plugin")
        }
    }

    private data class Case(val conditions: String, val fields: String, val plugin: String)

    private companion object {
        /**
         * The conditions (latitude, longitude, elevation, time, pressure, temperature, humidity,
         * wavelength and refraction adjustment), the three fields' J2000 right ascension and
         * declination in degrees, and the plugin's altitude and azimuth error.
         */
        val CASES =
            listOf(
                Case(
                    "40.0 0.0 250 2000-01-01T00:00:00Z 1013.25 15 0 0.55 true",
                    "54.30570261 82.59066212 39.30570261 82.56500830 24.30570261 82.59281504",
                    "0.36855259030565435 -0.8093441573744826",
                ),
                Case(
                    "40.0 0.0 250 2026-10-05T22:00:00Z 0 0 0 0 false",
                    "315.16867986 22.68622166 300.16867986 22.65847140 285.16867986 22.68224040",
                    "0.3875737647260351 -0.7472301063079385",
                ),
                Case(
                    "40.0 0.0 250 2025-03-15T03:30:00Z 1005 7 0.8 0.574 true",
                    "32.65668480 80.55884339 47.65668480 80.57845452 62.65668480 80.53627151",
                    "1.1361677820521692 -0.1318209074272545",
                ),
                Case(
                    "49.0 7.0 250 2000-01-01T00:00:00Z 0 0 0 0 false",
                    "225.87596007 81.78591172 210.87596007 81.79053789 195.87596007 81.77971255",
                    "0.06931480839320159 -0.3349559999959979",
                ),
                Case(
                    "49.0 7.0 250 2026-10-05T22:00:00Z 1005 7 0.8 0.574 true",
                    "322.05451742 19.47489789 307.05451742 19.46227445 292.05451742 19.45355319",
                    "-0.10815175473155136 -0.15617275606058456",
                ),
                Case(
                    "49.0 7.0 250 2025-03-15T03:30:00Z 1013.25 15 0 0.55 false",
                    "111.05345668 80.94233790 96.05345668 80.92318149 81.05345668 80.94723391",
                    "-0.3299767514068179 -0.7966861184642084",
                ),
                Case(
                    "52.5 -1.2 100 2000-01-01T00:00:00Z 1005 7 0.8 0.574 true",
                    "134.06311538 85.11130775 119.06311538 85.08507509 104.06311538 85.08488382",
                    "0.3622979437139975 0.13715576492354034",
                ),
                Case(
                    "52.5 -1.2 100 2026-10-05T22:00:00Z 1013.25 15 0 0.55 false",
                    "320.19269035 38.38082574 335.19269035 38.36967457 350.19269035 38.38595946",
                    "0.24711688600163484 -0.1769634661268924",
                ),
                Case(
                    "52.5 -1.2 100 2025-03-15T03:30:00Z 0 0 0 0 true",
                    "107.91611887 83.62547501 92.91611887 83.63741468 77.91611887 83.61012080",
                    "0.49502516383666517 0.6728102803737595",
                ),
                Case(
                    "28.3 -16.5 2390 2000-01-01T00:00:00Z 1013.25 15 0 0.55 false",
                    "189.07074137 84.59538968 174.07074137 84.60915640 159.07074137 84.58266595",
                    "0.033160450277275544 -0.7333194871649766",
                ),
                Case(
                    "28.3 -16.5 2390 2026-10-05T22:00:00Z 0 0 0 0 true",
                    "299.26934840 -1.30349695 314.26934840 -1.28806849 329.26934840 -1.32437788",
                    "-0.8544033979361529 0.15142363879986415",
                ),
                Case(
                    "28.3 -16.5 2390 2025-03-15T03:30:00Z 1005 7 0.8 0.574 false",
                    "14.11461254 83.91170480 359.11461254 83.92757906 344.11461254 83.91608636",
                    "0.5052771089695227 -0.3174642574268205",
                ),
                Case(
                    "-35.0 150.0 50 2000-01-01T00:00:00Z 0 0 0 0 true",
                    "112.94910463 -87.00382249 97.94910463 -87.00948469 82.94910463 -87.00861621",
                    "-0.09667010523014596 -0.03922551691385934",
                ),
                Case(
                    "-35.0 150.0 50 2026-10-05T22:00:00Z 1005 7 0.8 0.574 false",
                    "112.98836842 -15.87589337 97.98836842 -15.87433927 82.98836842 -15.88574251",
                    "-0.05939740247352887 0.2584534492627313",
                ),
                Case(
                    "-35.0 150.0 50 2025-03-15T03:30:00Z 1013.25 15 0 0.55 true",
                    "252.53712767 -80.48535542 237.53712767 -80.51494118 222.53712767 -80.50467091",
                    "-0.6468176046210488 -0.42386131213010003",
                ),
                Case(
                    "-23.5 -46.6 760 2000-01-01T00:00:00Z 1005 7 0.8 0.574 false",
                    "138.88491928 -82.27676426 123.88491928 -82.24811803 108.88491928 -82.27446597",
                    "-0.23933898884024885 -0.7718375875690526",
                ),
                Case(
                    "-23.5 -46.6 760 2026-10-05T22:00:00Z 1013.25 15 0 0.55 true",
                    "269.15964856 -10.13903242 284.15964856 -10.15512640 299.15964856 -10.11679284",
                    "0.6946252732449381 -0.3930420281330953",
                ),
                Case(
                    "-23.5 -46.6 760 2025-03-15T03:30:00Z 0 0 0 0 false",
                    "140.74189313 -81.98091867 125.74189313 -81.95575355 110.74189313 -81.97786991",
                    "-0.2413928601430655 0.5611336683260788",
                ),
            )
    }
}
