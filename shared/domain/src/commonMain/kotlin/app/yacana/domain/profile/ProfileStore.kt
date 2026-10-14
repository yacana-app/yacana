// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.profile

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * The profiles on this phone, one file each in [directory] (ARCHITECTURE.md).
 *
 * Every save writes a temporary file and renames it over the old one, so a crash
 * while saving leaves either the old profile or the new one, never half of one.
 */
class ProfileStore(private val directory: Path, private val fileSystem: FileSystem = SystemFileSystem) {
    /** Every readable profile, sorted by name. A file that cannot be read is skipped. */
    fun list(): List<Profile> {
        if (!fileSystem.exists(directory)) return emptyList()
        return fileSystem
            .list(directory)
            .filter { it.name.endsWith(EXTENSION) }
            .mapNotNull { path -> readOrNull(path) }
            .sortedBy { it.name.lowercase() }
    }

    fun load(id: String): Profile? = pathOf(id).takeIf { fileSystem.exists(it) }?.let(::read)

    fun save(profile: Profile) {
        fileSystem.createDirectories(directory)
        val target = pathOf(profile.id)
        val temporary = Path(directory, "${profile.id}$EXTENSION.tmp")
        fileSystem.sink(temporary).buffered().use { it.writeString(ProfileFile.encode(profile)) }
        fileSystem.atomicMove(temporary, target)
    }

    fun delete(id: String) {
        fileSystem.delete(pathOf(id), mustExist = false)
        if (activeId == id) activeId = null
    }

    /** The profile in use, remembered between launches; null if none was chosen. */
    var activeId: String?
        get() =
            Path(directory, ACTIVE).takeIf { fileSystem.exists(it) }?.let { path ->
                fileSystem.source(path).buffered().use { it.readString() }.trim().ifEmpty { null }
            }
        set(value) {
            fileSystem.createDirectories(directory)
            val temporary = Path(directory, "$ACTIVE.tmp")
            fileSystem.sink(temporary).buffered().use { it.writeString(value.orEmpty()) }
            fileSystem.atomicMove(temporary, Path(directory, ACTIVE))
        }

    /** Reads a profile, and saves it again at the current version if it was older. */
    private fun read(path: Path): Profile {
        val text = fileSystem.source(path).buffered().use { it.readString() }
        val decoded = ProfileFile.decode(text)
        if (decoded.migrated) save(decoded.profile)
        return decoded.profile
    }

    private fun readOrNull(path: Path): Profile? = try {
        read(path)
    } catch (_: ProfileFormatException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun pathOf(id: String): Path {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) { "Profile IDs are letters, digits and dashes: $id" }
        return Path(directory, "$id$EXTENSION")
    }

    private companion object {
        const val EXTENSION = ".json"
        const val ACTIVE = "active"
    }
}
