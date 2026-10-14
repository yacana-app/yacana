// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import app.yacana.domain.solver.StarDatabaseException
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * ASTAP's star databases as their author publishes them: a ZIP, or a Debian package whose data
 * is a tar in XZ (D80 comes only so). Each holds the database's files, such as `d50_0101.1476`.
 */
internal object DatabaseArchives {
    /** A database file: the database's name, then the tile; `g05_0101.290` or `w08_0101.001`. */
    private val DATABASE_FILE = Regex("([a-z]\\d\\d)_\\d{4}\\.\\d+")

    /**
     * Installs the database in [input] as a folder in [root], named as its files (`d50`), and
     * returns its name in capitals. The files go to a hidden folder first, so a database is in
     * its folder only once all its files are; [onName] hears the name as soon as it is known.
     */
    fun install(input: InputStream, root: File, onName: (String) -> Unit = {}): String {
        val work = File(root, WORK + System.nanoTime()).apply {
            deleteRecursively()
            mkdirs()
        }
        var name: String? = null
        try {
            entries(BufferedInputStream(input, BUFFER)) { path, data ->
                val file = path.substringAfterLast('/').lowercase(Locale.ROOT)
                val prefix = DATABASE_FILE.matchEntire(file)?.groupValues?.get(1) ?: return@entries
                if (name == null) {
                    name = prefix
                    onName(prefix.uppercase(Locale.ROOT))
                }
                if (prefix == name) File(work, file).outputStream().use { data.copyTo(it, BUFFER) }
            }
            val found = name ?: throw StarDatabaseException(notADatabase = true, "No ASTAP star database in this file")
            val target = File(root, found)
            target.deleteRecursively()
            if (!work.renameTo(target)) throw IOException("Could not move the database to $target")
            return found.uppercase(Locale.ROOT)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Each file in a ZIP or a Debian package, with its path and its contents. */
    private fun entries(input: BufferedInputStream, onFile: (String, InputStream) -> Unit) {
        input.mark(MAGIC)
        val magic = input.readUpTo(MAGIC)
        input.reset()
        when {
            magic.startsWith(ZIP) -> zip(input, onFile)
            magic.startsWith(AR) -> deb(input, onFile)
            else -> throw StarDatabaseException(notADatabase = true, "Neither a ZIP nor a Debian package")
        }
    }

    private fun zip(input: InputStream, onFile: (String, InputStream) -> Unit) {
        val zip = ZipInputStream(input)
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { onFile(it.name, zip) }
    }

    /** A Debian package is an `ar` archive; its `data.tar.xz` member holds the files. */
    private fun deb(input: InputStream, onFile: (String, InputStream) -> Unit) {
        input.skipFully(AR.size.toLong())
        while (true) {
            val header = input.readUpTo(AR_HEADER)
            if (header.size < AR_HEADER) throw StarDatabaseException(notADatabase = true, "No data.tar.xz in this package")
            val member = String(header, 0, AR_NAME, Charsets.US_ASCII).trim().removeSuffix("/")
            val size = String(header, AR_SIZE, AR_SIZE_LENGTH, Charsets.US_ASCII).trim().toLong()
            if (member == "data.tar.xz") return tar(XZInputStream(Bounded(input, size)), onFile)
            input.skipFully(size + size % 2)
        }
    }

    /** A tar archive: a 512-byte header for each file, then its contents padded to 512 bytes. */
    private fun tar(input: InputStream, onFile: (String, InputStream) -> Unit) {
        var longName: String? = null
        while (true) {
            val header = input.readUpTo(TAR_BLOCK)
            if (header.size < TAR_BLOCK || header.all { it == 0.toByte() }) return
            val size = String(header, TAR_SIZE, TAR_SIZE_LENGTH, Charsets.US_ASCII).trim(' ', '\u0000').toLong(OCTAL)
            val data = Bounded(input, size)
            when (header[TAR_TYPE].toInt().toChar()) {
                // GNU's long name: the next file's path is in this one's contents.
                'L' -> longName = data.readBytes().decodeToString().trimEnd('\u0000')

                '0', '\u0000' -> {
                    onFile(longName ?: path(header), data)
                    longName = null
                }

                else -> longName = null
            }
            data.skipFully(data.left)
            input.skipFully((TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK)
        }
    }

    private fun path(header: ByteArray): String {
        fun text(offset: Int, length: Int) = String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000')
        val prefix = text(TAR_PREFIX, TAR_PREFIX_LENGTH)
        val name = text(0, TAR_NAME_LENGTH)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun ByteArray.startsWith(start: ByteArray) = size >= start.size && start.indices.all { this[it] == start[it] }

    /** Up to [count] bytes, fewer only at the end of the stream; Android 10 has no `readNBytes`. */
    private fun InputStream.readUpTo(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(bytes, read, count - read)
            if (n < 0) break
            read += n
        }
        return if (read == count) bytes else bytes.copyOf(read)
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            when {
                skipped > 0 -> left -= skipped
                read() < 0 -> throw EOFException("The archive ends too soon")
                else -> left--
            }
        }
    }

    /** The next [left] bytes of a stream; closing it leaves the stream open. */
    private class Bounded(input: InputStream, var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            return super.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }

        override fun skip(n: Long): Long = super.skip(minOf(n, left)).also { left -= it }

        override fun available(): Int = minOf(super.available().toLong(), left).toInt()

        override fun close() = Unit
    }

    /** The start of the hidden folders that installs fill. */
    const val WORK = ".installing-"

    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val AR = "!<arch>\n".toByteArray(Charsets.US_ASCII)
    private const val MAGIC = 8
    private const val BUFFER = 1 shl 16
    private const val AR_HEADER = 60
    private const val AR_NAME = 16
    private const val AR_SIZE = 48
    private const val AR_SIZE_LENGTH = 10
    private const val TAR_BLOCK = 512
    private const val TAR_NAME_LENGTH = 100
    private const val TAR_SIZE = 124
    private const val TAR_SIZE_LENGTH = 12
    private const val TAR_TYPE = 156
    private const val TAR_PREFIX = 345
    private const val TAR_PREFIX_LENGTH = 155
    private const val OCTAL = 8
}
