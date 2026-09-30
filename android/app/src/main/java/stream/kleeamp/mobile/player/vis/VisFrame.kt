package stream.kleeamp.mobile.player.vis

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
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.chrome.BrickMeter
import stream.kleeamp.mobile.chrome.rememberMeter

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
        core.push(bands ?: VisMath.silenceBands(columns), dt)
    }

    override fun settle() = core.settle()
}

class ClassicPeakFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = ClassicPeakCore(columns)
    val bars get() = core.barPos
    val peaks get() = core.peakPos

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.silenceBands(columns), dt)
    }

    override fun settle() = core.settle()
}

class ClassicLedFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = ClassicLedCore(columns)
    val body get() = core.body
    val peaks get() = core.peak

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.silenceBands(columns), dt)
    }

    override fun settle() = core.settle()
}

class StereoFrame(columns: Int) : VisFrame(columns, 0L) {
    private val core = StereoCore()
    val levels get() = core.levels
    val peaks get() = core.peaks

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        if (bands != null) core.push(stereo, dt) else core.silence(dt)
    }

    override fun settle() = core.settle()
}

class MatrixFrame(columns: Int) : VisFrame(columns, MATRIX_TICK_NS) {
    val energy = FloatArray(columns)

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        val src = bands ?: VisMath.silenceBands(columns)
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
        this.bands = (bands ?: VisMath.silenceBands(columns)).copyOf()
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
        this.bands = (bands ?: VisMath.silenceBands(columns)).copyOf()
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
        core.push(bands ?: VisMath.silenceBands(columns), dt)
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
        /**
         * No throttle: cliamp samples + renders waveform modes every
         * TickWave (16 ms, ~60 fps). Throttling to 30 fps here is what
         * made the scope look a beat behind the music.
         */
        const val WAVE_TICK_NS = 0L
    }
}

/**
 * Bands-snapshot holders for the field families: rain, dot/outline/brick
 * bars, columns, pulse, retro and mirror all draw the latest spectrum plus
 * the frame counter, so one shape serves them all and each renderer owns
 * the look.
 */
open class BandsSnapshotFrame(columns: Int, tickNs: Long = 0L) : VisFrame(columns, tickNs) {
    var bands: FloatArray = FloatArray(columns)
        private set

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        this.bands = (bands ?: VisMath.silenceBands(columns)).copyOf()
    }

    override fun settle() {
        bands = FloatArray(columns)
    }
}

class RainFrame(columns: Int) : BandsSnapshotFrame(columns)
class BarsDotFrame(columns: Int) : BandsSnapshotFrame(columns)
class BarsOutlineFrame(columns: Int) : BandsSnapshotFrame(columns)
class BricksFrame(columns: Int) : BandsSnapshotFrame(columns)
class ColumnsFrame(columns: Int) : BandsSnapshotFrame(columns)
class PulseFrame(columns: Int) : BandsSnapshotFrame(columns)
class RetroFrame(columns: Int) : BandsSnapshotFrame(columns)
class MirrorFrame(columns: Int) : BandsSnapshotFrame(columns)
class ScatterFrame(columns: Int) : BandsSnapshotFrame(columns)
class SakuraFrame(columns: Int) : BandsSnapshotFrame(columns)
class FireworkFrame(columns: Int) : BandsSnapshotFrame(columns)
class BubblesFrame(columns: Int) : BandsSnapshotFrame(columns)
class FireflyFrame(columns: Int) : BandsSnapshotFrame(columns)
class BinaryFrame(columns: Int) : BandsSnapshotFrame(columns, BINARY_TICK_NS) {

    private companion object {
        /**
         * cliamp's binary is a render-only driver at TickFast (50 ms) while
         * playing; the scroll speed is counted in ticks.
         */
        const val BINARY_TICK_NS = 50_000_000L
    }
}

class FlameFrame(columns: Int) : VisFrame(columns, 0L) {
    val core = FlameCore(48, 28)

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.silenceBands(columns))
    }

    override fun settle() = core.settle()
}

