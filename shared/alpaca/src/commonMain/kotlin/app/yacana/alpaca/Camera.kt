// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * A camera: ICameraV3 and V4.
 *
 * This class controls exposures. The image itself is downloaded as ImageBytes by the
 * pixel engine, so that pixels never enter Kotlin's memory.
 */
@Suppress("TooManyFunctions") // One function per member of the ASCOM interface.
class Camera(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.Camera, number) {
    /** What the camera is and can do. It does not change while connected, so read it once. */
    suspend fun info(): CameraInfo {
        val sensorType = ascomEnum<SensorType>(int("sensortype"))
        val colour = sensorType != SensorType.Monochrome
        return CameraInfo(
            sensorName = optional { string("sensorname") }.orEmpty(),
            width = int("cameraxsize"),
            height = int("cameraysize"),
            pixelSizeX = double("pixelsizex"),
            pixelSizeY = double("pixelsizey"),
            sensorType = sensorType,
            bayerOffsetX = if (colour) optional { int("bayeroffsetx") } else null,
            bayerOffsetY = if (colour) optional { int("bayeroffsety") } else null,
            maxBinX = int("maxbinx"),
            maxBinY = int("maxbiny"),
            canAsymmetricBin = bool("canasymmetricbin"),
            gain = control("gains", "gainmin", "gainmax"),
            offset = control("offsets", "offsetmin", "offsetmax"),
            exposureMin = double("exposuremin"),
            exposureMax = double("exposuremax"),
            canAbortExposure = bool("canabortexposure"),
            canStopExposure = bool("canstopexposure"),
            canSetCcdTemperature = bool("cansetccdtemperature"),
            hasCcdTemperature = optional { double("ccdtemperature") } != null,
            canGetCoolerPower = bool("cangetcoolerpower"),
            maxAdu = int("maxadu"),
            readoutModes = optional { array("readoutmodes").map { it.jsonPrimitive.content } }.orEmpty(),
        )
    }

    /** Where the pixel engine downloads the last image as ImageBytes. */
    val imageArrayUrl: String get() = "${client.baseUrl}/api/v1/${type.path}/$number/imagearray"

    suspend fun state(): CameraState = ascomEnum(int("camerastate"))

    suspend fun imageReady(): Boolean = bool("imageready")

    /** Progress of the current exposure, 0–100, or null if the camera does not say. */
    suspend fun percentCompleted(): Int? = optional { int("percentcompleted") }

    /** Starts an exposure of [seconds]. A dark frame has [light] false, which matters only for cameras with a shutter. */
    suspend fun startExposure(seconds: Double, light: Boolean = true) = put("startexposure", "Duration" to seconds, "Light" to light)

    /** Ends the exposure and throws the image away. */
    suspend fun abortExposure() = put("abortexposure")

    /** Ends the exposure early and keeps the image. */
    suspend fun stopExposure() = put("stopexposure")

    /** When the last exposure started, in UTC, or null if the camera does not say. */
    suspend fun lastExposureStartTime(): Instant? = optional { parseUtc(string("lastexposurestarttime")) }

    suspend fun lastExposureDuration(): Double? = optional { double("lastexposureduration") }

    /** Gain in the camera's own units, or an index into [GainControl.Named]. */
    suspend fun gain(): Int = int("gain")

    suspend fun setGain(gain: Int) = put("gain", "Gain" to gain)

    suspend fun offset(): Int = int("offset")

    suspend fun setOffset(offset: Int) = put("offset", "Offset" to offset)

    suspend fun binning(): Binning = Binning(int("binx"), int("biny"))

    /** Set the binning before the subframe: the subframe is in binned pixels. */
    suspend fun setBinning(binning: Binning) {
        put("binx", "BinX" to binning.x)
        put("biny", "BinY" to binning.y)
    }

    suspend fun subframe(): Subframe = Subframe(int("startx"), int("starty"), int("numx"), int("numy"))

