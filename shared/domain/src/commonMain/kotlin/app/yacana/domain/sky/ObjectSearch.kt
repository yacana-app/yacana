// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** How a search on the internet ended. */
sealed interface LookUp {
    data class Found(val item: SkyObject) : LookUp

    /** Nobody knows the name as typed, and these have names like it, the brightest first; none is kept until chosen. */
    data class Many(val items: List<SkyObject>) : LookUp

    data object NotFound : LookUp

    data class Failed(val reason: String) : LookUp
}

/**
 * Finding objects: the app's catalogue, read once from [catalogue] in [scope], plus the
 * objects found on the internet with [resolver] before, kept in [saved] so they are there without
 * internet too, in the catalogue's own format.
 */
class ObjectSearch(
    private val catalogue: suspend () -> String,
    private val saved: Path,
    private val resolver: NameResolver?,
    scope: CoroutineScope,
) {
    private val mutableObjects = MutableStateFlow<Catalogue?>(null)
    private var bundled = Catalogue(emptyList())

    /** The catalogue and the objects found before; null while it loads. */
    val objects: StateFlow<Catalogue?> = mutableObjects.asStateFlow()

    init {
        scope.launch {
            withContext(Dispatchers.Default) {
                bundled = Catalogue.parse(catalogue())
                mutableObjects.value = joined(found())
            }
        }
    }

    fun search(query: String): List<SkyObject> = objects.value?.search(query).orEmpty()

    fun find(designation: String): SkyObject? = objects.value?.find(designation)

    /**
     * Asks the internet for [name]; what it finds is kept and joins the catalogue, unless the app has
     * it already by one of its names: then it is that one, never a second (M 31 found as "M31").
     * Without that name, the objects with names like it.
     */
    suspend fun lookUp(name: String): LookUp = when (val result = resolve(name)) {
        is LookUp.Found -> LookUp.Found(choose(result.item))
        LookUp.NotFound -> like(name)
        else -> result
    }

    /** [item], one of a [LookUp.Many], as the app keeps it: the catalogue's if it has it, else kept now. */
    suspend fun choose(item: SkyObject): SkyObject = known(item) ?: item.also { keep(it) }

    private fun known(item: SkyObject): SkyObject? = item.designations.firstNotNullOfOrNull { objects.value?.find(it) }

    @Suppress("TooGenericExceptionCaught") // As the name's own search.
    private suspend fun like(name: String): LookUp = try {
        val items = resolver?.like(name).orEmpty().map { known(it) ?: it }.distinctBy { it.designation }
        if (items.isEmpty()) LookUp.NotFound else LookUp.Many(items)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LookUp.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    @Suppress("TooGenericExceptionCaught") // Without internet, or a server that fails, the user sees why.
    private suspend fun resolve(name: String): LookUp = try {
        val item = resolver?.resolve(name)
        when {
            resolver == null -> LookUp.Failed("")
            item == null -> LookUp.NotFound
            else -> LookUp.Found(item)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LookUp.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    private suspend fun keep(item: SkyObject) = withContext(Dispatchers.Default) {
        val found = (listOf(item) + found()).distinctBy { compact(it.designation) }
        SystemFileSystem.sink(saved).buffered().use { sink -> found.forEach { sink.writeString(Catalogue.line(it) + "\n") } }
        mutableObjects.value = joined(found)
    }

    /** The objects found before but those the catalogue has: 0.2.0 kept some of them a second time. */
    private fun found(): List<SkyObject> = if (SystemFileSystem.exists(saved)) {
        Catalogue.parse(
            SystemFileSystem.source(saved).buffered().use {
                it.readString()
            },
        ).objects.filter { item -> item.designations.none { bundled.find(it) != null } }
    } else {
        emptyList()
    }

    /** The objects found and the catalogue's, each once: the lists show them by their designation. */
    private fun joined(found: List<SkyObject>) = Catalogue((found + bundled.objects).distinctBy { it.designation })
}