class SandFrame(columns: Int) : VisFrame(columns, SAND_TICK_NS) {
    val core = SandCore()

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.silenceBands(columns), frame.toLong())
    }

    override fun settle() = core.settle()

    private companion object {
        /**
         * cliamp ticks sand at TickFast (50 ms) while playing; the pour,
         * fall and explosion rates are tuned to that cadence.
         */
        const val SAND_TICK_NS = 50_000_000L
    }
}

class GeyserFrame(columns: Int) : VisFrame(columns, 0L) {
    val core = GeyserCore()

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.silenceBands(columns))
    }

    override fun settle() = core.settle()
}

class LogoFrame(columns: Int) : BandsSnapshotFrame(columns)
class AsciiFrame(columns: Int) : BandsSnapshotFrame(columns)

class TerrainFrame(columns: Int) : VisFrame(columns, 0L) {
    val core = TerrainCore(64)

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        val src = bands ?: VisMath.silenceBands(columns)
        var env = 0f
        for (b in src) env += b
        core.push(if (src.isEmpty()) 0f else env / src.size)
    }

    override fun settle() = core.settle()
}

class MosaicFrame(columns: Int) : VisFrame(columns, 0L) {
    val core = MosaicCore(10, 18)

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        core.push(bands ?: VisMath.silenceBands(columns))
    }

    override fun settle() = core.settle()
}

/**
 * Time-domain sample holders for the trace families: scope draws Lissajous
 * figures and heartbeat an ECG from the same waveform tap wave uses.
 */
open class SamplesFrame(columns: Int) : VisFrame(columns, 0L) {
    var samples: FloatArray = FloatArray(0)
        private set

    override fun tick(bands: FloatArray?, stereo: StereoMetrics, dt: Float, t: Double) {
        samples = PlaybackBus.waveform.value
    }

    override fun settle() {
        samples = FloatArray(0)
    }
}

class ScopeFrame(columns: Int) : SamplesFrame(columns)
class HeartbeatFrame(columns: Int) : SamplesFrame(columns)
class RedSectorFrame(columns: Int) : BandsSnapshotFrame(columns)

@Composable
fun rememberVisFrame(
    mode: Visualizer,
    columns: Int,
    live: Boolean,
    spectrum: State<FloatArray>? = null,
    stereo: State<StereoMetrics>? = null,
    spectrumProvider: (() -> FloatArray?)? = null,
    stereoProvider: (() -> StereoMetrics?)? = null,
    generation: Int = 0,
): VisFrame {
    val frame = remember(mode, columns) { newVisFrame(mode, columns) }
    LaunchedEffect(frame, generation) { frame.settle(); frame.bump() }
    LaunchedEffect(frame, live) {
        // Paused settles to the rest state and stops: nothing dances when the
        // music stops. Playing with no PCM yet feeds silence until real bands
        // land — silence in, silence out, never a synthetic dance.
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
                val src = spectrumProvider?.invoke() ?: spectrum?.value
                val real = live && src != null && src.isNotEmpty()
                val metrics = stereoProvider?.invoke() ?: stereo?.value ?: StereoMetrics.silent
                frame.tick(if (real) src else null, metrics, dt, (now - start) / 1_000_000_000.0)
                frame.bump()
            }
        }
    }
    return frame
}

