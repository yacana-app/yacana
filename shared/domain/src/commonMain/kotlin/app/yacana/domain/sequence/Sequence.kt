// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.focus.AutofocusTrigger
import app.yacana.domain.imaging.FrameType
import app.yacana.ports.SkyPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A sequence: N.I.N.A.'s tree of blocks and instructions, the one
 * model both editors show. The simple editor's night is a [root] with a start block, one
 * block per target and an end block, as NINA's start, target and end areas; the advanced editor
 * shows the same tree as it is, its targets as one area ([NightAreas]).
 */
@Serializable
data class Sequence(val name: String, val root: Block) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): Sequence = JSON.decodeFromString(serializer(), text)

        internal val JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "kind"
        }
    }
}

/** One step of a sequence: a block that holds others, or an instruction. */
@Serializable
sealed interface SequenceItem

/**
 * Where a block's instructions point: a target's name, which its frames take, its centre in J2000,
 * and how the camera is turned for it, as NINA's position angle.
 */
@Serializable
data class SkyTarget(val name: String, val raHours: Double, val decDeg: Double, val rotationDeg: Double = 0.0) {
    val position: SkyPosition get() = SkyPosition(raHours, decDeg)
}

/**
 * Items run one after another, NINA's sequential container, or all at once when [parallel], its
 * parallel container, which ends when the last of them does. With a [target], it is NINA's deep sky
 * object container, whose instructions go to that target and whose frames take its name. [triggers]
 * are asked before each light frame inside it, with those of the blocks around it. With
 * [conditions], NINA's loop conditions, it runs again and again while all of them hold: they are
 * asked before it starts, which skips it if one does not, and after each of its items.
 */
@Serializable
@SerialName("block")
data class Block(
    val name: String,
    val items: List<SequenceItem> = emptyList(),
    val target: SkyTarget? = null,
    val triggers: List<Trigger> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val parallel: Boolean = false,
) : SequenceItem

/** NINA's Unpark Scope. */
@Serializable
@SerialName("unpark")
data object Unpark : SequenceItem

/** NINA's Park Scope. */
@Serializable
@SerialName("park")
data object Park : SequenceItem

/** NINA's Cool Camera: the cooler on at [temperatureC], waiting up to [minutes] for the sensor to get there. */
@Serializable
@SerialName("cool")
data class CoolCamera(val temperatureC: Double, val minutes: Double = 10.0) : SequenceItem

/** NINA's Warm Camera: the cooler off. */
@Serializable
@SerialName("warm")
data object WarmCamera : SequenceItem

/** NINA's Set Switch Value, as for the dew heater: [value] on the switch called [name]; on and off are 1 and 0. */
@Serializable
@SerialName("switch")
data class SetSwitch(val name: String, val value: Double) : SequenceItem

/** NINA's Slew to Target, and with [centre] its Center: the mount goes to the block's target. */
@Serializable
@SerialName("goto")
data class GoToTarget(val centre: Boolean = true) : SequenceItem

/** NINA's Run Autofocus. */
@Serializable
@SerialName("autofocus")
data object RunAutofocus : SequenceItem

/** NINA's Wait for Time: until [minute] of tonight's clock, from local midnight; on at once if it has passed. */
@Serializable
@SerialName("wait_time")
data class WaitUntilTime(val minute: Int) : SequenceItem

/** NINA's Wait for Time Span: [minutes] doing nothing. */
@Serializable
@SerialName("wait")
data class WaitFor(val minutes: Double) : SequenceItem

/** NINA's Wait for Altitude: until the block's target is [altitudeDeg] above the horizon. */
@Serializable
@SerialName("wait_altitude")
data class WaitUntilAbove(val altitudeDeg: Double) : SequenceItem

/** NINA's Switch Filter: the wheel at the filter called [filter]. */
@Serializable
@SerialName("filter")
data class SwitchFilter(val filter: String) : SequenceItem

/** NINA's Move Focuser: the focuser at [position], in its steps. */
@Serializable
@SerialName("focuser")
data class MoveFocuser(val position: Int) : SequenceItem

/** NINA's Set Tracking, stopped: the mount stays still. */
@Serializable
@SerialName("stop_tracking")
data object StopTracking : SequenceItem

/** NINA's Annotation: [text] in the night's diary. */
@Serializable
@SerialName("note")
data class Note(val text: String) : SequenceItem

/**
 * NINA's loop conditions, which keep a block running: for [Loop.times] rounds, until
 * [UntilTime.minute] of tonight's clock, or while the block's target is [WhileAbove.altitudeDeg]
 * above the horizon.
 */
