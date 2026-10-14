// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.engine

import app.yacana.engine.ffi.BayerPattern
import app.yacana.engine.ffi.Image
import app.yacana.engine.ffi.StackStar
import app.yacana.engine.ffi.StarSensitivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import app.yacana.engine.ffi.ColourStretch as FfiColourStretch
import app.yacana.engine.ffi.EngineException as FfiEngineException
import app.yacana.engine.ffi.HeaderCard as FfiHeaderCard
import app.yacana.engine.ffi.HeaderValue as FfiHeaderValue
import app.yacana.engine.ffi.LiveStack as FfiLiveStack
import app.yacana.engine.ffi.MasterFrame as FfiMasterFrame
import app.yacana.engine.ffi.Region as FfiRegion
import app.yacana.engine.ffi.Stretch as FfiStretch
import app.yacana.engine.ffi.buildInfo as ffiBuildInfo
import app.yacana.engine.ffi.downloadImage as ffiDownloadImage
import app.yacana.engine.ffi.encodeJpeg as ffiEncodeJpeg

/** [ImageEngine] backed by the Rust crate in engine/, through the UniFFI bindings. */
class RustImageEngine : ImageEngine {
    override fun buildInfo(): EngineBuildInfo = ffiBuildInfo().let { EngineBuildInfo(version = it.version, arch = it.arch) }

    override suspend fun download(imageArrayUrl: String, network: Long?, bayer: Bayer?, timeout: Duration): Frame =
        withContext(Dispatchers.IO) {
            try {
                RustFrame(ffiDownloadImage(imageArrayUrl, network?.toULong(), bayer?.toFfi(), timeout.inWholeMilliseconds.toUInt()))
            } catch (e: FfiEngineException) {
                throw EngineException(e.describe(), e)
            }
        }

    override fun newStack(): LiveStack = RustStack(FfiLiveStack())

    override fun restoreStack(path: String): LiveStack? = try {
        RustStack(FfiLiveStack.restore(path))
    } catch (_: FfiEngineException) {
        null
    }

    override fun newMaster(): MasterFrame = RustMaster(FfiMasterFrame())

    override fun readMasterFits(descriptor: Int): MasterFrame? = try {
        RustMaster(FfiMasterFrame.readFitsFromDescriptor(descriptor))
    } catch (_: FfiEngineException) {
        null
    }

    override fun jpeg(image: Preview): ByteArray = translated { ffiEncodeJpeg(image.width.toUInt(), image.height.toUInt(), image.rgba) }
}

/** A stack in the Rust engine; frames it adds must be the engine's own. */
private class RustStack(private val stack: FfiLiveStack) : LiveStack {
    override fun add(
        frame: Frame,
        stars: List<DetectedStar>,
        exposureSeconds: Double,
        dark: MasterFrame?,
        flat: MasterFrame?,
    ): StackAddition {
        val image = (frame as? RustFrame)?.image ?: return StackAddition(false, info().frames, 0.0, 0.0, 0)
        val points = stars.map { StackStar(it.x, it.y, it.averageBrightness, it.hfr) }
        return stack.add(image, points, exposureSeconds, (dark as? RustMaster)?.master, (flat as? RustMaster)?.master).let {
            StackAddition(it.added, it.frames.toInt(), it.rotationDeg, it.shiftPx, it.matchedStars.toInt(), it.dark, it.flat)
        }
    }

    override fun info(): StackInfo = stack.info().let {
        StackInfo(
            it.width.toInt(),
            it.height.toInt(),
            it.frames.toInt(),
            it.exposureSeconds,
            it.redGain,
            it.greenGain,
            it.blueGain,
            it.balanceStars.toInt(),
        )
    }

    override fun preview(balanced: Boolean, region: Region, width: Int, height: Int): Preview = stack
        .preview(balanced, FfiRegion(region.x, region.y, region.width, region.height), width.toUInt(), height.toUInt())
        .let { Preview(it.width.toInt(), it.height.toInt(), it.rgba) }

    override fun linear(width: Int, height: Int): LinearImage = stack.linear(width.toUInt(), height.toUInt()).let {
        val floats = FloatArray(it.values.size / FLOAT_BYTES)
        ByteBuffer.wrap(it.values).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        LinearImage(it.width.toInt(), it.height.toInt(), floats)
    }

    override fun save(path: String, headers: List<HeaderCard>) = translated { stack.save(path, headers.toFfi()) }

    override fun writeTo(descriptor: Int, headers: List<HeaderCard>) = translated { stack.writeToDescriptor(descriptor, headers.toFfi()) }

    override fun saveJpeg(path: String, balanced: Boolean) = translated { stack.saveJpeg(path, balanced) }

    override fun writeJpegTo(descriptor: Int, balanced: Boolean) = translated { stack.writeJpegToDescriptor(descriptor, balanced) }

    override fun checkpoint(path: String, label: String) = translated { stack.checkpoint(path, label) }

    override fun label(): String = stack.label()

    override fun close() = stack.close()
}

