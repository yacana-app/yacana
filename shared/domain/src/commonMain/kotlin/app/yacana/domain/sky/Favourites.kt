// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * The objects marked with the star of their page, by designation, kept one per line in [file];
 * only while the app is open without one.
 */
class Favourites(private val file: Path? = null) {
    private val mutableMarked = MutableStateFlow(read())

    val marked: StateFlow<Set<String>> = mutableMarked.asStateFlow()

    fun toggle(designation: String) {
        mutableMarked.update { if (designation in it) it - designation else it + designation }
        file?.let { path -> SystemFileSystem.sink(path).buffered().use { sink -> marked.value.forEach { sink.writeString("$it\n") } } }
    }

    private fun read(): Set<String> {
        val path = file?.takeIf { SystemFileSystem.exists(it) } ?: return emptySet()
        return SystemFileSystem.source(path).buffered().use { it.readString() }.lines().filter { it.isNotBlank() }.toSet()
    }
}
