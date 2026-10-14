// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The latest values read from a device: [value] and when it was read. If the latest
 * read failed, [error] says why and [value] keeps the last good values, so nothing changes
 * without saying so.
 */
data class Live<T>(val value: T? = null, val updatedAt: Instant? = null, val error: String? = null)

/**
 * Reads a device every [interval] and publishes the result. The app wants it at least every
 * 2 s; 1 s leaves room for a slow answer. [refresh] reads again at once, after a command.
 */
internal class Poller<T>(
    scope: CoroutineScope,
    private val clock: Clock,
    private val interval: Duration = 1.seconds,
    private val read: suspend () -> T,
) {
    private val live = MutableStateFlow(Live<T>())
    private val kick = Channel<Unit>(Channel.CONFLATED)

    val state: StateFlow<Live<T>> = live.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                live.value = next()
                withTimeoutOrNull(interval) { kick.receive() }
            }
        }
    }

    fun refresh() {
        kick.trySend(Unit)
    }

    @Suppress("TooGenericExceptionCaught") // A poll must survive any device fault and report it.
    private suspend fun next(): Live<T> = try {
        Live(read(), clock.now())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        live.value.copy(error = e.message ?: e::class.simpleName)
    }
}
