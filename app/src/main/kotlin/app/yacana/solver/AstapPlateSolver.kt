// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import android.content.Context
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.PlateSolver
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import app.yacana.ports.SolverException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * [PlateSolver] with the `astap_cli` inside the APK. Each star database lives in its
 * own folder under the app's `astap/` folder, such as `astap/d50/` with the `d50_*.1476` files.
 */
class AstapPlateSolver(context: Context) : PlateSolver {
    private val databases = databaseFolder(context)
    private val work = File(context.cacheDir, "solve")
    private val astap = AstapCli(File(context.applicationInfo.nativeLibraryDir, "libastap_cli.so"), work)

    override fun installedDatabases(): Set<String> = databasesIn(databases)

    override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest): Solution? {
        val name = request.database.lowercase(Locale.ROOT)
        val folder = File(databases, name)
        if (request.database !in installedDatabases()) throw SolverException("The ${request.database} star database is not on the phone")
        val image = File(work, "frame.fits")
        withContext(Dispatchers.IO) {
            work.mkdirs()
            frame.save(image.path, headers)
        }
        // Cancelling the solve on timeout kills ASTAP.
        val run =
            withTimeoutOrNull(request.timeout) {
                astap.solve(
                    AstapCli.Request(
                        image = image,
                        database = folder,
                        databaseName = name,
                        fieldHeightDeg = request.fieldHeightDeg,
                        hint = request.hint?.let { AstapCli.Hint(it.raHours, it.decDeg) },
                        radiusDeg = request.radiusDeg,
                        downsample = request.downsample,
                    ),
                )
            } ?: return null
        val ra = run.raDeg
        val dec = run.decDeg
        return if (run.solved && ra != null && dec != null) {
            Solution(SkyPosition(ra / DEGREES_PER_HOUR, dec), run.rotationDeg ?: 0.0, run.scaleArcsec ?: 0.0, run.yAxisDeg, run.mirrored)
        } else {
            null
        }
    }

    private companion object {
        const val DEGREES_PER_HOUR = 15.0
    }
}
