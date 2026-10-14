// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.alpaca.CameraInfo
import app.yacana.alpaca.GainControl
import app.yacana.alpaca.SensorType
import app.yacana.domain.equipment.FakeAlpaca
import app.yacana.ports.Bayer
import app.yacana.ports.ColourStretch
import app.yacana.ports.DetectedStar
import app.yacana.ports.EngineBuildInfo
import app.yacana.ports.EngineException
import app.yacana.ports.Frame
import app.yacana.ports.FrameStatistics
import app.yacana.ports.HeaderCard
import app.yacana.ports.HeaderValue
import app.yacana.ports.ImageEngine
import app.yacana.ports.LinearImage
import app.yacana.ports.LiveStack
import app.yacana.ports.MasterFrame
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.StackAddition
import app.yacana.ports.StackInfo
import app.yacana.ports.StarDetection
import app.yacana.ports.Stretch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/** A pixel engine that hands out [TestFrame]s, recording what it was asked. */
internal class FakeEngine : ImageEngine {
    val urls = mutableListOf<String>()
    val networks = mutableListOf<Long?>()
    val bayers = mutableListOf<Bayer?>()
    val frames = mutableListOf<TestFrame>()
    var failure: EngineException? = null

    /** The HFR of the next frame, such as one that depends on the focuser's position. */
    var hfr: () -> Double = { 2.4 }

    /** The mean of the next frame, such as one that depends on the exposure and the light. */
    var mean: () -> Double = { 1000.0 }

    override fun buildInfo() = EngineBuildInfo("test", "jvm")

    override suspend fun download(imageArrayUrl: String, network: Long?, bayer: Bayer?, timeout: Duration): Frame {
        failure?.let { throw it }
        urls += imageArrayUrl
        networks += network
        bayers += bayer
        return TestFrame(hfr(), mean()).also { frames += it }
    }

    /** The stacks handed out, the newest last; none unless [stacking]. */
    val stacks = mutableListOf<FakeLiveStack>()
    var stacking = false

    /** The checkpoints written, by path, as the engine would read them back. */
    val checkpoints = mutableMapOf<String, FakeLiveStack>()

    override fun newStack(): LiveStack? = if (stacking) FakeLiveStack(this).also { stacks += it } else null

    override fun restoreStack(path: String): LiveStack? = checkpoints[path]?.copy()?.also { stacks += it }

    /** The master frames handed out. */
    val masters = mutableListOf<FakeMaster>()

    override fun newMaster(): MasterFrame? = if (stacking) FakeMaster(this).also { masters += it } else null

    override fun jpeg(image: Preview): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
}

/** A master that counts its frames; a flat made of it remembers whether it had a bias. */
internal class FakeMaster(val engine: FakeEngine, var frames: Int = 0, var label: String = "", val bias: Boolean? = null) :
    MasterFrame {
    var closed = false

    override fun add(frame: Frame): Int {
        check(!closed) { "closed" }
        return ++frames
    }

    override fun frames() = frames

    override fun flat(bias: MasterFrame?): MasterFrame = FakeMaster(engine, frames, bias = bias != null).also { engine.masters += it }

    override fun saveFits(path: String, headers: List<HeaderCard>) = Unit

    override fun writeFitsTo(descriptor: Int, headers: List<HeaderCard>) = Unit

    override fun label() = label

    override fun close() {
        closed = true
    }
}

