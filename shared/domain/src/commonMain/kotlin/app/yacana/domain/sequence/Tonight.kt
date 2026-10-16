// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.alpaca.SwitchInfo
import app.yacana.domain.imaging.FrameType
import kotlinx.serialization.Serializable
import kotlin.math.round

/** How many darks and how many bias the end takes, for each setting of the lights. */
const val TONIGHT_CALIBRATION = 10

/**
 * When tonight's plan takes darks with each setting of the lights, [count] each time: at the
 * start, for the live stack from the first frame, and at the end with the bias. By the sensor's
 * temperature, it is the trigger [Trigger.DarksAfterTemperature].
 */
@Serializable
data class DarkRules(val atStart: Boolean = false, val atEnd: Boolean = true, val count: Int = TONIGHT_CALIBRATION)

/**
 * Whether tonight's plan turns the dew heater on at the start, and off at the end; with a heater
 * that takes a value, at [power], a share of its range.
 */
@Serializable
data class HeaterRule(val on: Boolean = true, val power: Double = 1.0)

/** The dark rules [tonight] built this sequence with, to open it again in the simple editor. */
fun Sequence.darkRules(): DarkRules {
    fun Block?.darks() = this?.items.orEmpty().filterIsInstance<Exposures>().filter { it.type == FrameType.Dark }
    val start = (root.items.firstOrNull() as? Block).darks()
    val end = (root.items.lastOrNull() as? Block).darks()
    return DarkRules(start.isNotEmpty(), end.isNotEmpty(), (start + end).firstOrNull()?.count ?: TONIGHT_CALIBRATION)
}

/**
 * Tonight's sequence from the simple editor's targets, as the design "Sequence · tonight,
 * before starting" lays it out:
 * - the start: unpark, the dew [heater] on if [heaterRule] says so, and darks if [darks] says so;
 * - a block per target, with one per panel of a mosaic named as N.I.N.A. names them, each going to
 *   its panel and centring it, then taking the target's exposures; [triggers] around them:
 *   autofocus ones with a [focuser], and darks by temperature; recentring in each target's block,
 *   which is where a target's triggers go;
 * - the end: darks and bias with each setting of the lights if [darks] says so, park, and
 *   the heater off.
 */
fun tonight(
    targets: List<Target>,
    heater: SwitchInfo?,
    focuser: Boolean,
    triggers: List<Trigger> = DEFAULT_TRIGGERS,
    darks: DarkRules = DarkRules(),
    heaterRule: HeaterRule = HeaterRule(),
): Sequence {
    val switch = heater?.takeIf { heaterRule.on }
    val recentre = triggers.filterIsInstance<Trigger.RecentreAfterDrift>()
    val blocks = targets.map { it.block().copy(triggers = recentre) }
    val lights = targets.flatMap { it.exposures }.filter { it.type == FrameType.Light }
    // One set of darks for each exposure, gain, offset and binning, and of bias for each but the exposure.
    val darkSets = lights.distinctBy { listOf(it.exposureSeconds, it.gain, it.offset, it.binning) }.map { it.darks(darks.count) }
    val bias = lights.distinctBy { listOf(it.gain, it.offset, it.binning) }.map { it.bias(darks.count) }
    val start =
        Block(
            START,
            listOfNotNull(
                Unpark,
                switch?.let {
                    SetSwitch(it.name, it.at(heaterRule.power))
                },
            ) + darkSets.takeIf { darks.atStart }.orEmpty(),
        )
    val calibration = (darkSets + bias).takeIf { darks.atEnd }.orEmpty()
    val end = Block(END, calibration + listOfNotNull(Park, switch?.let { SetSwitch(it.name, it.min) }))
    return Sequence(
        TONIGHT,
        Block(
            TONIGHT,
            listOf(start) + blocks + end,
            triggers = triggers.filter {
                it !is Trigger.RecentreAfterDrift &&
                    (focuser || it.autofocus() == null)
            },
        ),
    )
}

/** Tonight's triggers as the plan keeps them: the root's, and the recentring [tonight] puts in each target's block. */
fun Sequence.planTriggers(): List<Trigger> =
    root.triggers + root.items.filterIsInstance<Block>().flatMap { it.triggers }.filterIsInstance<Trigger.RecentreAfterDrift>().take(1)

/**
 * A target's block as the simple editor makes it: going to the target and centring it, then taking
 * its exposures; a mosaic, a block per panel named as N.I.N.A. names them.
 */
fun Target.block(): Block {
    fun panel(name: String, at: TargetPanel) =
        Block(name, listOf(GoToTarget(centre = true)) + exposures, SkyTarget(name, at.raHours, at.decDeg, at.rotationDeg))
    val panels = panels.ifEmpty { listOf(TargetPanel(1, raHours, decDeg, rotationDeg)) }
    if (panels.size == 1) return panel(name, panels.single())
    // The mosaic's own centre and rotation stay on its block, so it opens again as it was framed.
    return Block(name, panels.map { panel("$name Panel ${it.number}", it) }, SkyTarget(name, raHours, decDeg, rotationDeg))
}

/**
 * The targets of a sequence as the simple editor makes them, to open it again in it: its
 * blocks between the start and the end, each going to its target and taking the same exposures in
 * every panel; null for a sequence of another shape, which only the advanced editor shows.
 */
fun Sequence.simpleTargets(): List<Target>? {
    val middle = root.items.drop(1).dropLast(1)
    val targets = middle.mapNotNull { (it as? Block)?.simpleTarget() }
    return targets.takeIf { root.items.size >= 2 && it.size == middle.size }
}

/** A target's block as the simple editor makes it: a single field, or a mosaic's panels all with the same exposures. */
private fun Block.simpleTarget(): Target? {
    val inner = items.filterIsInstance<Block>()
    val panels = inner.ifEmpty { listOf(this) }
    val exposures = panels.map { it.panelExposures() }.distinct().singleOrNull()
    val spots = panels.mapNotNull { it.target }
    val whole = inner.isEmpty() || inner.size == items.size
    val centre = target?.takeIf { whole && spots.size == panels.size }
    return if (centre == null || exposures == null) {
        null
    } else {
        val numbered = spots.mapIndexed { index, at -> TargetPanel(index + 1, at.raHours, at.decDeg, at.rotationDeg) }
        Target(name, centre.raHours, centre.decDeg, centre.rotationDeg, numbered, exposures)
    }
}

/** A panel's exposures after it goes to its target and centres it, as the simple editor makes it. */
private fun Block.panelExposures(): List<Exposures>? =
    if (items.firstOrNull() == GoToTarget(centre = true) && items.drop(1).all { it is Exposures }) {
        items.drop(1).map { it as Exposures }
    } else {
        null
    }

/** The switch's value at [share] of its range, on its steps; on and off only, on. */
private fun SwitchInfo.at(share: Double): Double {
    if (isOnOff) return max
    val value = min + (max - min) * share.coerceIn(0.0, 1.0)
    return if (step > 0) (min + round((value - min) / step) * step).coerceIn(min, max) else value
}

/** The switch that heats against dew, as the Seestar's "Dew heater", if [switches] have one that can be set. */
fun dewHeater(switches: List<SwitchInfo>): SwitchInfo? =
    switches.firstOrNull { switch -> switch.canWrite && HEATER_NAMES.any { switch.name.contains(it, ignoreCase = true) } }

private const val TONIGHT = "Tonight"
private val HEATER_NAMES = listOf("heater", "calentador", "dew")
