// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.alpaca.SwitchInfo
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.ProfilePresets
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SavedSequencesTest {
    private val folder = Path(SystemTemporaryDirectory, "yacana-sequences-${ProfilePresets.newId()}")
    private val heater = SwitchInfo(0, "Dew heater", "", canWrite = true, min = 0.0, max = 1.0, step = 1.0)
    private val ir = Exposures(count = 45, exposureSeconds = 60.0, filter = "IR", gain = 80, ditherEvery = 3)
    private val m31 =
        Target(
            "M 31",
            0.712,
            41.27,
            93.2,
            listOf(TargetPanel(1, 0.70, 41.0, 93.2), TargetPanel(2, 0.72, 41.5, 93.2)),
            listOf(ir, ir.copy(filter = "LP", count = 15)),
        )
    private val m33 = Target("M 33", 1.564, 30.66, 93.2, listOf(TargetPanel(1, 1.564, 30.66, 93.2)), listOf(ir))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(folder)) {
            SystemFileSystem.list(folder).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(folder)
        }
    }

    @Test
    fun aSavedSequenceOpensAgainWithoutLosingAnything() {
        val targets = listOf(m31, m33)
        val name = SavedSequences(folder).save(tonight(targets, heater, focuser = true), targets)

        val again = SavedSequences(folder).saved.value.single()

        assertEquals("M 31 · M 33", name)
        assertEquals(name, again.name)
        // In the simple editor, the same targets with their panels, rotation and exposures.
        assertEquals(targets, again.simpleTargets())
        // And the whole tree as it was, for the advanced editor.
        assertEquals(tonight(targets, heater, focuser = true).root, again.root)
    }

    @Test
    fun savingAgainReplacesItAndItCanBeDeleted() {
        val saved = SavedSequences(folder)
        saved.save(tonight(listOf(m33), null, false), listOf(m33))
        saved.save(tonight(listOf(m33.copy(exposures = listOf(ir.copy(count = 10)))), null, false), listOf(m33))

        assertEquals(10, (SavedSequences(folder).saved.value.single().simpleTargets()!!.single().exposures.single().count))

        saved.delete("M 33")
        assertTrue(SavedSequences(folder).saved.value.isEmpty())
    }

    @Test
    fun aSequenceOfAnotherShapeIsNotTheSimpleEditors() {
        // A target that only focuses: the simple editor makes no such block.
        val focusing = Block("M 33", listOf(RunAutofocus), SkyTarget("M 33", 1.5, 30.0))
        val odd = Sequence("Odd", Block("Odd", listOf(Block("Start"), focusing, Block("End"))))

        assertNull(odd.simpleTargets())
    }

    @Test
    fun aTargetFillsTheNightFromItsStartToDawn() {
        val start = Instant.parse("2026-10-06T20:00:00Z")
        val dither = DitherOptions(settleSeconds = 10.0)

        val filled = m33.fillingTheNight(start, start + 8.hours, dither).exposures.single()

        // As many frames as fit in 8 h, less a minute to centre, and not one more.
        val available = 8.hours - 1.minutes
        assertTrue(filled.duration(dither) <= available)
        assertTrue(filled.copy(count = filled.count + 1).duration(dither) > available)
        assertEquals(m33, m33.fillingTheNight(start, null, dither))
    }

    @Test
    fun aTemplateStartsANewPlanThatCanBeUndone() {
        val targets = Targets()
        targets.add(m31)

        targets.startNew(fillTheNight = true)

        assertTrue(targets.targets.value.isEmpty())
        assertTrue(targets.fillTheNight.value)
        targets.add(m33)
        assertEquals(false, targets.fillTheNight.value)
        targets.undo()
        targets.undo()
        assertEquals(listOf(m31), targets.targets.value)
    }
}
