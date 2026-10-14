// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The run going on, kept in [file] after each step and frame, so that when the app closes
 * or the phone restarts it comes back where it was, the frame in progress lost; gone once the run
 * ends. A run kept more than [RESTORE_WITHIN] ago is not brought back: the night may be over.
 */
internal class RunProgress(private val file: Path?) {
    fun keep(run: SequenceRun, at: Instant) {
        val path = file ?: return
        if (run.status == RunStatus.Running || run.status == RunStatus.Paused) {
            val kept =
                KeptRun(
                    run.sequence,
                    run.current,
                    run.done.map { (item, frames) -> DoneFrames(item, frames) },
                    at.toEpochMilliseconds(),
                    run.rounds.map { (block, rounds) -> DoneRounds(block, rounds) },
                )
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { it.writeString(Sequence.JSON.encodeToString(KeptRun.serializer(), kept)) }
        } else if (SystemFileSystem.exists(path)) {
            SystemFileSystem.delete(path)
        }
    }

    /** The run kept, as a safe pause to go on from; null if none, unreadable or too old at [now]. */
    fun read(now: Instant): SequenceRun? = file
        ?.takeIf { SystemFileSystem.exists(it) }
        ?.let { path ->
            runCatching {
                Sequence.JSON.decodeFromString(KeptRun.serializer(), SystemFileSystem.source(path).buffered().use { it.readString() })
            }.getOrNull()
        }?.takeIf { now - Instant.fromEpochMilliseconds(it.at) <= RESTORE_WITHIN }
        ?.let { kept ->
            SequenceRun(
                kept.sequence,
                RunStatus.Paused,
                kept.current,
                kept.done.associate { it.item to it.frames },
                APP_CLOSED,
                kept.rounds.associate { it.block to it.rounds },
            )
        }

    companion object {
        val RESTORE_WITHIN = 30.minutes

        /** Why a run brought back waits. */
        const val APP_CLOSED = "app closed"
    }
}

@Serializable
private class KeptRun(
    val sequence: Sequence,
    val current: List<Int>,
    val done: List<DoneFrames>,
    val at: Long,
    val rounds: List<DoneRounds> = emptyList(),
)

@Serializable
private class DoneFrames(val item: List<Int>, val frames: Int)

@Serializable
private class DoneRounds(val block: List<Int>, val rounds: Int)
