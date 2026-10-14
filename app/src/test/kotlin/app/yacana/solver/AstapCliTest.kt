// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ASTAP's command line as the app runs it, with a stand-in for ASTAP: a script that prints
 * its arguments and writes the `.ini` ASTAP would, so what is sent and what is read back is checked
 * without the solver itself, which only runs on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class AstapCliTest {
    private val work: File = Files.createTempDirectory("yacana-astap-cli").toFile()

    @After
    fun cleanUp() {
        work.deleteRecursively()
    }

    @Test
    fun aSolutionIsReadFromTheIniAstapWrites() {
        val astap = AstapCli(fakeAstap(solution(mirrored = true)), File(work, "solve"))

        val run = runBlocking { astap.solve(request(hint = AstapCli.Hint(0.7122, 41.269))) }

        assertTrue(run.solved)
        assertEquals(10.684, run.raDeg)
        assertEquals(41.269, run.decDeg)
        assertEquals(SCALE * 3600, assertNotNull(run.scaleArcsec), 1e-9)
        assertEquals(ROTATION, run.rotationDeg)
        assertEquals(ROTATION, assertNotNull(run.yAxisDeg), 1e-9)
        assertTrue(run.mirrored)
        assertEquals(0, run.exitCode)
    }

    @Test
    fun aFrameAsTheSkyLooksIsNotMirrored() {
        val astap = AstapCli(fakeAstap(solution(mirrored = false)), File(work, "solve"))

        val run = runBlocking { astap.solve(request(hint = null)) }

        assertTrue(run.solved)
        assertFalse(run.mirrored)
    }

    @Test
    fun theHintGoesAsRightAscensionAndSouthPoleDistance() {
        val astap = AstapCli(fakeAstap(solution(mirrored = true)), File(work, "solve"))

        val run = runBlocking { astap.solve(request(hint = AstapCli.Hint(0.7122, 41.269))) }

        assertTrue("-D d50 -fov 1.300000 -r 5.000000 -z 2" in run.output, run.output)
        assertTrue("-ra 0.712200 -spd 131.269000" in run.output, run.output)
    }

    @Test
    fun aBlindSolveSendsNoHint() {
        val astap = AstapCli(fakeAstap(solution(mirrored = true)), File(work, "solve"))

        val run = runBlocking { astap.solve(request(hint = null, radius = 180.0)) }

        assertTrue("-r 180.000000" in run.output, run.output)
        assertFalse("-ra" in run.output, run.output)
    }

    @Test
    fun withoutASolutionTheRunSaysSoAndKeepsTheExitCode() {
        val astap = AstapCli(fakeAstap(listOf("PLTSOLVD=F"), exit = 1), File(work, "solve"))

        val run = runBlocking { astap.solve(request(hint = null)) }

        assertFalse(run.solved)
        assertNull(run.raDeg)
        assertNull(run.yAxisDeg)
        assertFalse(run.mirrored)
        assertEquals(1, run.exitCode)
    }

    @Test
    fun withoutAnIniNothingIsSolved() {
        val astap = AstapCli(fakeAstap(null, exit = 2), File(work, "solve"))

        val run = runBlocking { astap.solve(request(hint = null)) }

        assertFalse(run.solved)
        assertEquals(2, run.exitCode)
    }

    private fun request(hint: AstapCli.Hint?, radius: Double = 5.0) = AstapCli.Request(
        image = File(work, "frame.fits"),
        database = File(work, "d50"),
        databaseName = "d50",
        fieldHeightDeg = 1.3,
        hint = hint,
        radiusDeg = radius,
        downsample = 2,
    )

    /** The `.ini` of a solution at M 31, its frame [mirrored] as the Seestar's are or not. */
    private fun solution(mirrored: Boolean): List<String> {
        val angle = Math.toRadians(ROTATION)
        val sign = if (mirrored) -1 else 1
        return listOf(
            "PLTSOLVD=T",
            "CRVAL1=10.684",
            "CRVAL2=41.269",
            "CDELT1=${-SCALE}",
            "CDELT2=$SCALE",
            "CROTA1=$ROTATION",
            "CROTA2=$ROTATION",
            "CD1_1=${sign * SCALE * cos(angle)}",
            "CD1_2=${SCALE * sin(angle)}",
            "CD2_1=${-sign * SCALE * sin(angle)}",
            "CD2_2=${SCALE * cos(angle)}",
        )
    }

    /** A script in ASTAP's place that prints its arguments, writes [ini] where ASTAP would, and ends with [exit]. */
    private fun fakeAstap(ini: List<String>?, exit: Int = 0): File {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val script = File(work, if (windows) "astap.cmd" else "astap.sh")
        script.writeText(
            buildString {
                if (windows) {
                    appendLine("@echo off")
                    appendLine("echo %*")
                    ini?.forEach { appendLine("echo $it>>solution.ini") }
                    appendLine("exit /b $exit")
                } else {
                    appendLine("#!/bin/sh")
                    appendLine("echo \"\$@\"")
                    ini?.forEach { appendLine("echo '$it' >> solution.ini") }
                    appendLine("exit $exit")
                }
            },
        )
        script.setExecutable(true)
        return script
    }

    private companion object {
        const val SCALE = 6.24e-4
        const val ROTATION = 35.2
    }
}