/** A stack that counts frames: it refuses those that [FakeEngine] built with an HFR of [NOT_ALIGNED]. */
internal class FakeLiveStack(private val engine: FakeEngine, var frames: Int = 0, var exposure: Double = 0.0, var label: String = "") :
    LiveStack {
    var closed = false
    val jpegs = mutableListOf<Pair<String, Boolean>>()

    fun copy() = FakeLiveStack(engine, frames, exposure, label)

    /** The masters each frame added was calibrated with. */
    val calibrations = mutableListOf<Pair<MasterFrame?, MasterFrame?>>()

    override fun add(
        frame: Frame,
        stars: List<DetectedStar>,
        exposureSeconds: Double,
        dark: MasterFrame?,
        flat: MasterFrame?,
    ): StackAddition {
        check(!closed && !(frame as TestFrame).closed) { "closed" }
        calibrations += dark to flat
        if (stars.firstOrNull()?.hfr == NOT_ALIGNED) return StackAddition(false, frames, 0.0, 0.0, 0)
        frames++
        exposure += exposureSeconds
        return StackAddition(true, frames, 0.4, 12.0, 90, dark = dark != null, flat = flat != null)
    }

    override fun info() = StackInfo(2160, 3840, frames, exposure, redGain = 0.7, blueGain = 1.1, balanceStars = if (frames > 0) 80 else 0)

    override fun preview(balanced: Boolean, region: Region, width: Int, height: Int) = Preview(width, height, ByteArray(width * height * 4))

    /** A sky of 1000 ADU with a little noise, the same each time. */
    override fun linear(width: Int, height: Int) = LinearImage(width, height, FloatArray(width * height * 3) { 1000f + (it * 7919 % 97) })

    override fun save(path: String, headers: List<HeaderCard>) = Unit

    override fun writeTo(descriptor: Int, headers: List<HeaderCard>) = Unit

    override fun saveJpeg(path: String, balanced: Boolean) {
        jpegs += path to balanced
    }

    override fun writeJpegTo(descriptor: Int, balanced: Boolean) = Unit

    override fun checkpoint(path: String, label: String) {
        this.label = label
        engine.checkpoints[path] = copy()
    }

    override fun label() = label

    override fun close() {
        closed = true
    }
}

/** The HFR that [FakeLiveStack] takes for a frame whose stars do not match. */
internal const val NOT_ALIGNED = 9.75

internal class TestFrame(private val hfr: Double = 2.4, private val mean: Double = 1000.0) : Frame {
    var closed = false
    override val width = 2160
    override val height = 3840
    override val bayer = Bayer.GRBG

    override fun statistics() = FrameStatistics(0, 65535, mean, mean, 10.0, 20.0)

    override fun autoStretch() = ColourStretch.linked(Stretch(0.01f, 0.1f))

    override fun histogram(bins: Int) = LongArray(bins)

    val scales = mutableListOf<Pair<Double?, Double?>>()

    override fun detectStars(pixelSizeUm: Double?, focalLengthMm: Double?): StarDetection {
        scales += pixelSizeUm to focalLengthMm
        return StarDetection(listOf(DetectedStar(10f, 20f, hfr, 1000.0, 9000.0, Region(6.0, 16.0, 9.0, 9.0))), hfr, HFR_ERROR)
    }

    override fun preview(stretch: ColourStretch, region: Region, width: Int, height: Int) =
        Preview(width, height, ByteArray(width * height * 4))

    override fun save(path: String, headers: List<HeaderCard>) = Unit

    override fun writeTo(descriptor: Int, headers: List<HeaderCard>) = Unit

    override fun close() {
        closed = true
    }
}

internal class FakeStore : FrameStore {
    val saves = mutableListOf<Pair<String, List<HeaderCard>>>()

    /** The master flats saved, by path, with their header, as a FITS file would give them back. */
    val masters = mutableMapOf<String, Pair<FakeMaster, List<HeaderCard>>>()

    /** The stacks saved: where, with how many frames, their header and whether balanced. */
    val stackSaves = mutableListOf<StackSave>()

    /** The live mosaics kept, by path. */
    val jpegs = mutableListOf<String>()

    override suspend fun saveJpeg(relativePath: String, jpeg: ByteArray): String {
        jpegs += relativePath
        return relativePath
    }
    override val location = MutableStateFlow("saved")

    override suspend fun save(frame: Frame, relativePath: String, headers: List<HeaderCard>): String {
        saves += relativePath to headers
        return "saved/$relativePath.fits"
    }

