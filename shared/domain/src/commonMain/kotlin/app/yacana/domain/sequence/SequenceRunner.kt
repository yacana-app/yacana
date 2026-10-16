// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.LINK_HICCUP
import app.yacana.domain.equipment.linkTrouble
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.focus.AutofocusState
import app.yacana.domain.focus.AutofocusTrigger
import app.yacana.domain.focus.LightFrame
import app.yacana.domain.focus.TriggerContext
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CaptureSettings
import app.yacana.domain.imaging.CapturedFrame
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.MosaicPanel
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.CentringState
import app.yacana.ports.Solution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How a run of a sequence ended, or that it goes on, or waits in a safe pause. */
enum class RunStatus { Running, Paused, Finished, Stopped, Failed }

/**
 * A run of [sequence]: its [status], the item running as its indices from the root down, the
 * frames taken of each [Exposures] by its indices, why it failed, paused or stopped, and the
 * [rounds] each repeating block has finished, by its indices.
 */
data class SequenceRun(
    val sequence: Sequence,
    val status: RunStatus = RunStatus.Running,
    val current: List<Int> = emptyList(),
    val done: Map<List<Int>, Int> = emptyMap(),
    val error: String? = null,
    val rounds: Map<List<Int>, Int> = emptyMap(),
)

/**
 * Runs a sequence with the connected equipment, item by item, each
 * one written in the session's journal, in the same order as N.I.N.A.: a block with conditions runs
 * round after round while they all hold, asking them before it starts and after each item, and a
 * parallel block runs its items at once. Before each light frame it asks the autofocus triggers of
 * the blocks around it, and runs autofocus when one fires; if that autofocus fails, as under
 * clouds, the triggers wait for the next exposures, so it is not tried again before every frame.
 * A recentre trigger solves the last light and centres again when it drifted too far. A centring or
 * an autofocus that fails is written down and the sequence goes on, as N.I.N.A.'s do by default;
 * any other failure stops it, with the reason, unless the link with the equipment is lost: then it
 * waits in a safe pause, and [resume] goes on from the item it was in, the frame in
 * progress lost. The run going on is kept in [progressFile], so that after the app closes or the
 * phone restarts [restore] brings it back. Tonight's hours are those of [zone].
 */
