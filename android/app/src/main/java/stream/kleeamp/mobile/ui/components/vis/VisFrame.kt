package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import stream.kleeamp.mobile.data.visualizer.ClassicLedCore
import stream.kleeamp.mobile.data.visualizer.ClassicPeakCore
import stream.kleeamp.mobile.data.visualizer.KleeampCore
import stream.kleeamp.mobile.data.visualizer.MeterCore
import stream.kleeamp.mobile.data.visualizer.OmarchyField
import stream.kleeamp.mobile.data.visualizer.StereoCore
import stream.kleeamp.mobile.data.visualizer.StereoMetrics
import stream.kleeamp.mobile.data.visualizer.VisMath
import stream.kleeamp.mobile.data.visualizer.Visualizer
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.ui.components.BrickMeter
import stream.kleeamp.mobile.ui.components.rememberMeter

@Stable
sealed class VisFrame(val columns: Int, val minTickNs: Long) {
    var frame by mutableIntStateOf(0)
        private set

    internal fun bump() { frame++ }

    abstract fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double)

    /**
     * Rest state for pause. Brick settles to the meter floor and stops its
     * loop; every other family does the same here so nothing keeps dancing
     * when the music stops. Style is untouched - each renderer simply draws
     * resting values.
     */
    abstract fun settle()
}

class BarsFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = MeterCore(columns)
    val levels get() = core.levels
    val peaks get() = core.peaks

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        if (bands != null) core.push(bands) else core.pushIdle(t)
    }

    override fun settle() = core.settle()
}

class ClassicPeakFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = ClassicPeakCore(columns)
    val bars get() = core.barPos
    val peaks get() = core.peakPos

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.idleBands(columns, t), dt)
    }

    override fun settle() = core.settle()
}

class ClassicLedFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = ClassicLedCore(columns)
    val body get() = core.body
    val peaks get() = core.peak

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.idleBands(columns, t), dt)
    }

    override fun settle() = core.settle()
}

class StereoFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = StereoCore()
    val levels get() = core.levels
    val peaks get() = core.peaks

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        if (bands != null) core.push(stereo, dt) else core.idle(t)
    }

    override fun settle() = core.settle()
}

class MatrixFrame(columns: Int) : VisFrame(columns, MATRIX_TICK_NS) {
    val energy = FloatArray(columns)

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        val src = bands ?: VisMath.idleBands(columns, t)
        VisMath.resampleAverage(src, columns).copyInto(energy)
    }

    override fun settle() {
        energy.fill(0f)
    }

    private companion object {
        const val MATRIX_TICK_NS = 66_000_000L
    }
}

class ButterflyFrame(columns: Int) : VisFrame(columns, BUTTERFLY_TICK_NS) {
    var bands: FloatArray = FloatArray(columns)
        private set

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        this.bands = (bands ?: VisMath.idleBands(columns, t)).copyOf()
    }

    override fun settle() {
        bands = FloatArray(columns)
    }

    private companion object {
        const val BUTTERFLY_TICK_NS = 66_000_000L
    }
}

class OmarchyFrame(columns: Int) : VisFrame(columns, OMARCHY_TICK_NS) {
    var bands: FloatArray = FloatArray(columns)
        private set

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        this.bands = (bands ?: VisMath.idleBands(columns, t)).copyOf()
    }

    override fun settle() {
        bands = FloatArray(columns)
    }

    private companion object {
        const val OMARCHY_TICK_NS = 50_000_000L
    }
}

class KleeampFrame(columns: Int) : VisFrame(columns, KLEEAMP_TICK_NS) {
    val core = KleeampCore(columns)

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.idleBands(columns, t), dt)
    }

    override fun settle() = core.settle()

    private companion object {
        const val KLEEAMP_TICK_NS = 0L
    }
}

class WaveFrame(columns: Int) : VisFrame(columns, WAVE_TICK_NS) {
    /**
     * Latest raw time-domain samples, read straight off the waveform bus.
     * The FFT cannot produce these, so wave ignores [bands] and follows the
     * waveform tap instead - which also means it works while the FFT is
     * still attaching. The published arrays are never mutated after publish,
     * so the reference can be held without copying.
     */
    var samples: FloatArray = FloatArray(0)
        private set

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        samples = PlaybackBus.waveform.value
    }

    override fun settle() {
        samples = FloatArray(0)
    }

    private companion object {
        const val WAVE_TICK_NS = 33_000_000L
    }
}

@Composable
fun rememberVisFrame(
    mode: Visualizer,
    columns: Int,
    live: Boolean,
    spectrum: State<FloatArray>,
    stereo: State<StereoMetrics>,
): VisFrame {
    val frame = remember(mode, columns) { newVisFrame(mode, columns) }
    LaunchedEffect(frame, live) {
        // Paused settles to the rest state and stops, like Brick: no idle
        // dance when the music stops. Playing with no FFT yet (session
        // attaching) still idles inside the loop until real bands land.
        if (!live) {
            frame.settle()
            frame.bump()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        var last = start
        var lastTick = start - frame.minTickNs
        while (true) {
            withFrameNanos { now ->
                if (now - lastTick < frame.minTickNs) return@withFrameNanos
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                last = now
                lastTick = now
                val src = spectrum.value
                val real = live && src.isNotEmpty()
                frame.tick(if (real) src else null, stereo.value, dt, (now - start) / 1_000_000_000.0)
                frame.bump()
            }
        }
    }
    return frame
}

private fun newVisFrame(mode: Visualizer, columns: Int): VisFrame = when (mode) {
    Visualizer.Bars -> BarsFrame(columns)
    Visualizer.ClassicPeak -> ClassicPeakFrame(columns)
    Visualizer.Matrix -> MatrixFrame(columns)
    Visualizer.Butterfly -> ButterflyFrame(columns)
    Visualizer.ClassicLed -> ClassicLedFrame(columns)
    Visualizer.Stereo -> StereoFrame(columns)
    Visualizer.Omarchy -> OmarchyFrame(columns)
    Visualizer.Kleeamp -> KleeampFrame(columns)
    Visualizer.Wave -> WaveFrame(columns)
    Visualizer.Brick, Visualizer.Widget -> error("brick renders through rememberMeter")
}

@Composable
fun VisualizerMeter(
    mode: Visualizer,
    columns: Int,
    live: Boolean,
    spectrum: State<FloatArray>,
    stereo: State<StereoMetrics>,
    brick: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    if (mode == Visualizer.Brick) {
        val frame = rememberMeter(columns, live, spectrum)
        BrickMeter(frame = frame, modifier = modifier, brick = brick, gap = gap)
    } else {
        val frame = rememberVisFrame(mode, columns, live, spectrum, stereo)
        VisualizerView(frame, modifier)
    }
}

@Composable
fun VisualizerView(frame: VisFrame, modifier: Modifier = Modifier) {
    when (frame) {
        is BarsFrame -> VisBars(frame, modifier)
        is ClassicPeakFrame -> VisClassicPeak(frame, modifier)
        is MatrixFrame -> VisMatrix(frame, modifier)
        is ButterflyFrame -> VisButterfly(frame, modifier)
        is ClassicLedFrame -> VisClassicLed(frame, modifier)
        is StereoFrame -> VisStereo(frame, modifier)
        is OmarchyFrame -> VisOmarchy(frame, modifier)
        is KleeampFrame -> VisKleeamp(frame, modifier)
        is WaveFrame -> VisWave(frame, modifier)
    }
}
