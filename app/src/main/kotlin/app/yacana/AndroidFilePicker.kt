// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.yacana.ui.FilePicker
import java.io.OutputStream

/**
 * Text files through the system file picker (SAF): the user chooses where a profile
 * goes and which one to open. Create it while the activity is created.
 */
class AndroidFilePicker(private val activity: ComponentActivity) : FilePicker {
    private var pendingText: String? = null
    private var pendingOpen: ((String) -> Unit)? = null

    private val create =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val text = pendingText
            pendingText = null
            if (uri != null && text != null) {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
            }
        }

    private val open =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val onText = pendingOpen
            pendingOpen = null
            if (uri != null && onText != null) {
                activity.contentResolver.openInputStream(uri)?.use { onText(it.readBytes().decodeToString()) }
            }
        }

    private var pendingFolder: ((Uri) -> Unit)? = null

    private val tree =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val onFolder = pendingFolder
            pendingFolder = null
            if (uri != null && onFolder != null) onFolder(uri)
        }

    /** Lets the user pick a folder, such as one for frames on an SD card. */
    fun pickFolder(onFolder: (Uri) -> Unit) {
        pendingFolder = onFolder
        tree.launch(null)
    }

    private var pendingZip: ((Uri) -> Unit)? = null

    private val createZip =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val then = pendingZip
            pendingZip = null
            if (uri != null && then != null) then(uri)
        }

    /** Lets the user choose where a ZIP goes, then [write] fills it. */
    fun saveZip(suggestedName: String, write: (OutputStream) -> Unit) {
        pendingZip = { uri -> activity.contentResolver.openOutputStream(uri, "wt")?.use(write) }
        createZip.launch(suggestedName)
    }

    override fun newZip(suggestedName: String, onPicked: (String) -> Unit) {
        pendingZip = { onPicked(it.toString()) }
        createZip.launch(suggestedName)
    }

    override fun save(suggestedName: String, text: String) {
        pendingText = text
        create.launch(suggestedName)
    }

    private var pendingFile: ((String) -> Unit)? = null

    private val openAny =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val onPicked = pendingFile
            pendingFile = null
            if (uri != null && onPicked != null) onPicked(uri.toString())
        }

    override fun openFile(onPicked: (String) -> Unit) {
        pendingFile = onPicked
        openAny.launch(arrayOf("*/*"))
    }

    override fun openFolder(onPicked: (String) -> Unit) = pickFolder { onPicked(it.toString()) }

    override fun open(onText: (String) -> Unit) {
        pendingOpen = onText
        open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }
}