/** A master frame in the Rust engine; frames it adds must be the engine's own. */
private class RustMaster(val master: FfiMasterFrame) : MasterFrame {
    override fun add(frame: Frame): Int = (frame as? RustFrame)?.image?.let { master.add(it).toInt() } ?: frames()

    override fun frames(): Int = master.frames().toInt()

    override fun flat(bias: MasterFrame?): MasterFrame = translated { RustMaster(master.flat((bias as? RustMaster)?.master)) }

    override fun saveFits(path: String, headers: List<HeaderCard>) = translated { master.saveFits(path, headers.toFfi()) }

    override fun writeFitsTo(descriptor: Int, headers: List<HeaderCard>) = translated {
        master.writeFitsToDescriptor(descriptor, headers.toFfi())
    }

    override fun label(): String = master.label()

    override fun close() = master.close()
}

private class RustFrame(val image: Image) : Frame {
    override val width = image.width().toInt()
    override val height = image.height().toInt()
    override val bayer = image.bayer()?.toPort()

    override fun statistics(): FrameStatistics = image.statistics().let {
        FrameStatistics(it.min.toInt(), it.max.toInt(), it.mean, it.median, it.mad, it.stdDev)
    }

    override fun autoStretch(): ColourStretch = image.autoStretch().let {
        ColourStretch(it.red.toPort(), it.green.toPort(), it.blue.toPort())
    }

    override fun histogram(bins: Int): LongArray = image.histogram(bins.toUInt()).map { it.toLong() }.toLongArray()

    // NINA's default sensitivity.
    override fun detectStars(pixelSizeUm: Double?, focalLengthMm: Double?): StarDetection =
        image.detectStars(StarSensitivity.HIGH, pixelSizeUm, focalLengthMm).let { result ->
            StarDetection(
                stars =
                result.stars.map {
                    DetectedStar(
                        x = it.x,
                        y = it.y,
                        hfr = it.hfr,
                        background = it.background,
                        maxBrightness = it.maxBrightness,
                        box = Region(it.boxX.toDouble(), it.boxY.toDouble(), it.boxWidth.toDouble(), it.boxHeight.toDouble()),
                        averageBrightness = it.averageBrightness,
                        fwhm = it.fwhm,
                    )
                },
                averageHfr = result.averageHfr,
                hfrStdDev = result.hfrStdDev,
                fwhm = result.fwhm,
            )
        }

    override fun preview(stretch: ColourStretch, region: Region, width: Int, height: Int): Preview = image
        .preview(
            FfiColourStretch(stretch.red.toFfi(), stretch.green.toFfi(), stretch.blue.toFfi()),
            FfiRegion(region.x, region.y, region.width, region.height),
            width.toUInt(),
            height.toUInt(),
        ).let { Preview(it.width.toInt(), it.height.toInt(), it.rgba) }

    override fun save(path: String, headers: List<HeaderCard>) = translated { image.save(path, headers.toFfi()) }

    override fun writeTo(descriptor: Int, headers: List<HeaderCard>) = translated { image.writeToDescriptor(descriptor, headers.toFfi()) }

    override fun close() = image.close()
}

private inline fun <T> translated(block: () -> T): T = try {
    block()
} catch (e: FfiEngineException) {
    throw EngineException(e.describe(), e)
}

private fun List<HeaderCard>.toFfi() = map { card ->
    val value =
        when (val v = card.value) {
            is HeaderValue.Text -> FfiHeaderValue.Text(v.value)
            is HeaderValue.Integer -> FfiHeaderValue.Integer(v.value)
            is HeaderValue.Real -> FfiHeaderValue.Real(v.value)
            is HeaderValue.Logical -> FfiHeaderValue.Logical(v.value)
        }
    FfiHeaderCard(card.key, value, card.comment)
}

private fun FfiStretch.toPort() = Stretch(shadows, midtones, highlights)

private fun Stretch.toFfi() = FfiStretch(shadows, midtones, highlights)

private fun Bayer.toFfi(): BayerPattern = when (this) {
    Bayer.RGGB -> BayerPattern.RGGB
    Bayer.GRBG -> BayerPattern.GRBG
    Bayer.GBRG -> BayerPattern.GBRG
    Bayer.BGGR -> BayerPattern.BGGR
}

private fun BayerPattern.toPort(): Bayer = when (this) {
    BayerPattern.RGGB -> Bayer.RGGB
    BayerPattern.GRBG -> Bayer.GRBG
    BayerPattern.GBRG -> Bayer.GBRG
    BayerPattern.BGGR -> Bayer.BGGR
}

private fun FfiEngineException.describe(): String = when (this) {
    is FfiEngineException.Network -> "Network: $reason"
    is FfiEngineException.Http -> "HTTP $status: $reason"
    is FfiEngineException.Alpaca -> "Alpaca error 0x${number.toString(HEX).uppercase()}: $reason"
    is FfiEngineException.Format -> "Not a readable image: $reason"
    is FfiEngineException.File -> "Could not write the file: $reason"
}

private const val HEX = 16

/** The bytes of each of the engine's 32-bit floats. */
private const val FLOAT_BYTES = 4
