// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.imaging.DARK_FILTER
import app.yacana.domain.sky.Framing
import app.yacana.ports.SkyPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A panel of a target: where the mount goes and how the camera is turned, in J2000. */
@Serializable
data class TargetPanel(val number: Int, val raHours: Double, val decDeg: Double, val rotationDeg: Double) {
    val position: SkyPosition get() = SkyPosition(raHours, decDeg)
}

/**
 * A target as framed: its name, the framing's centre and rotation, exactly as sent, and
 * its panels, one for a single field; and the [exposures] each panel takes, in order.
 */
@Serializable
data class Target(
    val name: String,
    val raHours: Double,
    val decDeg: Double,
    val rotationDeg: Double,
    val panels: List<TargetPanel>,
    val exposures: List<Exposures> = emptyList(),
) {
    val position: SkyPosition get() = SkyPosition(raHours, decDeg)

    companion object {
        fun of(name: String, framing: Framing, exposures: List<Exposures> = emptyList()) = Target(
            name,
            framing.center.raHours,
            framing.center.decDeg,
            framing.rotationDeg,
            framing.panels.map { TargetPanel(it.number, it.center.raHours, it.center.decDeg, it.rotationDeg) },
            exposures,
        )
    }
}

/** How many frames a new target takes, of [NEW_TARGET_SECONDS], as the simple editor starts it. */
const val NEW_TARGET_FRAMES = 60

const val NEW_TARGET_SECONDS = 60.0

/** A new target's lights dither after every so many frames. */
const val DITHER_EVERY = 3

/**
 * A new target's frames: an hour of 60 s through the first of [filters] that lets the light in,
 * or the wheel's own without them, dithering every [DITHER_EVERY]; the editor changes them.
 */
fun newTargetExposures(filters: List<String>) = listOf(
    Exposures(
        count = NEW_TARGET_FRAMES,
        exposureSeconds = NEW_TARGET_SECONDS,
        filter = filters.firstOrNull { !it.equals(DARK_FILTER, ignoreCase = true) },
        ditherEvery = DITHER_EVERY,
    ),
)

/** With a focuser, tonight's autofocus runs when the temperature changes by 1 °C, as the design's rule, until it is changed. */
val DEFAULT_TRIGGERS: List<Trigger> = listOf(Trigger.AutofocusAfterTemperature(1.0))

/**
 * Tonight's plan as kept: its targets, the autofocus triggers of the whole sequence, and the night
 * as the advanced editor left it, when it has what the simple editor can't show.
 */
@Serializable
private data class Plan(
    val targets: List<Target> = emptyList(),
    val triggers: List<Trigger> = DEFAULT_TRIGGERS,
    val advanced: Sequence? = null,
    val darks: DarkRules = DarkRules(),
)

/**
 * Tonight's targets, as added from their framing and edited in the simple editor, and its
 * autofocus triggers; or tonight as the advanced editor changed it. Kept in [file],
 * only while the app is open without one. Each change can be undone, the last [UNDO_DEPTH] of them.
 */
@Suppress("TooManyFunctions") // One function per change to the plan, each one undone as the others.
class Targets(private val file: Path? = null) {
    private val plan = MutableStateFlow(read())
    private val mutableTargets = MutableStateFlow(plan.value.targets)
    private val mutableTriggers = MutableStateFlow(plan.value.triggers)
    private val mutableAdvanced = MutableStateFlow(plan.value.advanced)
    private val before = ArrayDeque<Plan>()
    private val mutableCanUndo = MutableStateFlow(false)

    val targets: StateFlow<List<Target>> = mutableTargets.asStateFlow()

    /** When autofocus runs before a light frame, with a focuser. */
    val triggers: StateFlow<List<Trigger>> = mutableTriggers.asStateFlow()
    private val mutableDarks = MutableStateFlow(plan.value.darks)

    /** When tonight's plan takes darks. */
    val darks: StateFlow<DarkRules> = mutableDarks.asStateFlow()

    /**
     * Tonight as the advanced editor left it, when it has what the simple editor can't show, as
     * conditions: it is what the night runs. Null while the plan is the simple editor's.
     */
    val advanced: StateFlow<Sequence?> = mutableAdvanced.asStateFlow()

    /** Whether there is a change to undo. */
    val canUndo: StateFlow<Boolean> = mutableCanUndo.asStateFlow()

    private val mutableFillTheNight = MutableStateFlow(false)

