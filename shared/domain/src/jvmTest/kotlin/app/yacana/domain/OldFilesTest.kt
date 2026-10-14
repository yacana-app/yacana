// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain

import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.HorizonPoint
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileFile
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.RunProgress
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SavedSequences
import app.yacana.domain.sequence.Targets
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.areas
import app.yacana.domain.sky.Favourites
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The files older versions of the app saved still open, as each version wrote them: those
 * of v0.1.2, the first on phones, and those of the last version before the advanced editor
 * changed the sequence, made by that version's own code into `resources/old-files`.
 * When a version changes a file's format, its files are added the same way: written by a test
 * in a checkout of the version before.
 */
class OldFilesTest {
    @Test
    fun aProfileOfVersion012OpensWithItsDevicesSiteAndOptions() {
        val profile = ProfileStore(Path(V012, "profiles")).list().single()

        assertSeestar(profile)
        assertEquals("D80", profile.options.starDatabase)
        assertEquals(20.0, profile.options.minAltitudeDeg)
        assertEquals(false, profile.options.locationFromPhone)
        assertEquals(listOf(HorizonPoint(0.0, 10.0), HorizonPoint(180.0, 25.0)), profile.options.horizon)
    }

    @Test
    fun aProfileExportedByVersion012IsImported() {
        assertSeestar(ProfileFile.decode(text(Path(V012, "profile-export.json"))).profile)
    }

    @Test
    fun aProfileFromBeforeTheAdvancedEditorKeepsEveryOption() {
        val profile = ProfileStore(Path(BEFORE_ADVANCED, "profiles")).list().single()
        val options = profile.options

        assertSeestar(profile)
        assertEquals(listOf("Dark", "IR", "LP"), profile.devices.filterNames)
        assertEquals(2, options.solver.downsample)
        assertEquals(15.0, options.solver.searchRadiusDeg)
        assertEquals(30.0, options.polarAlignment.stepDeg)
        assertEquals(true, options.polarAlignment.hintOnly)
        assertEquals(FocusFitting.Parabolic, options.autofocus.fitting)
        assertEquals(mapOf("LP" to -25), options.autofocus.filterOffsets)
        // Its pulse of 3 s gives way to N.I.N.A.'s dither of 5 pixels; the settle time stays.
        assertEquals(DitherOptions(pixels = 5.0, settleSeconds = 10.0), options.dither)
        assertEquals(false, options.safety.park)
        assertEquals(profile, ProfileFile.decode(text(Path(BEFORE_ADVANCED, "profile-export.json"))).profile)
    }

    @Test
    fun tonightsPlanFromBeforeTheAdvancedEditorOpensInTheSimpleOne() {
        val targets = Targets(Path(BEFORE_ADVANCED, "targets.json"))

        assertEquals(listOf("M 31", "NGC 7000"), targets.targets.value.map { it.name })
        assertEquals(2, targets.targets.value[1].panels.size)
        assertEquals(listOf(Exposures(60, 120.0, "IR", ditherEvery = 3)), targets.targets.value[0].exposures)
        assertEquals(FrameType.Dark, targets.targets.value[1].exposures[1].type)
        assertEquals(listOf(Trigger.AutofocusAfterTemperature(2.0), Trigger.AutofocusAfterFilter), targets.triggers.value)
        assertNull(targets.advanced.value)
    }

    @Test
    fun aSavedSequenceFromBeforeTheAdvancedEditorOpensAsTonightsPlan() {
        val saved = SavedSequences(Path(BEFORE_ADVANCED, "sequences")).saved.value.single()
        val tonight = Targets()

        tonight.open(saved)

        assertEquals("M 31 · NGC 7000", saved.name)
        assertEquals(listOf("M 31", "NGC 7000"), tonight.targets.value.map { it.name })
        assertNull(tonight.advanced.value)
        // In the advanced editor, its start, its two targets and its end are the night's areas.
        val areas = saved.areas()
        assertEquals(listOf("M 31", "NGC 7000"), areas.targets.items.map { (it as Block).name })
        assertEquals(2, areas.start.items.size)
    }

    @Test
    fun aRunInProgressFromBeforeTheAdvancedEditorComesBackPaused() {
        val run = assertNotNull(RunProgress(Path(BEFORE_ADVANCED, "sequence-run.json")).read(KEPT_AT + 10.minutes))

        assertEquals(RunStatus.Paused, run.status)
        assertEquals(listOf(1, 1), run.current)
        assertEquals(mapOf(listOf(1, 1) to 12), run.done)
        assertEquals("M 31", (run.sequence.root.items[1] as Block).name)
    }

    @Test
    fun theFavouritesFromBeforeTheAdvancedEditorAreKept() {
        assertEquals(setOf("M 31", "NGC 7000"), Favourites(Path(BEFORE_ADVANCED, "favourites.txt")).marked.value)
    }

    /** The fake Seestar's profile, as both versions saved it. */
    private fun assertSeestar(profile: Profile) {
        assertEquals("seestar-s50", profile.id)
        assertEquals("Seestar S50", profile.name)
        assertEquals("http://10.0.0.1:32323", profile.devices.mount?.serverUrl)
        assertEquals("filterwheel-0", profile.devices.filterWheel?.uniqueId)
        assertEquals(250.0, profile.optics.focalLengthMm)
        assertEquals(Location(40.4, -3.7, 650.0), profile.location)
    }

    private fun text(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }

    private companion object {
        /** The files, from the module's folder, where the tests run. */
        const val FILES = "src/jvmTest/resources/old-files"
        val V012 = Path(FILES, "v0.1.2")
        val BEFORE_ADVANCED = Path(FILES, "before-advanced")

        /** When the run in progress was kept. */
        val KEPT_AT = Instant.parse("2026-10-06T23:00:00Z")
    }
}
