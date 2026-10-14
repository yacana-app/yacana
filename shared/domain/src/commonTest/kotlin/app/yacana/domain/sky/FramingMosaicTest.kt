// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.Targets
import app.yacana.ports.SkyPosition
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramingMosaicTest {
    /** The Seestar S50 Pro's field, 1.35° × 2.40°. */
    private val seestar = 1.35 to 2.40
    private val m31 = SkyPosition(0.7123, 41.27)

    private fun separationArcsec(a: SkyPosition, b: SkyPosition) = SkyView.separation(a, b) * 3600

    @Test
    fun aSingleFieldIsSentAsFramed() {
        val framing = Framing(m31, 35.0, seestar)
        val panel = framing.panels.single()

        // The centre within 1″ and the rotation within 0.1° of the framing's.
        assertTrue(separationArcsec(panel.center, m31) <= 1.0)
        assertTrue(abs(panel.rotationDeg - 35.0) <= 0.1)
        val target = Target.of("M 31", framing)
        assertEquals(m31, target.position)
        assertEquals(35.0, target.rotationDeg)
    }

    @Test
    fun aMosaicTakesAnyColumnsAndRows() {
        // As NINA's: a strip of 10 along the long side, or of 9 across it.
        listOf(10 to 1, 1 to 9).forEach { (columns, rows) ->
            val framing = Framing(m31, 35.0, seestar, columns = columns, rows = rows)
            val panels = framing.panels
            assertEquals(columns * rows, panels.size)
            assertEquals((1..columns * rows).toList(), panels.map { it.number })
            // Each next to the one before, a field less the overlap apart: on the sky's tangent plane at the
            // centre, the panels far out in a long strip come a little closer, overlapping more, never less.
            val side = if (columns > 1) 2.40 else 1.35
            val apart = side * (1 - Framing.DEFAULT_OVERLAP)
            panels.zipWithNext().forEach { (a, b) ->
                val separation = SkyView.separation(a.center, b.center)
                assertTrue(separation <= apart + 0.005 && separation >= apart * 0.95, "$columns × $rows: $separation")
            }
        }
    }

    @Test
    fun aLargeObjectGetsAsManyPanelsAsItNeeds() {
        // 10° by 1°, along the field's long side: more than the 4 × 4 the framing used to stop at.
        val loop =
            SkyObject(
                listOf(
                    "Big",
                ),
                ObjectType.EmissionNebula, SkyPosition(5.5, 0.0), "Ori", 600.0, 60.0, 90.0, null, null, emptyList(), null,
            )
        val framing = Framing.of(loop, seestar, rotationDeg = 0.0)

        assertTrue(framing.columns > 4, "${framing.columns} × ${framing.rows}")
        assertTrue(framing.widthDeg >= 10.0)
    }

    @Test
    fun panelsOverlapByWhatIsAsked() {
        listOf(0.0, 35.0, 90.0, 200.0).forEach { rotation ->
            listOf(0.10, 0.15, 0.30).forEach { overlap ->
                val framing = Framing(m31, rotation, seestar, columns = 3, rows = 2, overlap = overlap)
                val panels = framing.panels
                assertEquals(6, panels.size)
                // Neighbours along the long side are a field less the overlap apart, and so across.
                val along = SkyView.separation(panels[0].center, panels[1].center)
                val across = SkyView.separation(panels[0].center, panels[3].center)
                assertTrue(abs(along - 2.40 * (1 - overlap)) < 0.005, "along $along at $rotation° and $overlap")
                assertTrue(abs(across - 1.35 * (1 - overlap)) < 0.005, "across $across at $rotation° and $overlap")
                // The mosaic's centre stays where it was framed.
                val middle = SkyPosition(
                    (panels[1].center.raHours + panels[4].center.raHours) / 2,
                    (panels[1].center.decDeg + panels[4].center.decDeg) / 2,
                )
                assertTrue(SkyView.separation(middle, m31) < 0.01)
            }
        }
    }

    @Test
    fun panelsKeepTheFramingsTurnWhereTheyAre() {
        val framing = Framing(m31, 35.0, seestar, columns = 2)
        framing.panels.forEach { assertTrue(abs(it.rotationDeg - 35.0) < 2, "${it.number}: ${it.rotationDeg}") }
        // Up the frame is the framing's rotation from north: with no rotation, the panels side by side east and west.
        val plain = Framing(m31, 0.0, seestar, columns = 2).panels
        assertTrue(plain[0].center.raHours > plain[1].center.raHours, "the first panel, on the left, is east")
        assertTrue(abs(plain[0].center.decDeg - plain[1].center.decDeg) < 0.05)
    }

    @Test
    fun objectsAreFramedAsTheCameraIsTurned() {
        val andromeda = SkyObject(listOf("M 31"), ObjectType.Galaxy, m31, "And", 178.0, 70.0, 35.0, 3.4, null, emptyList(), null)
        // The Seestar's, its long side nearly north to south: M 31, turned 32° against it, takes 2 × 2.
        val seestarFraming = Framing.of(andromeda, seestar, 93.2)
        assertEquals(93.2, seestarFraming.rotationDeg)
        assertEquals(2 to 2, seestarFraming.columns to seestarFraming.rows)
        // A camera turned along the galaxy would take it in 2 × 1.
        assertEquals(2 to 1, Framing.of(andromeda, seestar, 125.0).let { it.columns to it.rows })
    }

    @Test
    fun theCamerasRotationFromWhereTheFramesYAxisPoints() {
        // The Seestar's frames are 2160 × 3840, their y axis the long side, pointing 183.2° from north through east.
        assertEquals(93.2, cameraRotation(183.2, 2160, 3840), 1e-9)
        // A wide frame's long side is its x axis, a right angle from y.
        assertEquals(3.2, cameraRotation(183.2, 3840, 2160), 1e-9)
    }

    @Test
    fun tonightsTargetsStayOnThePhone() {
        val file = Path(SystemTemporaryDirectory, "yacana-targets-${kotlin.random.Random.nextLong()}.json")
        try {
            Targets(file).add(Target.of("M 31", Framing(m31, 35.0, seestar, columns = 2)))
            val kept = Targets(file).targets.value.single()
            assertEquals("M 31", kept.name)
            assertEquals(2, kept.panels.size)
            assertEquals(35.0, kept.rotationDeg)
            Targets(file).remove(0)
            assertEquals(emptyList(), Targets(file).targets.value)
        } finally {
            if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
        }
    }
}