    /** Whether the next target added fills the night, as the template "One target all night" asks. */
    val fillTheNight: StateFlow<Boolean> = mutableFillTheNight.asStateFlow()

    /** Adds [target] to the plan, and at the end of the targets of tonight's advanced sequence, if there is one. */
    fun add(target: Target) {
        mutableFillTheNight.value = false
        change { it.copy(targets = it.targets + target, advanced = it.advanced?.withTarget(target)) }
    }

    /** Tonight becomes [sequence], as the advanced editor leaves it; null goes back to the simple editor's plan. */
    fun setAdvanced(sequence: Sequence?) = change { it.copy(advanced = sequence) }

    /** Tonight's plan becomes [targets] with [triggers] and [darks], as a saved sequence opens; it can be undone. */
    fun replace(targets: List<Target>, triggers: List<Trigger> = this.triggers.value, darks: DarkRules = this.darks.value) =
        change { Plan(targets, triggers, darks = darks) }

    /** Tonight becomes [sequence], as a saved one opens: in the simple editor's plan if it is one, or as an advanced sequence. */
    fun open(sequence: Sequence) {
        val targets = sequence.simpleTargets()
        change {
            if (targets !=
                null
            ) {
                Plan(targets, sequence.root.triggers.ifEmpty { it.triggers }, darks = sequence.darkRules())
            } else {
                it.copy(advanced = sequence)
            }
        }
    }

    /** A new plan, as a template starts one: no targets, and with [fillTheNight] the first fills the night. */
    fun startNew(fillTheNight: Boolean = false) {
        change { it.copy(targets = emptyList(), advanced = null) }
        mutableFillTheNight.value = fillTheNight
    }

    fun remove(index: Int) = change { it.copy(targets = it.targets.filterIndexed { i, _ -> i != index }) }

    /** The target at [index] takes [exposures], every panel. */
    fun setExposures(index: Int, exposures: List<Exposures>) =
        change { it.copy(targets = it.targets.mapIndexed { i, target -> if (i == index) target.copy(exposures = exposures) else target }) }

    /** Autofocus runs when any of [triggers] fires. */
    fun setTriggers(triggers: List<Trigger>) = change { it.copy(triggers = triggers) }

    /** The darks come as [rules] say, and with [temperature], also when the sensor's changes by that much. */
    fun setDarks(rules: DarkRules, temperature: Double?) = change { plan ->
        val others = plan.triggers.filterNot { it is Trigger.DarksAfterTemperature }
        plan.copy(darks = rules, triggers = others + listOfNotNull(temperature?.let { Trigger.DarksAfterTemperature(it, rules.count) }))
    }

    /** Goes back to before the last change. */
    fun undo() {
        val last = before.removeLastOrNull() ?: return
        show(last)
        mutableCanUndo.value = before.isNotEmpty()
        write()
    }

    private fun change(how: (Plan) -> Plan) {
        val now = plan.value
        before.addLast(now)
        if (before.size > UNDO_DEPTH) before.removeFirst()
        show(how(now))
        mutableCanUndo.value = true
        write()
    }

    private fun show(next: Plan) {
        plan.value = next
        mutableTargets.value = next.targets
        mutableTriggers.value = next.triggers
        mutableAdvanced.value = next.advanced
        mutableDarks.value = next.darks
    }

    private fun write() {
        file?.let { path ->
            SystemFileSystem.sink(path).buffered().use { it.writeString(JSON.encodeToString(Plan.serializer(), plan.value)) }
        }
    }

    /** The plan in the file; a file from before triggers were kept holds only the targets. */
    private fun read(): Plan {
        val text =
            file?.takeIf { SystemFileSystem.exists(it) }?.let { path ->
                runCatching { SystemFileSystem.source(path).buffered().use { it.readString() } }.getOrNull()
            }
        val plan = text?.let { runCatching { JSON.decodeFromString(Plan.serializer(), it) }.getOrNull() }
        val targets = text?.let { runCatching { JSON.decodeFromString(ListSerializer(Target.serializer()), it) }.getOrNull() }
        return plan ?: targets?.let { Plan(it) } ?: Plan()
    }

    /** Tonight with [target] at the end of its targets' area. */
    private fun Sequence.withTarget(target: Target): Sequence =
        areas().let { it.copy(targets = it.targets.copy(items = it.targets.items + target.block())) }.toSequence(name)

    companion object {
        /** How many changes can be undone, beyond the 20 the editor promises. */
        const val UNDO_DEPTH = 50

        private val JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "kind"
        }
    }
}
