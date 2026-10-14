// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.os.Build
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileFile
import app.yacana.ports.EngineBuildInfo
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The exportable session log: a ZIP with the log files, the profile in use and
 * the app's version, and nothing else. The user decides whether to share it; the app sends nothing.
 */
object LogArchive {
    fun write(out: OutputStream, logs: List<File>, profile: Profile?, versionName: String, engine: EngineBuildInfo) {
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("version.txt", version(versionName, engine).encodeToByteArray())
            profile?.let { entry("profile.json", ProfileFile.encode(it).encodeToByteArray()) }
            logs.forEach { entry("logs/${it.name}", it.readBytes()) }
        }
    }

    private fun version(versionName: String, engine: EngineBuildInfo) =
        """
        Yacana $versionName
        Pixel engine ${engine.version} (${engine.arch})
        Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        Device ${Build.MANUFACTURER} ${Build.MODEL}
        """.trimIndent() + "\n"
}
