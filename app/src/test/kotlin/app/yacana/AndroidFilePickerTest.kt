// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Files through the system's file picker (SAF): what each kind of pick asks Android for,
 * and what the app does with the place the user chose, or with none when the user goes back.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidFilePickerTest {
    private val screen = Robolectric.buildActivity(ComponentActivity::class.java).create()
    private val activity = screen.get()

    // Made as the activity is created, as the app does, before it starts.
    private val picker = AndroidFilePicker(activity).also { screen.start().resume() }
    private val folder: File = Files.createTempDirectory("yacana-picker").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun aProfileIsSavedWhereTheUserChooses() {
        val file = File(folder, "Seestar.json")

        picker.save("Seestar.json", "{\"kind\":\"profile\"}")
        val asked = pick(Uri.fromFile(file))

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.action)
        assertEquals("Seestar.json", asked.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("{\"kind\":\"profile\"}", file.readText())
    }

    @Test
    fun aProfileIsReadFromTheFileTheUserOpens() {
        val file = File(folder, "Seestar.json").apply { writeText("{\"kind\":\"profile\"}") }
        var read: String? = null

        picker.open { read = it }
        val asked = pick(Uri.fromFile(file))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.action)
        assertEquals("{\"kind\":\"profile\"}", read)
    }

    @Test
    fun goingBackPicksNothing() {
        var read: String? = null

        picker.open { read = it }
        pick(null)

        assertNull(read)
    }

    @Test
    fun aZipIsWrittenWhereTheUserChooses() {
        val file = File(folder, "log.zip")

        picker.saveZip("log.zip") { it.write(byteArrayOf(80, 75)) }
        pick(Uri.fromFile(file))

        assertEquals(listOf<Byte>(80, 75), file.readBytes().toList())
    }

    @Test
    fun aZipAFileAndAFolderArePickedByTheirPlace() {
        val picked = mutableListOf<String>()
        val zip = Uri.parse("content://downloads/cache.zip")
        val file = Uri.parse("content://downloads/d50_star_database.zip")
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAstro")

        picker.newZip("cache.zip") { picked += it }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, pick(zip).action)
        picker.openFile { picked += it }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick(file).action)
        picker.openFolder { picked += it }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, pick(tree).action)

        assertEquals(listOf(zip, file, tree).map { it.toString() }, picked)
    }

    /** What the picker asked Android for, answered with [uri] as the user's choice, or with going back. */
    private fun pick(uri: Uri?): Intent {
        val asked = shadowOf(activity).nextStartedActivityForResult
        val result = uri?.let { Intent().setData(it) }
        shadowOf(activity).receiveResult(asked.intent, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        return asked.intent
    }
}
