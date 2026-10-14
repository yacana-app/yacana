// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.profile.ProfilePresets
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetsTest {
    private val file = Path(SystemTemporaryDirectory, "yacana-targets-${ProfilePresets.newId()}.json")
    private val m33 = Target("M 33", 1.564, 30.66, 93.2, listOf(TargetPanel(1, 1.564, 30.66, 93.2)), newTargetExposures(listOf("IR")))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    }

    @Test
    fun theLastTwentyChangesAndMoreAreUndone() {
        val targets = Targets()
        targets.add(m33)
        val counts = (1..25).map { it * 10 }
        counts.forEach { count -> targets.setExposures(0, listOf(m33.exposures.single().copy(count = count))) }

        repeat(24) { targets.undo() }

        assertEquals(10, targets.targets.value.single().exposures.single().count)
        targets.undo()
        assertEquals(NEW_TARGET_FRAMES, targets.targets.value.single().exposures.single().count)
        targets.undo()
        assertEquals(emptyList(), targets.targets.value)
        assertFalse(targets.canUndo.value)
    }

    @Test
    fun theTargetsAndTheirExposuresComeBackFromTheFile() {
        val lp = Exposures(30, 120.0, "LP", ditherEvery = 3)
        Targets(file).apply {
            add(m33)
            setExposures(0, m33.exposures + lp)
        }

        val again = Targets(file)

        assertEquals(listOf(m33.copy(exposures = m33.exposures + lp)), again.targets.value)
        assertFalse(again.canUndo.value)
    }

    @Test
    fun theTriggersAreKeptAndUndoneWithTheTargets() {
        val chosen = listOf(Trigger.AutofocusAfterFilter, Trigger.AutofocusAfterTime(2.0))
        Targets(file).apply {
            assertEquals(DEFAULT_TRIGGERS, triggers.value)
            add(m33)
            setTriggers(chosen)
        }

        val again = Targets(file)

        assertEquals(chosen, again.triggers.value)
        assertEquals(listOf(m33), again.targets.value)
        again.setTriggers(emptyList())
        again.undo()
        assertEquals(chosen, again.triggers.value)
    }

    @Test
    fun theDarkRulesAndTheirTriggerAreKeptAndUndoneWithThePlan() {
        Targets(file).apply {
            assertEquals(DarkRules(), darks.value)
            add(m33)
            setDarks(DarkRules(atStart = true, count = 20), temperature = 3.0)
        }

        val again = Targets(file)

        assertEquals(DarkRules(atStart = true, count = 20), again.darks.value)
        assertEquals(DEFAULT_TRIGGERS + Trigger.DarksAfterTemperature(3.0, 20), again.triggers.value)
        again.replace(listOf(m33))
        assertEquals(DarkRules(atStart = true, count = 20), again.darks.value, "a plan opened again keeps them")
        again.setDarks(DarkRules(), temperature = null)
        assertEquals(DEFAULT_TRIGGERS, again.triggers.value)
        again.undo()
        assertEquals(DarkRules(atStart = true, count = 20), again.darks.value)
    }

    @Test
    fun aTargetFromBeforeHasNoExposures() {
        SystemFileSystem.sink(file).use { sink ->
            val json = """[{"name":"M 33","raHours":1.564,"decDeg":30.66,"rotationDeg":93.2,"panels":[]}]"""
            val bytes = json.encodeToByteArray()
            sink.write(kotlinx.io.Buffer().apply { write(bytes) }, bytes.size.toLong())
        }

        val target = Targets(file).targets.value.single()

        assertEquals("M 33", target.name)
        assertTrue(target.exposures.isEmpty())
    }

    @Test
    fun anAdvancedNightIsKeptAndTakesTheTargetsAddedFromCielo() {
        val m42 = Target("M 42", 5.588, -5.39, 0.0, listOf(TargetPanel(1, 5.588, -5.39, 0.0)), newTargetExposures(listOf("IR")))
        val repeating = tonight(listOf(m33), null, focuser = false).areas().let {
            it.copy(targets = it.targets.copy(conditions = listOf(Condition.Loop(2))))
        }
        Targets(file).apply {
            setAdvanced(repeating.toSequence("Tonight"))
            add(m42)
        }

        val again = Targets(file)

        val targets = again.advanced.value?.areas()?.targets
        assertEquals(listOf("M 33", "M 42"), targets?.items?.map { (it as Block).name })
        assertEquals(listOf<Condition>(Condition.Loop(2)), targets?.conditions)
        again.startNew()
        assertNull(again.advanced.value)
    }

    @Test
    fun aSavedSequenceOpensInThePlanOrAsAnAdvancedNight() {
        val targets = Targets()
        targets.open(tonight(listOf(m33), null, focuser = true))
        assertEquals(listOf(m33), targets.targets.value)
        assertNull(targets.advanced.value)

        val advanced = Sequence("Night", Block("Night", listOf(Block("Start"), Block("M 33", listOf(WaitFor(5.0))), Block("End"))))
        targets.open(advanced)
        assertEquals(advanced, targets.advanced.value)

        targets.undo()
        assertNull(targets.advanced.value)
    }
}
