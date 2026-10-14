// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import app.yacana.domain.solver.StarDatabaseException
import org.junit.After
import org.junit.Ignore
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseArchivesTest {
    private val root = Files.createTempDirectory("yacana-astap").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aZipIsInstalledInAFolderNamedAsItsFiles() {
        val zip =
            zip(
                "d05_0101.1476" to byteArrayOf(1, 2, 3),
                "d05_0201.1476" to byteArrayOf(4),
                "acknowledgement of databases.txt" to byteArrayOf(5),
            )
        var heard: String? = null

        val name = DatabaseArchives.install(zip.inputStream(), root) { heard = it }

        assertEquals("D05", name)
        assertEquals("D05", heard)
        assertEquals(listOf("d05_0101.1476", "d05_0201.1476"), root.resolve("d05").list()!!.sorted())
        assertEquals(listOf(1, 2, 3), root.resolve("d05/d05_0101.1476").readBytes().map { it.toInt() })
        assertEquals(setOf("D05"), databasesIn(root))
    }

    @Test
    fun aDebianPackageIsInstalledFromItsXzTar() {
        val big = ByteArray(1500) { it.toByte() }
        val deb = deb(tar("./opt/astap/d80_0101.1476" to big, "./opt/astap/d80_0201.1476" to byteArrayOf(7)))

        val name = DatabaseArchives.install(deb.inputStream(), root)

        assertEquals("D80", name)
        assertTrue(root.resolve("d80/d80_0101.1476").readBytes().contentEquals(big))
        assertEquals(listOf(7), root.resolve("d80/d80_0201.1476").readBytes().map { it.toInt() })
    }

    @Test
    fun aFileWithoutADatabaseLeavesNothing() {
        val zip = zip("notes.txt" to byteArrayOf(1))

        val error = assertFailsWith<StarDatabaseException> { DatabaseArchives.install(zip.inputStream(), root) }

        assertTrue(error.notADatabase)
        assertEquals(emptyList(), root.list()!!.toList())
    }

    /**
     * ASTAP's own D80 package, which comes only as a Debian package (1.2 GB): run by hand with
     * `D80_DEB` set to where it was downloaded.
     */
    @Ignore("Needs the 1.2 GB D80 package")
    @Test
    fun theRealD80PackageInstalls() {
        val name = File(System.getenv("D80_DEB")).inputStream().use { DatabaseArchives.install(it, root) }

        val files = root.resolve("d80").listFiles()!!
        println("D80: ${files.size} files, ${files.sumOf { it.length() }} bytes")
        assertEquals("D80", name)
        assertEquals(1476, files.size)
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    /** A tar as GNU tar writes it: a header block for each file, its contents padded, two empty blocks. */
    private fun tar(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        files.forEach { (name, data) ->
            val header = ByteArray(BLOCK)
            name.toByteArray().copyInto(header)
            "0000644".toByteArray().copyInto(header, 100)
            data.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            "ustar  ".toByteArray().copyInto(header, 257)
            out.write(header)
            out.write(data)
            out.write(ByteArray((BLOCK - data.size % BLOCK) % BLOCK))
        }
        out.write(ByteArray(2 * BLOCK))
    }.toByteArray()

    /** A Debian package: `ar` with the version, the control files and the data as tar in XZ. */
    private fun deb(tar: ByteArray): ByteArray {
        val xz = ByteArrayOutputStream().also { bytes -> XZOutputStream(bytes, LZMA2Options()).use { it.write(tar) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray())
        listOf(
            "debian-binary" to "2.0\n".toByteArray(),
            "control.tar.xz" to byteArrayOf(1, 2, 3),
            "data.tar.xz" to xz,
        ).forEach { (name, data) ->
            val header =
                name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "100644".padEnd(8) + data.size.toString().padEnd(10) +
                    "`\n"
            out.write(header.toByteArray())
            out.write(data)
            if (data.size % 2 == 1) out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private companion object {
        const val BLOCK = 512
    }
}
