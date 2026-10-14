// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// The cases come from the tests of N.I.N.A.'s Three Point Polar Alignment plugin
// (PolarErrorDeterminationTest), Copyright © Stefan Berg and the N.I.N.A. contributors, MPL-2.0.

package app.yacana.domain.polar

import app.yacana.domain.equipment.AltAz
import app.yacana.domain.equipment.Atmosphere
import app.yacana.domain.equipment.j2000FromObserved
import app.yacana.domain.equipment.j2000Place
import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PolarErrorTest {
    private val y2000 = Instant.parse("2000-01-01T00:00:00Z")

    /** The plugin's test weather: 1005 hPa, 7 °C, humidity 0.8, at 0.574 µm. */
    private val air = Atmosphere(1005.0, 7.0, 0.8, 0.574)

    private val arcsec = 1.0 / 3600

    private fun jnow(raDeg: Double, decDeg: Double, at: Instant) = SolvedPoint(j2000Place(SkyPosition(raDeg / 15, decDeg), at), at)

    private fun j2000(raDeg: Double, decDeg: Double) = SolvedPoint(SkyPosition(raDeg / 15, decDeg), y2000)

    @Test
    fun theOrderOfThePointsDoesNotChangeTheError() {
        val site = Location(49.0, 7.0, 250.0)
        val air = Atmosphere(0.0, 0.0001, 0.2, 0.55)
        val points = listOf(jnow(20.0, 40.0, y2000), jnow(60.0, 41.0, y2000), jnow(90.0, 42.0, y2000))

        val forward = PolarErrorMeasurement(points, site, air, correctForRefraction = true).initial
        val backward = PolarErrorMeasurement(points.reversed(), site, air, correctForRefraction = true).initial

        assertTrue(forward.totalDeg > 0.0001)
        assertEquals(forward.altitudeDeg, backward.altitudeDeg, arcsec)
        assertEquals(forward.azimuthDeg, backward.azimuthDeg, arcsec)
    }

    @Test
    fun pointsOnACircleAroundThePoleHaveNoError() {
        val site = Location(49.0, 7.0, 250.0)
        val points = listOf(jnow(90.0, 40.0, y2000), jnow(60.0, 40.0, y2000), jnow(20.0, 40.0, y2000))

        val error = PolarErrorMeasurement(points, site, Atmosphere.NONE, correctForRefraction = true).initial

        assertEquals(0.0, error.altitudeDeg, arcsec)
        assertEquals(0.0, error.azimuthDeg, arcsec)
    }

    /** The plugin's synthetic mount: its axis 1° east and 1° higher than the pole, at latitude 40°. */
    private fun syntheticFields(site: Location, axis: AltAz, first: AltAz, step: Double): List<SolvedPoint> {
        val p = axis.toVector()
        val v1 = first.toVector()
        val v2 = v1.rotate(p, step)
        val v3 = v2.rotate(p, step)
        // Where each field is in J2000, seen there through the air: as plate solving finds it.
        return listOf(v3, v2, v1).map { SolvedPoint(j2000FromObserved(it.toAltAz(), y2000, site, air), y2000) }
    }

    @Test
    fun aKnownErrorIsFoundAgainstTheTruePole() {
        val site = Location(40.0, 0.0, 250.0)
        val fields = syntheticFields(site, AltAz(41.0, 1.0), AltAz(20.0, 70.0), step = -30.0)

        val error = PolarErrorMeasurement(fields, site, air, correctForRefraction = true).initial

        assertEquals(1.0, error.altitudeDeg, arcsec)
        assertEquals(1.0, error.azimuthDeg, arcsec)
    }

    @Test
    fun againstTheRefractedPoleTheAltitudeLosesTheRefraction() {
        val site = Location(40.0, 0.0, 250.0)
        val fields = syntheticFields(site, AltAz(41.0, 1.0), AltAz(20.0, 70.0), step = -30.0)

        val error = PolarErrorMeasurement(fields, site, air, correctForRefraction = false).initial

        assertEquals(1.0 - 69.3 / 3600, error.altitudeDeg, arcsec)
        assertEquals(1.0, error.azimuthDeg, arcsec)
    }

    @Test
    fun astropysFieldsFarApart() {
        // The plugin's fields, computed with Astropy for an axis 1° east and 1° higher than the pole.
        val fields = listOf(j2000(127.00972423, 27.34989335), j2000(156.6798968, 27.40124463), j2000(186.4193401, 27.75369312))
        val site = Location(40.0, 0.0, 250.0)

        val truePole = PolarErrorMeasurement(fields, site, air, correctForRefraction = true).initial
        val refracted = PolarErrorMeasurement(fields, site, air, correctForRefraction = false).initial

        assertEquals(1.0, truePole.altitudeDeg, arcsec)
        assertEquals(1.0, truePole.azimuthDeg, arcsec)
        assertEquals(1.0 - 69.3 / 3600, refracted.altitudeDeg, arcsec)
        assertEquals(1.0, refracted.azimuthDeg, arcsec)
    }

    @Test
    fun astropysFieldsNearThePole() {
        // The plugin's default geometry: three fields near the pole, 10° apart in right ascension.
        val fields = listOf(j2000(147.13178399, 87.8022287), j2000(133.73661393, 87.76679023), j2000(120.40437197, 87.88183512))
        val site = Location(40.0, 0.0, 250.0)

        val truePole = PolarErrorMeasurement(fields, site, air, correctForRefraction = true).initial
        val refracted = PolarErrorMeasurement(fields, site, air, correctForRefraction = false).initial

        assertEquals(1.0, truePole.altitudeDeg, arcsec)
        assertEquals(1.0, truePole.azimuthDeg, arcsec)
        assertEquals(1.0 - 69.0 / 3600, refracted.altitudeDeg, arcsec)
        assertEquals(1.0, refracted.azimuthDeg, arcsec)
    }

    @Test
    fun inTheSouthTheAxisIsComparedWithTheSouthPole() {
        // At latitude −35°, an axis 1° higher than the south pole and 1° west of south.
        val site = Location(-35.0, 150.0, 50.0)
        val fields = syntheticFields(site, AltAz(36.0, 181.0), AltAz(30.0, 220.0), step = 30.0)

        val error = PolarErrorMeasurement(fields, site, air, correctForRefraction = true).initial

        // The plugin signs them the other way round in the south.
        assertEquals(-1.0, error.altitudeDeg, arcsec)
        assertEquals(1.0, error.azimuthDeg, arcsec)
    }

    @Test
    fun theLiveErrorFollowsTheFieldAsTheScrewsTurn() {
        // 6′ in each axis, as when the screws are turned after a rough alignment. The plugin's live
        // estimate works on the image plane, so it is exact only as the error gets small: after
        // correcting 1° it would still say about 20″.
        val site = Location(40.0, 0.0, 250.0)
        val fields = syntheticFields(site, AltAz(40.1, 0.1), AltAz(20.0, 70.0), step = -30.0)
        val measurement = PolarErrorMeasurement(fields, site, air, correctForRefraction = true)
        val later = y2000 + 30.seconds
        val initial = measurement.initial

        // Untouched, the error is the one measured.
        val untouched = measurement.current(SolvedPoint(measurement.reference.centre, later))
        assertEquals(initial.altitudeDeg, untouched.altitudeDeg, arcsec)
        assertEquals(initial.azimuthDeg, untouched.azimuthDeg, arcsec)

        // With the azimuth corrected, only the altitude is left. Correcting turns the field by the
        // error; the plugin's destination, the other way, is where the reference star goes in the image.
        val azimuthDone = measurement.current(SolvedPoint(measurement.destination(initial.azimuthDeg, 0.0, later), later))
        assertEquals(0.0, azimuthDone.azimuthDeg, arcsec)
        assertEquals(initial.altitudeDeg, azimuthDone.altitudeDeg, arcsec)

        // With both corrected, nothing is left.
        val done = measurement.current(SolvedPoint(measurement.destination(initial.azimuthDeg, initial.altitudeDeg, later), later))
        assertEquals(0.0, done.altitudeDeg, arcsec)
        assertEquals(0.0, done.azimuthDeg, arcsec)
    }
}
