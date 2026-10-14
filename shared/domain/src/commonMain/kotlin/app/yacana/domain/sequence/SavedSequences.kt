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

/**
 * "My sequences": the sequences saved to open again, each as its JSON in [folder], named
 * after its targets; only while the app is open without one.
 */
class SavedSequences(private val folder: Path? = null) {
    private val mutableSaved = MutableStateFlow(read())

    /** The saved sequences, by name. */
    val saved: StateFlow<List<Sequence>> = mutableSaved.asStateFlow()

    /** Keeps [sequence], named after [targets], in place of one of that name; returns the name. */
    fun save(sequence: Sequence, targets: List<Target>): String = keep(sequence, targets.map { it.name })

    /** Keeps [sequence], named after the targets of its targets' area, as the advanced editor saves it; returns the name. */
    fun save(sequence: Sequence): String = keep(sequence, sequence.areas().targets.items.filterIsInstance<Block>().map { it.name })

    private fun keep(sequence: Sequence, names: List<String>): String {
        val name = names.joinToString(" · ").ifEmpty { sequence.name }
        val named = sequence.copy(name = name)
        mutableSaved.value = (saved.value.filter { it.name != name } + named).sortedBy { it.name }
        folder?.let { dir ->
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(file(dir, name)).buffered().use { it.writeString(named.toJson()) }
        }
        return name
    }

    fun delete(name: String) {
        mutableSaved.value = saved.value.filter { it.name != name }
        folder?.let { dir -> file(dir, name).takeIf { SystemFileSystem.exists(it) }?.let { SystemFileSystem.delete(it) } }
    }

    private fun read(): List<Sequence> {
        val dir = folder?.takeIf { SystemFileSystem.exists(it) } ?: return emptyList()
        return SystemFileSystem.list(dir).filter { it.name.endsWith(JSON) }.mapNotNull { path ->
            runCatching { Sequence.fromJson(SystemFileSystem.source(path).buffered().use { it.readString() }) }.getOrNull()
        }.sortedBy { it.name }
    }

    /** The file of a name, with only what a file name can hold. */
    private fun file(dir: Path, name: String) = Path(dir, name.replace(UNSAFE, "_") + JSON)

    private companion object {
        const val JSON = ".json"
        val UNSAFE = Regex("[^\\p{L}\\p{N} ._-]")
    }
}
