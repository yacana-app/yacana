// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.imaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.yacana.domain.imaging.FrameStore
import app.yacana.ports.EngineException
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.LiveStack
import app.yacana.ports.MasterFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Frames on the phone: in the folder the user picked with the system file picker, which
 * can be on internal storage, an SD card or a USB drive, or else in the app's folder under
 * `Android/data/app.yacana/files/Yacana`, which a PC also sees over USB.
 *
 * Either way a frame is written under a temporary `.part` name and renamed when complete, so a
 * crash never leaves half a frame under its final name.
 */
@Suppress("TooManyFunctions") // What a frame store does, and a small helper for each.
class AndroidFrameStore(private val context: Context) : FrameStore {
    private val prefs = context.getSharedPreferences("frames", Context.MODE_PRIVATE)
    private val folders = mutableMapOf<String, DocumentFile>()
    private val mutableLocation = MutableStateFlow(describe(tree()))

    override val location: StateFlow<String> = mutableLocation.asStateFlow()

    /** Uses the folder the user picked from now on, keeping the right to write there after restarts. */
    fun choose(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit { putString(KEY_TREE, tree.toString()) }
        synchronized(folders) { folders.clear() }
        mutableLocation.value = describe(tree)
    }

    override suspend fun save(frame: Frame, relativePath: String, headers: List<HeaderCard>): String = withContext(Dispatchers.IO) {
        write("$relativePath.$EXTENSION", replace = false, { frame.save(it, headers) }, { frame.writeTo(it, headers) })
    }

    override suspend fun saveStack(stack: LiveStack, relativePath: String, headers: List<HeaderCard>, balanced: Boolean): String =
        withContext(Dispatchers.IO) {
            write("$relativePath.$JPEG", replace = true, { stack.saveJpeg(it, balanced) }, { stack.writeJpegTo(it, balanced) })
            write("$relativePath.$EXTENSION", replace = true, { stack.save(it, headers) }, { stack.writeTo(it, headers) })
        }

    override suspend fun saveMaster(master: MasterFrame, relativePath: String, headers: List<HeaderCard>): String =
        withContext(Dispatchers.IO) {
            write("$relativePath.$EXTENSION", replace = true, { master.saveFits(it, headers) }, { master.writeFitsTo(it, headers) })
        }

    override suspend fun saveJpeg(relativePath: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        write("$relativePath.$JPEG", replace = true, { path ->
            // As the engine writes, through `.part`, so that no half file is left under the name.
            val part = File("$path.part")
            part.writeBytes(jpeg)
            check(part.renameTo(File(path)) || (File(path).delete() && part.renameTo(File(path)))) { "Cannot rename $path.part" }
        }) { descriptor ->
            ParcelFileDescriptor.adoptFd(descriptor).use { FileOutputStream(it.fileDescriptor).use { out -> out.write(jpeg) } }
        }
    }

    override suspend fun readMaster(relativePath: String, read: (descriptor: Int) -> MasterFrame?): MasterFrame? =
        withContext(Dispatchers.IO) {
            val name = "$relativePath.$EXTENSION"
            val tree = tree()
            val descriptor =
                if (tree == null) {
                    File(appFolder(), name).takeIf { it.isFile }?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                } else {
                    find(tree, name.split('/'))?.let { context.contentResolver.openFileDescriptor(it.uri, "r") }
                }
            descriptor?.detachFd()?.let(read)
        }

    /** The file at [path] under [tree] if it is there, without creating any folder on the way. */
    private fun find(tree: Uri, path: List<String>): DocumentFile? =
        path.fold(DocumentFile.fromTreeUri(context, tree)) { folder, name -> folder?.findFile(name) }?.takeIf { it.isFile }

    /**
     * Writes the file at [relativePath] under the folder, over the one there if [replace]: by its
     * path in the app's folder, which the engine writes through `.part` itself, or into a descriptor
     * in the picked one. Returns the path to show.
     */
    private fun write(relativePath: String, replace: Boolean, toPath: (String) -> Unit, toDescriptor: (Int) -> Unit): String {
        val tree = tree()
        if (tree == null) {
            val file = File(appFolder(), relativePath)
            file.parentFile?.mkdirs()
            toPath(file.path)
            return file.path
        }
        val parts = relativePath.split('/')
        var folder = folder(tree, parts.dropLast(1))
        val name = parts.last()
        // A folder remembered may be gone, as when moved or deleted from the phone: found again, it is tried once more.
        val part =
            folder.createFile(OCTET_STREAM, "$name.part")
                ?: run {
                    synchronized(folders) { folders.clear() }
                    folder = folder(tree, parts.dropLast(1))
                    required(folder.createFile(OCTET_STREAM, "$name.part")) { "Cannot create $name in ${describe(tree)}" }
                }
        val descriptor = required(context.contentResolver.openFileDescriptor(part.uri, "w")) { "Cannot open $name for writing" }
        toDescriptor(descriptor.detachFd())
        // Only a stack is saved over: looking for a frame's name would list a whole night's folder.
        if (replace) folder.findFile(name)?.delete()
        required(DocumentsContract.renameDocument(context.contentResolver, part.uri, name)) { "Cannot rename $name.part to $name" }
        return "${describe(tree)}/$relativePath"
    }

    /** The folder for [path] under [tree], created as needed; found folders are remembered. */
    private fun folder(tree: Uri, path: List<String>): DocumentFile = synchronized(folders) {
        var folder = DocumentFile.fromTreeUri(context, tree) ?: throw EngineException("The picked folder is not available")
        var key = ""
        for (name in path) {
            key += "/$name"
            folder = folders.getOrPut(key) {
                folder.findFile(name)?.takeIf { it.isDirectory } ?: folder.createDirectory(name)
                    ?: throw EngineException("Cannot create the folder $name")
            }
        }
        folder
    }

    /** [value], or an [EngineException] saying [why] when the system gave nothing. */
    private inline fun <T : Any> required(value: T?, why: () -> String): T = value ?: throw EngineException(why())

    private fun tree(): Uri? = prefs.getString(KEY_TREE, null)?.toUri()?.takeIf { uri ->
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    private fun appFolder() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Yacana")

    /**
     * The folder as a path the user recognises, such as `/storage/emulated/0/Astro`, instead of the
     * picker's ID for it, such as `primary:Astro`. An SD card or USB drive is `/storage/<its ID>/…`.
     */
    private fun describe(tree: Uri?): String {
        if (tree == null) return appFolder().path
        val id = DocumentsContract.getTreeDocumentId(tree)
        val volume = id.substringBefore(':')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return listOf(root, id.substringAfter(':', "")).filter { it.isNotEmpty() }.joinToString("/")
    }

    private companion object {
        const val KEY_TREE = "tree"
        const val EXTENSION = "fits"
        const val JPEG = "jpg"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
