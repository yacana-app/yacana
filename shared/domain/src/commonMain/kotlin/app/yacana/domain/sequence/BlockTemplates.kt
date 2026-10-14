// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.builtins.ListSerializer

/**
 * The advanced editor's templates: blocks saved to add again, as N.I.N.A.'s, each by its
 * name in place of one of that name; kept in [file], only while the app is open without one.
 */
class BlockTemplates(private val file: Path? = null) {
    private val mutableSaved = MutableStateFlow(read())

    val saved: StateFlow<List<Block>> = mutableSaved.asStateFlow()

    fun save(block: Block) = change { list -> list.filter { it.name != block.name } + block }

    fun delete(name: String) = change { list -> list.filter { it.name != name } }

    private fun change(how: (List<Block>) -> List<Block>) {
        mutableSaved.value = how(saved.value)
        file?.let { path ->
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { it.writeString(Sequence.JSON.encodeToString(LIST, saved.value)) }
        }
    }

    private fun read(): List<Block> = file
        ?.takeIf { SystemFileSystem.exists(it) }
        ?.let { path ->
            runCatching {
                Sequence.JSON.decodeFromString(LIST, SystemFileSystem.source(path).buffered().use { it.readString() })
            }.getOrNull()
        }
        .orEmpty()

    private companion object {
        val LIST = ListSerializer(Block.serializer())
    }
}