@Serializable
sealed interface Condition {
    @Serializable
    @SerialName("loop")
    data class Loop(val times: Int) : Condition

    @Serializable
    @SerialName("until_time")
    data class UntilTime(val minute: Int) : Condition

    @Serializable
    @SerialName("while_above")
    data class WhileAbove(val altitudeDeg: Double) : Condition
}

/**
 * NINA's Smart Exposure: [count] frames of [type] through [filter] (the wheel's current one when
 * null) of [exposureSeconds], with [gain], [offset] and [binning] (the camera's own when null),
 * dithering after every [ditherEvery] frames (never with 0).
 */
@Serializable
@SerialName("exposures")
data class Exposures(
    val count: Int,
    val exposureSeconds: Double,
    val filter: String? = null,
    val type: FrameType = FrameType.Light,
    val gain: Int? = null,
    val offset: Int? = null,
    val binning: Int = 1,
    val ditherEvery: Int = 0,
) : SequenceItem {
    /** [count] darks with these lights' exposure, gain, offset and binning. */
    fun darks(count: Int) = copy(count = count, type = FrameType.Dark, filter = null, ditherEvery = 0)

    /** [count] bias with these lights' gain, offset and binning, at the camera's shortest exposure. */
    fun bias(count: Int) = copy(count = count, type = FrameType.Bias, exposureSeconds = 0.0, filter = null, ditherEvery = 0)
}

/**
 * N.I.N.A.'s triggers as a block keeps them: its autofocus ones, each run asking a fresh
 * [AutofocusTrigger], and its Center After Drift.
 */
@Serializable
sealed interface Trigger {
    /** The autofocus trigger to ask before each light frame, a fresh one; null for one that does not focus. */
    fun autofocus(): AutofocusTrigger? = null

    /** When the focuser's temperature changes by [amount] °C. */
    @Serializable
    @SerialName("af_temperature")
    data class AutofocusAfterTemperature(val amount: Double = 5.0) : Trigger {
        override fun autofocus() = AutofocusTrigger.AfterTemperatureChange(amount)
    }

    /** When the filter changes. */
    @Serializable
    @SerialName("af_filter")
    data object AutofocusAfterFilter : Trigger {
        override fun autofocus() = AutofocusTrigger.AfterFilterChange()
    }

    /** When the HFR grows by [amount] % over the last [sampleSize] frames. */
    @Serializable
    @SerialName("af_hfr")
    data class AutofocusAfterHfr(val amount: Double = 5.0, val sampleSize: Int = 10) : Trigger {
        override fun autofocus() = AutofocusTrigger.AfterHfrIncrease(amount, sampleSize)
    }

    /** When [minutes] have gone by. */
    @Serializable
    @SerialName("af_time")
    data class AutofocusAfterTime(val minutes: Double = 30.0) : Trigger {
        override fun autofocus() = AutofocusTrigger.AfterTime(minutes)
    }

    /**
     * NINA's Center After Drift: before a light frame, the last one's plate solve; when it is more
     * than [arcminutes] from the block's target, the mount centres it again.
     */
    @Serializable
    @SerialName("recentre")
    data class RecentreAfterDrift(val arcminutes: Double = 1.0) : Trigger {
        /**
         * How far it lets the field drift with a camera of [pixelScaleArcsec]: [arcminutes], but
         * never under [MIN_PIXELS], about the most a dither moves the field. A wide-angle camera's
         * pixels are of about a minute, so its arcminute would recentre after each dither.
         */
        fun limitArcmin(pixelScaleArcsec: Double?): Double = maxOf(arcminutes, (pixelScaleArcsec ?: 0.0) * MIN_PIXELS / ARCSEC_PER_ARCMIN)

        companion object {
            /** N.I.N.A.'s dither reaches 3 × 5 pixels, and a Seestar's grid 17 of its main camera. */
            const val MIN_PIXELS = 20.0
            private const val ARCSEC_PER_ARCMIN = 60.0
        }
    }

    /**
     * Before a light frame, [count] darks with its exposure, gain, offset and binning, behind the
     * wheel's dark filter, when none were taken for them with the sensor within [amount] °C of its
     * temperature now: for the live stack's calibration. N.I.N.A. has none like it.
     */
    @Serializable
    @SerialName("darks_temperature")
    data class DarksAfterTemperature(val amount: Double = 3.0, val count: Int = TONIGHT_CALIBRATION) : Trigger
}