    override suspend fun saveStack(stack: LiveStack, relativePath: String, headers: List<HeaderCard>, balanced: Boolean): String {
        stackSaves += StackSave(relativePath, stack.info().frames, headers, balanced)
        return "saved/$relativePath.fits"
    }

    override suspend fun saveMaster(master: MasterFrame, relativePath: String, headers: List<HeaderCard>): String {
        masters[relativePath] = master as FakeMaster to headers
        return "saved/$relativePath.fits"
    }

    override suspend fun readMaster(relativePath: String, read: (descriptor: Int) -> MasterFrame?): MasterFrame? =
        masters[relativePath]?.let { (master, headers) ->
            val date = (headers.firstOrNull { it.key == "DATE-OBS" }?.value as? HeaderValue.Text)?.value.orEmpty()
            FakeMaster(master.engine, master.frames, date, master.bias)
        }
}

internal data class StackSave(val path: String, val frames: Int, val headers: List<HeaderCard>, val balanced: Boolean)

/** The Seestar's telephoto camera, as its driver describes it. */
internal val SEESTAR_CAMERA =
    CameraInfo(
        sensorName = "",
        width = 2160,
        height = 3840,
        pixelSizeX = 2.9,
        pixelSizeY = 2.9,
        sensorType = SensorType.Rggb,
        bayerOffsetX = 1,
        bayerOffsetY = 0,
        maxBinX = 1,
        maxBinY = 1,
        canAsymmetricBin = false,
        gain = GainControl.Range(0, 600),
        offset = GainControl.None,
        exposureMin = 3e-5,
        exposureMax = 2000.0,
        canAbortExposure = true,
        canStopExposure = true,
        canSetCcdTemperature = false,
        hasCcdTemperature = true,
        canGetCoolerPower = false,
        maxAdu = 65535,
        readoutModes = listOf("0"),
    )

/** The spread of the HFR of the fake frames' stars. */
internal const val HFR_ERROR = 0.1

/** The Seestar's telephoto camera as spike S3 found it; an exposure is ready as soon as it starts. */
internal fun FakeAlpaca.seestarCamera() {
    devices += Triple("Camera", 0, "camera-0")
    mapOf(
        "connected" to "false",
        "sensortype" to "2",
        "sensorname" to "\"\"",
        "cameraxsize" to "2160",
        "cameraysize" to "3840",
        "pixelsizex" to "2.9",
        "pixelsizey" to "2.9",
        "bayeroffsetx" to "1",
        "bayeroffsety" to "0",
        "maxbinx" to "1",
        "maxbiny" to "1",
        "canasymmetricbin" to "false",
        "gains" to "[]",
        "gain" to "80",
        "gainmin" to "0",
        "gainmax" to "600",
        "offsetmin" to "0",
        "offsetmax" to "0",
        "exposuremin" to "3e-05",
        "exposuremax" to "2000",
        "canabortexposure" to "true",
        "canstopexposure" to "true",
        "cansetccdtemperature" to "false",
        "ccdtemperature" to "24.6875",
        "cangetcoolerpower" to "false",
        "maxadu" to "65535",
        "readoutmodes" to """["0"]""",
        "camerastate" to "0",
        "imageready" to "false",
    ).forEach { (member, value) -> values["camera/0/$member"] = value }
    onPut = { member, _ -> if (member == "camera/0/startexposure") values["camera/0/imageready"] = "true" }
}

/** The Seestar's filter wheel as spike S3 found it: Dark blocks the light, IR is the usual filter. */
internal fun FakeAlpaca.seestarFilterWheel(position: Int) {
    devices += Triple("FilterWheel", 0, "filterwheel-0")
    values["filterwheel/0/connected"] = "false"
    values["filterwheel/0/names"] = """["Dark","IR","LP"]"""
    values["filterwheel/0/focusoffsets"] = "[0,0,0]"
    values["filterwheel/0/position"] = "$position"
}
