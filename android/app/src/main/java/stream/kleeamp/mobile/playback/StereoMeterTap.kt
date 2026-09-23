package stream.kleeamp.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import stream.kleeamp.mobile.player.vis.StereoCore
import stream.kleeamp.mobile.player.vis.StereoMetrics

@UnstableApi
class StereoMeterTap : AudioProcessor {

    private var configured = false
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private var sumLeft = 0.0
    private var sumRight = 0.0
    private var peakLeft = 0.0
    private var peakRight = 0.0
    private var frames = 0

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configured = true
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun isActive(): Boolean = configured

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline drains a passthrough processor by feeding its own
        // output back in. The bytes are already the correct output, and
        // copying a buffer onto itself throws.
        if (inputBuffer === output) return
        runCatching { probe(inputBuffer.duplicate()) }
        val size = inputBuffer.remaining()
        val out = if (output.capacity() < size) {
            ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            output
        }
        out.clear()
        out.put(inputBuffer)
        out.flip()
        output = out
    }

    override fun getOutput(): ByteBuffer = output

    override fun isEnded(): Boolean = false

    override fun queueEndOfStream() = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun flush() {
        output = AudioProcessor.EMPTY_BUFFER
    }

    override fun reset() {
        configured = false
        output = AudioProcessor.EMPTY_BUFFER
        clearAccumulators()
        PlaybackBus.publishStereo(StereoMetrics.silent)
    }

    private fun probe(buffer: ByteBuffer) {
        buffer.order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val samples = buffer.asShortBuffer()
                while (samples.remaining() >= channels) {
                    val left = samples.get().toDouble() / 32768.0
                    val right = if (channels >= 2) samples.get().toDouble() / 32768.0 else left
                    repeat(channels - 2) { samples.get() }
                    accumulate(left, right)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val samples = buffer.asFloatBuffer()
                while (samples.remaining() >= channels) {
                    val left = samples.get().toDouble()
                    val right = if (channels >= 2) samples.get().toDouble() else left
                    repeat(channels - 2) { samples.get() }
                    accumulate(left, right)
                }
            }
            else -> return
        }
        if (frames >= WINDOW) publish()
    }

    private fun accumulate(left: Double, right: Double) {
        sumLeft += left * left
        sumRight += right * right
        peakLeft = maxOf(peakLeft, abs(left))
        peakRight = maxOf(peakRight, abs(right))
        frames++
    }

    private fun publish() {
        PlaybackBus.publishStereo(StereoCore.metrics(sumLeft, peakLeft, sumRight, peakRight, frames))
        clearAccumulators()
    }

    private fun clearAccumulators() {
        sumLeft = 0.0
        sumRight = 0.0
        peakLeft = 0.0
        peakRight = 0.0
        frames = 0
    }

    private companion object {
        const val WINDOW = 1024
    }
}