@Suppress("TooManyFunctions") // Starting, pausing, resuming and stopping a run, and one function per kind of step.
class SequenceRunner(
    private val equipment: EquipmentController,
    private val capture: CaptureController,
    private val centring: Centring,
    private val autofocus: Autofocus,
    private val scope: CoroutineScope,
    private val journal: Journal = Journal.None,
    private val clock: Clock = Clock.System,
    random: Random = Random.Default,
    progressFile: Path? = null,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val steps = SequenceSteps(equipment, journal, random)
    private val progress = RunProgress(progressFile)
    private val mutableState = MutableStateFlow<SequenceRun?>(null)
    private var job: Job? = null

    /** The sensor's temperature at the last darks of each light setting this run, for the darks trigger. */
    private val darkTemperatures = mutableMapOf<List<Any?>, Double>()

    /** The last light a recentre trigger solved, not to solve it again. */
    private var solved: CapturedFrame? = null

    /** Where the last centring left the frames pointing, for the live mosaic to place the panel. */
    private var centred: Solution? = null

    /** The last target whose frames were taken, whose folder the darks and bias of the end go to. */
    private var framed: SkyTarget? = null

    /** The run in progress, or the last one; null before any. */
    val state: StateFlow<SequenceRun?> = mutableState.asStateFlow()

    /** Runs [sequence] from its start, unless one is running. */
    fun start(sequence: Sequence) {
        if (job?.isActive == true) return
        framed = null
        change { SequenceRun(sequence) }
        job = scope.launch { run(sequence) }
    }

    /** Stops the run now, running or paused; the frame in progress is thrown away. [reason] says why, if not by hand. */
    fun stop(reason: String? = null) {
        val status = state.value?.status
        if (status != RunStatus.Running && status != RunStatus.Paused) return
        job?.cancel()
        capture.abort()
        change { it?.copy(status = RunStatus.Stopped, error = reason) }
        journal.log("sequence_stopped", "reason" to reason)
    }

    /**
     * Puts the run in a safe pause, by hand or, with a [reason], as when the link is lost: the frame
     * in progress is thrown away.
     */
    fun pause(reason: String? = null) {
        if (state.value?.status != RunStatus.Running) return
        job?.cancel()
        capture.abort()
        change { it?.copy(status = RunStatus.Paused, error = reason) }
        journal.log("sequence_paused", "reason" to reason)
    }

    /** Goes on with a paused run from the item it was in, as each [Exposures] counts the frames it took. */
    fun resume() {
        val paused = state.value?.takeIf { it.status == RunStatus.Paused } ?: return
        change { paused.copy(status = RunStatus.Running, error = null) }
        journal.log("sequence_resumed", "at" to paused.current)
        job = scope.launch { run(paused.sequence, from = paused.current) }
    }

    /**
     * The sequence changed while it runs or waits, as the advanced editor changes what is still to
     * come: the run goes on with [sequence] from where it is.
     */
    fun update(sequence: Sequence) {
        val status = state.value?.status
        if (status != RunStatus.Running && status != RunStatus.Paused) return
        change { it?.copy(sequence = sequence) }
    }

    /**
     * After the app closed or the phone restarted: the run that was going on, back as a
     * safe pause that [resume] goes on with once the equipment is connected; false if there was none.
     */
    fun restore(): Boolean {
        val run = progress.read(clock.now())?.takeIf { state.value == null } ?: return false
        mutableState.value = run
        journal.log("sequence_restored", "name" to run.sequence.name, "at" to run.current)
        return true
    }

    /** Changes the run, and keeps it on disk while it goes on. */
    private fun change(update: (SequenceRun?) -> SequenceRun?) {
        mutableState.updateAndGet(update)?.let { progress.keep(it, clock.now()) }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun run(sequence: Sequence, from: List<Int>? = null) {
        if (from == null) journal.log("sequence_started", "name" to sequence.name)
        val error =
            try {
                block(emptyList(), Scope(), from)
                capture.sequenceEnded()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e::class.simpleName.orEmpty()
            }
        // A failure while the equipment does not answer well is the link's, not the sequence's: it waits.
        val status =
            when {
                error == null -> RunStatus.Finished
                linkTroubleSoon() -> RunStatus.Paused
                else -> RunStatus.Failed
            }
        change { it?.copy(status = status, error = error) }
        when (status) {
            RunStatus.Finished -> journal.log("sequence_finished")
            RunStatus.Paused -> journal.log("sequence_paused", "reason" to error)
            else -> journal.log("sequence_failed", "reason" to error)
        }
    }

    /**
     * Whether the link is not answering well, now or by the time the polls notice: a request that
     * fails as the WiFi goes can come a second before the next poll does.
     */
    private suspend fun linkTroubleSoon(): Boolean = withTimeoutOrNull(LINK_HICCUP + POLL) {
        while (!linkTrouble()) delay(POLL)
        true
    } == true

    private fun linkTrouble(): Boolean {
        val connected = (equipment.state.value.connection as? Connection.Connected)?.equipment ?: return true
        return connected.linkTrouble(clock.now())
    }

    /** The block at [path] in the sequence as it is now, which the advanced editor may have changed. */
    private fun blockAt(path: List<Int>): Block? =
        path.fold(state.value?.sequence?.root) { block, index -> block?.items?.getOrNull(index) as? Block }

    /**
     * The block at [path], with the triggers of the blocks around it and its target, or theirs,
     * round after round while its conditions hold; resuming [from] an item, those before it were
     * done, in the round it was in. A parallel block resumed runs again: its items ran at once.
     */
    private suspend fun block(path: List<Int>, outer: Scope, from: List<Int>?) {
        val first = blockAt(path) ?: return
        journal.log("sequence_block", "name" to first.name)
        val scope = scope(first, outer)
        val resuming = from.takeUnless { first.parallel }
        val round = resuming?.let { state.value?.rounds?.get(path) } ?: 0
        if (resuming == null && !holds(first, round, scope.target)) {
            journal.log("sequence_block_skipped", "name" to first.name)
        } else {
            rounds(path, scope, round, resuming)
            change { run -> run?.copy(rounds = run.rounds.filterKeys { it != path }) }
        }
    }

    /** What the items of [block] run with: the triggers around it and its own, started now, and its target or the one around it. */
    private fun scope(block: Block, outer: Scope): Scope {
        val context = triggerContext()
        return Scope(
            outer.autofocus + block.triggers.mapNotNull { it.autofocus()?.also { trigger -> trigger.start(context) } },
            outer.recentre + block.triggers.filterIsInstance<Trigger.RecentreAfterDrift>(),
            block.target ?: outer.target,
            outer.darks + block.triggers.filterIsInstance<Trigger.DarksAfterTemperature>(),
            mosaicOf(block, outer.mosaic),
        )
    }

    /** The block at [path] round after round from [first], while it finishes them and its conditions hold. */
    private suspend fun rounds(path: List<Int>, scope: Scope, first: Int, from: List<Int>?) {
        var round = first
        var resuming = from
        var again = true
        while (again) {
            if (resuming == null) forget(path)
            val finished = if (blockAt(path)?.parallel == true) together(path, scope) else inOrder(path, round, scope, resuming)
            resuming = null
            val block = blockAt(path)
            again = finished && block != null && block.conditions.isNotEmpty()
            if (block != null && again) {
                round++
                change { it?.copy(rounds = it.rounds + (path to round)) }
                again = holds(block, round, scope.target)
                if (again) journal.log("sequence_round", "name" to block.name, "round" to round + 1)
            }
        }
    }

    /** A new round of the block at [path]: the frames and rounds inside it count from nothing again. */
    private fun forget(path: List<Int>) = change { run ->
        fun List<Int>.inside() = size > path.size && take(path.size) == path
        run?.copy(done = run.done.filterKeys { !it.inside() }, rounds = run.rounds.filterKeys { !it.inside() })
    }

    /**
     * The block's items one after another, from [from] when resuming, as they are when each starts;
     * false when one of its conditions ended it after an item.
     */
    private suspend fun inOrder(path: List<Int>, round: Int, scope: Scope, from: List<Int>?): Boolean {
        var index = 0
        var ended = false
        while (!ended) {
            val item = blockAt(path)?.items?.getOrNull(index) ?: break
            val at = path + index++
            if (from == null || !before(at, from)) {
                step(item, at, scope, from?.takeIf { it.take(at.size) == at })
                ended = endedAfterItem(path, round, scope)
            }
        }
        return !ended
    }

    /** Whether the block at [path] ends after an item, as one of its conditions no longer holds. */
    private fun endedAfterItem(path: List<Int>, round: Int, scope: Scope): Boolean {
        val block = blockAt(path) ?: return false
        val ended = block.conditions.isNotEmpty() && !holds(block, round, scope.target)
        if (ended) journal.log("sequence_block_ended", "name" to block.name)
        return ended
    }

    /** The block's items all at once; it ends when the last of them does. */
    private suspend fun together(path: List<Int>, scope: Scope): Boolean {
        val items = blockAt(path)?.items.orEmpty()
        coroutineScope { items.forEachIndexed { index, item -> launch { step(item, path + index, scope, null) } } }
        return true
    }

    private suspend fun step(item: SequenceItem, at: List<Int>, scope: Scope, from: List<Int>?) {
        if (item is Block) return block(at, scope, from)
        change { it?.copy(current = at) }
        journal.log("sequence_item", "item" to item.toString(), "target" to scope.target?.name)
        instruction(item, at, scope)
        journal.log("sequence_item_done", "item" to item.toString())
    }

    /** Whether all of [block]'s conditions hold after [round] rounds, for its [target]. */
    private fun holds(block: Block, round: Int, target: SkyTarget?): Boolean = block.conditions.all { condition ->
        when (condition) {
            is Condition.Loop -> round < condition.times
            is Condition.UntilTime -> clock.now() < tonightAt(condition.minute, clock.now(), zone())
            is Condition.WhileAbove -> altitude(target ?: error(ALTITUDE_NEEDS_TARGET)) >= condition.altitudeDeg
        }
    }

    /** [target]'s altitude now, from the profile's site. */
    private fun altitude(target: SkyTarget): Double = Ephemerides.altitude(target.position, clock.now(), steps.site())

    private suspend fun instruction(item: SequenceItem, at: List<Int>, scope: Scope) {
        when (item) {
            is GoToTarget -> goTo(item, scope.target ?: error("Go to the target needs a block with a target"))
            RunAutofocus -> focus()
            is Exposures -> exposures(item, at, scope)
            is WaitUntilTime -> waitUntil(item.minute)
            is WaitFor -> delay(item.minutes.minutes)
            is WaitUntilAbove -> waitUntilAbove(item.altitudeDeg, scope.target ?: error(ALTITUDE_NEEDS_TARGET))
            is Note -> journal.log("sequence_note", "text" to item.text)
            else -> steps.device(item)
        }
    }

    /** Waits until [minute] of tonight's clock; on at once if it has passed. */
    private suspend fun waitUntil(minute: Int) {
        val wait = tonightAt(minute, clock.now(), zone()) - clock.now()
        if (wait.isPositive()) delay(wait)
    }

    /** Waits until [target] is [altitudeDeg] above the horizon, looking again every so often. */
    private suspend fun waitUntilAbove(altitudeDeg: Double, target: SkyTarget) {
        while (altitude(target) < altitudeDeg) delay(ALTITUDE_POLL)
    }

    private suspend fun goTo(step: GoToTarget, target: SkyTarget) {
        // The dithers of this target go around where it is framed.
        steps.dithers.reset()
        centred = null
        if (!step.centre) {
            val mount = steps.mount()
            mount.goToAndWait(mount.toMount(target.position, clock.now()))
            return
        }
        val end = centring.centreNow(target.position)
        centred = (end as? CentringState.Centred)?.solution
        if (end !is CentringState.Centred) journal.log("sequence_centring_failed", "target" to target.name, "end" to end.toString())
    }

    /** Runs autofocus; false, and written down, if it failed. */
    private suspend fun focus(): Boolean {
        val end = autofocus.runNow()
        if (end !is AutofocusState.Done) journal.log("sequence_autofocus_failed", "end" to end.toString())
        return end is AutofocusState.Done
    }

    /**
     * The target whose folder [step]'s frames go to: its own for lights; for darks and bias outside a
     * target, as at the start or the end, the one they calibrate, the last one taken or before any
     * the first of the sequence.
     */
    private fun folder(step: Exposures, target: SkyTarget?): SkyTarget? {
        if (target != null) framed = target
        return if (step.type == FrameType.Light) target else target ?: framed ?: state.value?.sequence?.root?.firstTarget()
    }

    /** The step's frames, asking the triggers before each light and dithering after every so many. */
    private suspend fun exposures(step: Exposures, at: List<Int>, scope: Scope) {
        val target = scope.target
        val connected = steps.connected()
        val camera = connected.camera ?: error("The profile has no camera connected")
        // The filter just selected: the wheel's polled position catches up a moment later.
        val filter = connected.filterWheel?.let { steps.filter(it, step) }
        val settings =
            CaptureSettings(
                // Bias are as short as the camera can take.
                exposureSeconds = if (step.type == FrameType.Bias) camera.info.exposureMin else step.exposureSeconds,
                // Without its own, Imaging's, as centring and the autofocus take, not whatever the camera was left at.
                gain = step.gain ?: capture.state.value.settings.gain,
                offset = step.offset,
                binning = step.binning,
                type = step.type,
                target = folder(step, target)?.name.orEmpty(),
                // A panel's lights, placed by the solution that centred it, for the live mosaic.
                mosaic = scope.mosaic?.takeIf { step.type == FrameType.Light && it.index >= 0 }?.copy(solution = centred),
            )
        val dither = connected.profile.options.dither
        var asking = step.type == FrameType.Light
        // Resumed after a safe pause, it goes on from the frames it took.
        val taken = state.value?.done?.get(at) ?: 0
        repeat(step.count - taken) { index ->
            if (asking) asking = askTriggers(scope.autofocus, filter)
            if (step.type == FrameType.Light) {
                recentre(scope)
                darksIfTemperature(scope, step, settings)
            }
            capture.takeInSequence(settings)
            val done = taken + index + 1
            change { it?.copy(done = it.done + (at to done)) }
            if (step.ditherEvery > 0 && done % step.ditherEvery == 0 && done < step.count) dither(dither)
        }
        if (step.type == FrameType.Dark) sensorTemperature()?.let { darkTemperatures[settings.darkKey()] = it }
    }

    /**
     * Before a light frame, with a darks trigger around it: darks with the light's settings, behind
     * the dark filter, when none were taken for them with the sensor within the trigger's degrees of
     * now; then back to the light's filter.
     */
    private suspend fun darksIfTemperature(scope: Scope, step: Exposures, settings: CaptureSettings) {
        val trigger = scope.darks.minByOrNull { it.amount } ?: return
        // Without a dark filter no light can be kept out, and without the sensor's temperature there is nothing to follow.
        val wheel = steps.connected().filterWheel?.takeIf { capture.darkFilter() != null }
        val temperature = sensorTemperature()
        val key = settings.darkKey()
        val near = temperature != null && darkTemperatures[key]?.let { abs(it - temperature) < trigger.amount } == true
        if (wheel == null || temperature == null || near) return
        journal.log("sequence_darks", "temperature" to temperature, "count" to trigger.count, "seconds" to settings.exposureSeconds)
        val darks = step.darks(trigger.count)
        steps.filter(wheel, darks)
        repeat(trigger.count) { capture.takeInSequence(settings.copy(type = FrameType.Dark)) }
        darkTemperatures[key] = temperature
        steps.filter(wheel, step)
    }

    private fun sensorTemperature(): Double? = steps.connected().camera?.state?.value?.value?.ccdTemperature

    /** What darks are for: a light's exposure, gain, offset and binning. */
    private fun CaptureSettings.darkKey() = listOf(exposureSeconds, gain, offset, binning)

    /**
     * Before a light frame, with a recentre trigger around it: the last light's solve, and centring
     * again when it drifted further than the trigger allows, as N.I.N.A.'s Center After Drift.
     */
    private suspend fun recentre(scope: Scope) {
        val target = scope.target
        val limit = scope.recentre.minOfOrNull { it.limitArcmin(steps.pixelScale) }
        if (target == null || limit == null) return
        // The last light is on screen once it is saved.
        capture.sequenceSaved()
        val last = capture.state.value.last?.takeIf { it !== solved && it.capture.type == FrameType.Light } ?: return
        solved = last
        val off = centring.driftArcmin(last, target.position)
        if (off != null && off > limit) {
            journal.log("sequence_recentre", "target" to target.name, "off" to off, "limit" to limit)
            goTo(GoToTarget(centre = true), target)
        }
    }

    private suspend fun dither(options: DitherOptions) {
        val at = steps.dither(options)
        journal.log(
            "sequence_dither",
            "east" to at.eastPixels,
            "north" to at.northPixels,
            "goTos" to at.goTos,
            "pixels" to options.pixels,
            "settle" to options.settleSeconds,
        )
    }

    /**
     * Asks every trigger, as each keeps what it saw, and runs autofocus if any fires; false when
     * that autofocus failed, so they are not asked again until the next exposures.
     */
    private suspend fun askTriggers(triggers: List<AutofocusTrigger>, filter: String?): Boolean {
        val context = triggerContext(filter)
        val fired = triggers.filter { it.shouldTrigger(context) }
        if (fired.isEmpty()) return true
        journal.log("sequence_autofocus_trigger", "triggers" to fired.joinToString { it::class.simpleName.orEmpty() })
        return focus()
    }

    /** What the triggers look at; the [filter] just selected, or the wheel's as polled. */
    private fun triggerContext(filter: String? = null): TriggerContext {
        val connected = runCatching { steps.connected() }.getOrNull()
        val wheel = connected?.filterWheel
        return TriggerContext(
            now = clock.now(),
            temperature = connected?.focuser?.state?.value?.value?.temperature,
            filter = filter ?: wheel?.position?.value?.value?.let { wheel.names.getOrNull(it) },
            lastAutofocus = autofocus.last.value,
            lights = capture.history.frames.value.filter { it.type == FrameType.Light }.map { LightFrame(it.at, it.filter, it.hfr) },
        )
    }
}

/**
 * What a block's items run with: the triggers of the blocks around them, the target they go to,
 * and, in a mosaic, its panels and which one this is (-1 for the mosaic's own block).
 */
private class Scope(
    val autofocus: List<AutofocusTrigger> = emptyList(),
    val recentre: List<Trigger.RecentreAfterDrift> = emptyList(),
    val target: SkyTarget? = null,
    val darks: List<Trigger.DarksAfterTemperature> = emptyList(),
    val mosaic: MosaicPanel? = null,
)

/** How often the equipment's state is read. */
private val POLL = 1.seconds

/** How often a wait for a target's altitude looks again. */
private val ALTITUDE_POLL = 1.minutes

private const val ALTITUDE_NEEDS_TARGET = "Waiting for the altitude needs a block with a target"

/** Whether the item at [at] comes before [from] in the tree, and is not one that holds it. */
internal fun before(at: List<Int>, from: List<Int>): Boolean {
    val differs = at.indices.firstOrNull { it >= from.size || at[it] != from[it] } ?: return false
    return differs < from.size && at[differs] < from[differs]
}

/** The first target of this block or of the blocks in it, in the order they run. */
private fun Block.firstTarget(): SkyTarget? = target ?: items.filterIsInstance<Block>().firstNotNullOfOrNull { it.firstTarget() }

/**
 * The mosaic [block] is in: its own, with its panels, when it holds blocks of their own targets, as
 * a framing's mosaic; which panel, when it is one of [outer]'s; else [outer]'s, as for blocks inside
 * a panel's.
 */
internal fun mosaicOf(block: Block, outer: MosaicPanel?): MosaicPanel? {
    val target = block.target
    val panels = block.items.filterIsInstance<Block>().mapNotNull { it.target }
    return when {
        target != null && panels.size > 1 -> MosaicPanel(target.name, target.position, panels.map { it.position }, -1)
        target != null && outer != null && outer.index < 0 -> outer.copy(index = outer.panels.indexOf(target.position))
        target != null -> null
        else -> outer
    }
}