    suspend fun setSubframe(subframe: Subframe) {
        put("startx", "StartX" to subframe.x)
        put("starty", "StartY" to subframe.y)
        put("numx", "NumX" to subframe.width)
        put("numy", "NumY" to subframe.height)
    }

    suspend fun readoutMode(): Int = int("readoutmode")

    suspend fun setReadoutMode(index: Int) = put("readoutmode", "ReadoutMode" to index)

    /** Sensor temperature in °C, or null without a sensor ([CameraInfo.hasCcdTemperature]). */
    suspend fun ccdTemperature(): Double? = optional { double("ccdtemperature") }

    suspend fun coolerOn(): Boolean = bool("cooleron")

    suspend fun setCoolerOn(on: Boolean) = put("cooleron", "CoolerOn" to on)

    /** Cooler power, 0–100 %, or null if the camera does not say. */
    suspend fun coolerPower(): Double? = optional { double("coolerpower") }

    /** The cooler's target temperature in °C. */
    suspend fun targetCcdTemperature(): Double = double("setccdtemperature")

    suspend fun setTargetCcdTemperature(celsius: Double) = put("setccdtemperature", "SetCCDTemperature" to celsius)

    /**
     * Gain or offset control (ASCOM): named settings if the list exists and is not empty, else a
     * range if minimum and maximum differ. The Seestar sends an empty gain list with a 0–600 range,
     * and an offset range of 0–0, which means no offset control.
     */
    private suspend fun control(listMember: String, minMember: String, maxMember: String): GainControl {
        val names =
            try {
                array(listMember).map { it.jsonPrimitive.content }
            } catch (e: AlpacaException) {
                // A camera in range mode may answer "invalid operation" instead of "not implemented".
                if (e.errorNumber != AlpacaException.NOT_IMPLEMENTED && e.errorNumber != AlpacaException.INVALID_OPERATION) throw e
                emptyList()
            }
        val min = optional { int(minMember) }
        val max = optional { int(maxMember) }
        return when {
            names.isNotEmpty() -> GainControl.Named(names)
            min != null && max != null && max > min -> GainControl.Range(min, max)
            else -> GainControl.None
        }
    }
}

/** What a camera is and can do. Pixel sizes are in µm and exposures in seconds. */
data class CameraInfo(
    val sensorName: String,
    val width: Int,
    val height: Int,
    val pixelSizeX: Double,
    val pixelSizeY: Double,
    val sensorType: SensorType,
    val bayerOffsetX: Int?,
    val bayerOffsetY: Int?,
    val maxBinX: Int,
    val maxBinY: Int,
    val canAsymmetricBin: Boolean,
    val gain: GainControl,
    val offset: GainControl,
    val exposureMin: Double,
    val exposureMax: Double,
    val canAbortExposure: Boolean,
    val canStopExposure: Boolean,
    val canSetCcdTemperature: Boolean,
    val hasCcdTemperature: Boolean,
    val canGetCoolerPower: Boolean,
    val maxAdu: Int,
    val readoutModes: List<String>,
) {
    /**
     * The Bayer pattern from the top-left pixel, such as `GRBG` for the Seestar ([SensorType.Rggb]
     * with offsets 1 and 0), or null for other sensor types.
     */
    val bayerPattern: String?
        get() {
            if (sensorType != SensorType.Rggb) return null
            val x = bayerOffsetX ?: 0
            val y = bayerOffsetY ?: 0
            return listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).joinToString("") { (column, row) ->
                RGGB[(row + y) % 2][(column + x) % 2].toString()
            }
        }

    private companion object {
        val RGGB = listOf("RG", "GB")
    }
}

/** How gain or offset is set: a range of values, named settings selected by index, or not at all. */
sealed interface GainControl {
    data class Range(val min: Int, val max: Int) : GainControl

    data class Named(val names: List<String>) : GainControl

    data object None : GainControl
}

data class Binning(val x: Int, val y: Int)

/** A region of interest, in binned pixels from the top-left corner. */
data class Subframe(val x: Int, val y: Int, val width: Int, val height: Int)
