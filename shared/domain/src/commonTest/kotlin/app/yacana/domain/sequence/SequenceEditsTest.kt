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

class SequenceEditsTest {
    private val m31 = SkyTarget("M 31", 0.712, 41.27)
    private val lights = Exposures(count = 10, exposureSeconds = 60.0, filter = "IR")
    private val root =
        Block(
            "Night",
            listOf(
                Block(START, listOf(Unpark, Note("a"))),
                Block(TARGETS, listOf(Block("M 31", listOf(GoToTarget(), lights), m31), Block("Box"))),
                Block(END, listOf(Park)),
            ),
        )
    private val file = Path(SystemTemporaryDirectory, "yacana-templates-${ProfilePresets.newId()}.json")

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    }

    @Test
    fun itemsAreFoundChangedTakenAwayAndCopiedByTheirPath() {
        assertEquals(lights, root.itemAt(listOf(1, 0, 1)))
        assertEquals("Box", root.blockAt(listOf(1, 1))?.name)
        assertEquals(listOf(GoToTarget()), root.removeAt(listOf(1, 0, 1)).blockAt(listOf(1, 0))?.items)
        assertEquals(listOf(Unpark, Unpark, Note("a")), root.insertAfter(listOf(0, 0), Unpark).blockAt(listOf(0))?.items)
        assertEquals(listOf(Note("a"), Unpark), root.moveTo(listOf(0, 0), 1).blockAt(listOf(0))?.items)
        assertEquals("Renamed", root.changeAt(listOf(1, 1)) { it.copy(name = "Renamed") }.blockAt(listOf(1, 1))?.name)
    }

    @Test
    fun anItemMovedIntoABlockGoesAtItsEndAndLeavesWhereItWas() {
        // Into a block after it, and into the block around it.
        val boxed = root.moveInto(listOf(1, 0, 1), listOf(1, 1))
        val out = root.moveInto(listOf(1, 0, 1), listOf(1))

        assertEquals(listOf<SequenceItem>(lights), boxed.blockAt(listOf(1, 1))?.items)
        assertEquals(listOf(GoToTarget()), boxed.blockAt(listOf(1, 0))?.items)
        assertEquals(lights, out.itemAt(listOf(1, 2)))
        assertEquals(listOf(GoToTarget()), out.blockAt(listOf(1, 0))?.items)
    }

    @Test
    fun aRunsPathIsFoundInTheAreasAsTheEditorNumbersThem() {
        val night = tonight(listOf(Target("M 33", 1.56, 30.7, 0.0, emptyList(), listOf(lights))), null, focuser = false)

        assertEquals(listOf(0, 0), night.areaPath(listOf(0, 0)))
        assertEquals(listOf(1, 0, 1), night.areaPath(listOf(1, 1)))
        assertEquals(listOf(2, 0), night.areaPath(listOf(2, 0)))
        val own = night.areas().let { it.copy(targets = it.targets.copy(conditions = listOf(Condition.Loop(2)))) }.toSequence("Night")
        assertEquals(listOf(1, 0, 1), own.areaPath(listOf(1, 0, 1)))
        assertEquals(night.areas(), night.areas().asBlock("Night").asAreas())
    }

    @Test
    fun aTemplateIsKeptByItsNameInPlaceOfOneOfThatName() {
        BlockTemplates(file).apply {
            save(Block("M 31", listOf(GoToTarget()), m31))
            save(Block("Filters", listOf(SwitchFilter("LP"))))
            save(Block("M 31", listOf(GoToTarget(), lights), m31))
        }

        val again = BlockTemplates(file)

        assertEquals(listOf("Filters", "M 31"), again.saved.value.map { it.name })
        assertEquals(listOf(GoToTarget(), lights), again.saved.value.last().items)
        again.delete("Filters")
        assertEquals(listOf("M 31"), BlockTemplates(file).saved.value.map { it.name })
    }
}
