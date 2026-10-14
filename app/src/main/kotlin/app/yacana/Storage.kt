// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/**
 * The bytes the app can write in [folder], as Android's download manager counts them: less than
 * the free space, as Android keeps some for itself. A download larger than this does not start.
 */
fun allocatableBytes(context: Context, folder: File): Long {
    val storage = context.getSystemService(StorageManager::class.java)
    return runCatching { storage.getAllocatableBytes(storage.getUuidForPath(folder)) }.getOrElse { StatFs(folder.path).availableBytes }
}
