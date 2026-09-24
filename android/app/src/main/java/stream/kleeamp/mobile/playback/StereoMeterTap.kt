package stream.kleeamp.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import stream.kleeamp.mobile.player.vis.StereoCore
import stream.kleeamp.mobile.player.vis.StereoMetrics

/**
 * Stereo level tap: passes audio through untouched while measuring per-channel
 * level for the meters.
 *
 * Implemented as Media3's stock [TeeAudioProcessor] rather than a hand-rolled
 * passthrough. The previous hand copy wedged ExoPlayer's drain loop on a
 * speed-change flush (output-buffer aliasing plus a swallowed end-of-stream),
 * freezing the position with the player still reporting PLAYING - a speed tap
 * stopped the song and nothing resumed it.
 */
@UnstableApi
class StereoMeterTap(
    private val tee: TeeAudioProcessor = TeeAudioProcessor(MeterSink()),
) : AudioProcessor by tee {

    // flush() overloads carry interface defaults (which throw), so Kotlin's
    // delegation leaves them unforwarded: route them explicitly, like the
    // sink does on every speed change.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun flush() = tee.flush()

    override fun flush(metadata: AudioProcessor.StreamMetadata) = tee.flush(metadata)

    /** Observes tee'd PCM and folds it into meter levels. Never touches playback. */
    private class MeterSink : TeeAudioProcessor.AudioBufferSink {

        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT

        private var sumLeft = 0.0
        private var sumRight = 0.0
        private var peakLeft = 0.0
        private var peakRight = 0.0
        private var frames = 0

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            clearAccumulators()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            // The tee hands over a read-only view; duplicate so probing can
            // set byte order without touching the tee's buffer.
            runCatching { probe(buffer.duplicate().order(ByteOrder.nativeOrder())) }
        }

        private fun probe(buffer: ByteBuffer) {
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
}
