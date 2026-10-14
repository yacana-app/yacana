// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Runs ASTAP's command-line solver as a separate process.
 *
 * The binary ships in the APK as `libastap_cli.so`, because Android only lets an app
 * run programs from its native library folder. Cancelling the coroutine kills the process.
 *
 * @param binary `libastap_cli.so` in `applicationInfo.nativeLibraryDir`.
 * @param workDir where ASTAP writes its `.ini` and `.wcs` results.
 */
class AstapCli(private val binary: File, private val workDir: File) {
    /** What to solve. [hint] null with a radius of 180° is a blind solve. */
    data class Request(
        val image: File,
        val database: File,
        val databaseName: String,
        val fieldHeightDeg: Double,
        val hint: Hint?,
        val radiusDeg: Double,
        val downsample: Int = 0,
    )

    data class Hint(val raHours: Double, val decDeg: Double)

    /**
     * Where a pixel up the frame goes on the sky, from north through east: per pixel, the CD
     * matrix's second column moves [eastward] in right ascension and [northward] in declination.
     */
    private fun yAxis(eastward: Double?, northward: Double?): Double? =
        if (eastward == null || northward == null) null else Math.toDegrees(kotlin.math.atan2(eastward, northward)).mod(FULL_TURN)

    /**
     * Whether the frame is mirrored with its first row at the top, as it is written: ASTAP takes
     * that row as its first from the bottom, so a frame as the sky looks has the CD matrix's
     * determinant positive.
     */
    private fun mirrored(values: Map<String, String>): Boolean {
        val cd = listOf("CD1_1", "CD1_2", "CD2_1", "CD2_2").map { values[it]?.toDoubleOrNull() ?: return false }
        val (a, b) = cd.take(2)
        val (c, d) = cd.drop(2)
        return a * d - b * c < 0
    }

    /** One solver run: the solution, if any, and what it cost. */
    data class Run(
        val solved: Boolean,
        val raDeg: Double?,
        val decDeg: Double?,
        val scaleArcsec: Double?,
        val rotationDeg: Double?,
        /** Where the frame's y axis points, from north through east, from the CD matrix. */
        val yAxisDeg: Double?,
        /** Whether the frame, first row at the top, has east to the right of north. */
        val mirrored: Boolean,
        val wallMillis: Long,
        val peakMemoryKb: Long?,
        val exitCode: Int,
        val output: String,
    )

    suspend fun solve(request: Request): Run = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        // ASTAP cuts the output name at its first dot, so the base name has none.
        val base = File(workDir, "solution")
        val ini = File(base.path + ".ini")
        ini.delete()
        File(base.path + ".wcs").delete()

        val start = SystemClock.elapsedRealtime()
        val process = ProcessBuilder(arguments(request, base)).directory(workDir).redirectErrorStream(true).start()
        val pid = pidOf(process)
        val output = StringBuilder()
        var peakKb: Long? = null
        val exitCode =
            coroutineScope {
                // Killing the process closes the stream, so a read error only means the run was cancelled.
                val reader = launch { runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
                val sampler =
                    launch {
                        while (isActive && pid != null) {
                            peakResidentKb(pid)?.let { kb -> peakKb = maxOf(peakKb ?: 0, kb) }
                            delay(MEMORY_SAMPLE_MILLIS)
                        }
                    }
                try {
                    runInterruptible { process.waitFor() }.also { reader.join() }
                } finally {
                    sampler.cancel()
                    // Only does something when the coroutine was cancelled before ASTAP finished.
                    process.destroy()
                }
            }
        val wall = SystemClock.elapsedRealtime() - start
        val values = if (ini.isFile) parseIni(ini.readText()) else emptyMap()
        Run(
            solved = values["PLTSOLVD"]?.startsWith("T") == true,
            raDeg = values["CRVAL1"]?.toDoubleOrNull(),
            decDeg = values["CRVAL2"]?.toDoubleOrNull(),
            scaleArcsec = values["CDELT2"]?.toDoubleOrNull()?.let { it * ARCSEC_PER_DEG },
            rotationDeg = values["CROTA2"]?.toDoubleOrNull(),
            yAxisDeg = yAxis(values["CD1_2"]?.toDoubleOrNull(), values["CD2_2"]?.toDoubleOrNull()),
            mirrored = mirrored(values),
            wallMillis = wall,
            peakMemoryKb = peakKb,
            exitCode = exitCode,
            output = output.toString(),
        )
    }

    private fun arguments(request: Request, base: File): List<String> = buildList {
        add(binary.path)
        addAll(listOf("-f", request.image.path, "-d", request.database.path, "-D", request.databaseName))
        addAll(listOf("-fov", request.fieldHeightDeg.format(), "-r", request.radiusDeg.format()))
        addAll(listOf("-z", request.downsample.toString(), "-o", base.path))
        request.hint?.let { hint ->
            addAll(listOf("-ra", hint.raHours.format(), "-spd", (hint.decDeg + SOUTH_POLE_OFFSET).format()))
        }
    }

    private fun Double.format() = String.format(Locale.ROOT, "%.6f", this)

    private companion object {
        const val MEMORY_SAMPLE_MILLIS = 20L
        const val FULL_TURN = 360.0
        const val ARCSEC_PER_DEG = 3600.0
        const val SOUTH_POLE_OFFSET = 90.0

        fun pidOf(process: Process): Long? = Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toLong()

        /** Peak resident memory of a process, from `VmHWM` in `/proc/<pid>/status`. */
        fun peakResidentKb(pid: Long): Long? = runCatching {
            File("/proc/$pid/status")
                .readLines()
                .firstOrNull { it.startsWith("VmHWM:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLong()
        }.getOrNull()

        fun parseIni(text: String): Map<String, String> = text
            .lines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
            }.toMap()
    }
}