private val visFrameFactories: Map<Visualizer, (Int) -> VisFrame> = mapOf(
    Visualizer.Bars to ::BarsFrame,
    Visualizer.ClassicPeak to ::ClassicPeakFrame,
    Visualizer.Matrix to ::MatrixFrame,
    Visualizer.Butterfly to ::ButterflyFrame,
    Visualizer.ClassicLed to ::ClassicLedFrame,
    Visualizer.Stereo to ::StereoFrame,
    Visualizer.Omarchy to ::OmarchyFrame,
    Visualizer.Kleeamp to ::KleeampFrame,
    Visualizer.Wave to ::WaveFrame,
    Visualizer.Rain to ::RainFrame,
    Visualizer.BarsDot to ::BarsDotFrame,
    Visualizer.BarsOutline to ::BarsOutlineFrame,
    Visualizer.Bricks to ::BricksFrame,
    Visualizer.Columns to ::ColumnsFrame,
    Visualizer.Pulse to ::PulseFrame,
    Visualizer.Retro to ::RetroFrame,
    Visualizer.Mirror to ::MirrorFrame,
    Visualizer.Scatter to ::ScatterFrame,
    Visualizer.Flame to ::FlameFrame,
    Visualizer.Sakura to ::SakuraFrame,
    Visualizer.Firework to ::FireworkFrame,
    Visualizer.Bubbles to ::BubblesFrame,
    Visualizer.Sand to ::SandFrame,
    Visualizer.Geyser to ::GeyserFrame,
    Visualizer.Firefly to ::FireflyFrame,
    Visualizer.Binary to ::BinaryFrame,
    Visualizer.Logo to ::LogoFrame,
    Visualizer.Terrain to ::TerrainFrame,
    Visualizer.Scope to ::ScopeFrame,
    Visualizer.Heartbeat to ::HeartbeatFrame,
    Visualizer.Ascii to ::AsciiFrame,
    Visualizer.Mosaic to ::MosaicFrame,
    Visualizer.RedSector to ::RedSectorFrame,
)

private fun newVisFrame(mode: Visualizer, columns: Int): VisFrame =
    visFrameFactories[mode]?.invoke(columns)
        ?: error("brick renders through rememberMeter")

@Composable
fun VisualizerMeter(
    mode: Visualizer,
    columns: Int,
    live: Boolean,
    spectrum: State<FloatArray>? = null,
    stereo: State<StereoMetrics>? = null,
    brick: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    spectrumProvider: (() -> FloatArray?)? = null,
    stereoProvider: (() -> StereoMetrics?)? = null,
    generation: Int = 0,
) {
    if (mode == Visualizer.Brick) {
        val frame = rememberMeter(columns, live, spectrum, spectrumProvider, generation)
        BrickMeter(frame = frame, modifier = modifier, brick = brick, gap = gap)
    } else {
        val frame = rememberVisFrame(
            mode, columns, live, spectrum, stereo,
            spectrumProvider, stereoProvider, generation,
        )
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
        is RainFrame -> VisRain(frame, modifier)
        is BarsDotFrame -> VisBarsDot(frame, modifier)
        is BarsOutlineFrame -> VisBarsOutline(frame, modifier)
        is BricksFrame -> VisBricks(frame, modifier)
        is ColumnsFrame -> VisColumns(frame, modifier)
        is PulseFrame -> VisPulse(frame, modifier)
        is RetroFrame -> VisRetro(frame, modifier)
        is MirrorFrame -> VisMirror(frame, modifier)
        is ScatterFrame -> VisScatter(frame, modifier)
        is FlameFrame -> VisFlame(frame, modifier)
        is SakuraFrame -> VisSakura(frame, modifier)
        is FireworkFrame -> VisFirework(frame, modifier)
        is BubblesFrame -> VisBubbles(frame, modifier)
        is SandFrame -> VisSand(frame, modifier)
        is GeyserFrame -> VisGeyser(frame, modifier)
        is FireflyFrame -> VisFirefly(frame, modifier)
        is BinaryFrame -> VisBinary(frame, modifier)
        is LogoFrame -> VisLogo(frame, modifier)
        is TerrainFrame -> VisTerrain(frame, modifier)
        is ScopeFrame -> VisScope(frame, modifier)
        is HeartbeatFrame -> VisHeartbeat(frame, modifier)
        is AsciiFrame -> VisAscii(frame, modifier)
        is MosaicFrame -> VisMosaic(frame, modifier)
        is RedSectorFrame -> VisRedSector(frame, modifier)
        is SamplesFrame -> error("trace frames dispatch by leaf")
        is BandsSnapshotFrame -> error("field frames dispatch by leaf")
    }
}
