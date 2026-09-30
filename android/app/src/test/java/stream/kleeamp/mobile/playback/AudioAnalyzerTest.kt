package stream.kleeamp.mobile.playback

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.player.vis.StereoCore

/**
 * Analyzer contract — mirrors CLIamp's Analyze semantics:
 * real FFT from decoded PCM, 64 normalized bands, silence handling,
 * stereo L/R independence. No timers, no sine-animated bars: the sine
 * waves here are *inputs* (synthetic PCM fed to the analyzer), and the
 * assertions prove the output tracks the input frequency/level.
 */
class AudioAnalyzerTest {

    private fun sine(freqHz: Double, sampleRate: Int, n: Int, amp: Float = 0.8f): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (sin(2 * PI * freqHz * i / sampleRate) * amp).toFloat()
        return out
    }

    @Test
    fun silenceStaysSilent() {
        val analyzer = AudioAnalyzer()
        val out = analyzer.analyzeSamples(FloatArray(4096), 4096, 44_100)
        for (v in out) assertEquals(0f, v, 1e-6f)
    }

    @Test
    fun emptyInputDecaysToSilence() {
        val analyzer = AudioAnalyzer()
        analyzer.analyzeSamples(sine(440.0, 44_100, 4096), 4096, 44_100)
        repeat(10) { analyzer.analyzeSamples(FloatArray(0), 0, 44_100) }
        for (v in analyzer.analyzeSamples(FloatArray(0), 0, 44_100)) {
            assertTrue("decayed band $v not near zero", v < 0.2f)
        }
    }

    @Test
    fun sineAppearsInTheCorrectBand() {
        val analyzer = AudioAnalyzer()
        // 440 Hz over 64 log bands 20–20k:
        // 64 * (log10(440)-log10(20)) / (log10(20000)-log10(20)) ≈ 28.6.
        val out = analyzer.analyzeSamples(sine(440.0, 44_100, 4096), 4096, 44_100)
        assertEquals(64, out.size)
        for (v in out) assertTrue("band $v out of range", v in 0f..1f)
        val peak = out.indices.maxBy { out[it] }
        assertTrue("440 Hz peaked at band $peak, expected 24..33", peak in 24..33)
        assertTrue("peak ${out[peak]} too weak", out[peak] > 0.15f)
    }

    @Test
    fun bassAndTrebleSeparate() {
        val analyzer = AudioAnalyzer()
        val bass = analyzer.analyzeSamples(sine(80.0, 44_100, 4096), 4096, 44_100)
        val bassPeak = bass.indices.maxBy { bass[it] }
        val analyzer2 = AudioAnalyzer()
        val treble = analyzer2.analyzeSamples(sine(8000.0, 44_100, 4096), 4096, 44_100)
        val treblePeak = treble.indices.maxBy { treble[it] }
        assertTrue("bass peak $bassPeak not low", bassPeak < 12)
        assertTrue("treble peak $treblePeak not high", treblePeak > 40)
        assertTrue("peaks overlap: $bassPeak vs $treblePeak", treblePeak - bassPeak > 15)
    }

    @Test
    fun broadbandNoiseLightsManyBands() {
        val analyzer = AudioAnalyzer()
        val noise = FloatArray(4096) { (Math.random() * 2 - 1).toFloat() * 0.5f }
        val out = analyzer.analyzeSamples(noise, 4096, 44_100)
        assertTrue("noise lit ${out.count { it > 0.05f }} bands, want >20", out.count { it > 0.05f } > 20)
    }

    @Test
    fun stereoLeftOnlyDrivesLeft() {
        // 1024 full-scale left frames, silent right.
        val m = StereoCore.metrics(
            sumSquaresLeft = 1024 * 0.5, peakLeft = 1.0,
            sumSquaresRight = 0.0, peakRight = 0.0, frames = 1024,
        )
        assertTrue(m.leftLevel > 0.5f)
        assertEquals(0f, m.rightLevel, 1e-6f)
        assertTrue(m.leftPeak > 0.9f)
        assertEquals(0f, m.rightPeak, 1e-6f)
    }

    @Test
    fun stereoRightOnlyDrivesRight() {
        val m = StereoCore.metrics(
            sumSquaresLeft = 0.0, peakLeft = 0.0,
            sumSquaresRight = 1024 * 0.5, peakRight = 1.0, frames = 1024,
        )
        assertEquals(0f, m.leftLevel, 1e-6f)
        assertTrue(m.rightLevel > 0.5f)
    }

    @Test
    fun stereoDbFloorMatchesCliamp() {
        // CLIamp stereoFloorDB = -48: full scale → 1, silence → 0.
        assertEquals(1f, StereoCore.dbLevel(1.0), 1e-4f)
        assertEquals(0f, StereoCore.dbLevel(0.0), 0f)
        // -24 dB amplitude (~0.063) → 0.5 normalized.
        assertEquals(0.5f, StereoCore.dbLevel(0.0631), 0.02f)
    }

    @Test
    fun waveWindowHoldsWithoutNewPcm() {
        // Stall behavior: no new writes between ticks must not run the
        // cursor past written or invent motion — the trace holds.
        PcmRing.clear()
        PcmRing.onFormat(44_100, 1)
        val analyzer = AudioAnalyzer()
        PcmRing.writeMono(FloatArray(2048) { 0.5f }, 2048)
        analyzer.publishWave()
        val first = PlaybackBus.waveform.value.copyOf()
        analyzer.publishWave()
        val second = PlaybackBus.waveform.value
        assertTrue("wave moved with no new pcm", first.contentEquals(second))
        PcmRing.clear()
    }

    @Test
    fun waveWindowFollowsNewPcm() {
        PcmRing.clear()
        PcmRing.onFormat(44_100, 1)
        val analyzer = AudioAnalyzer()
        PcmRing.writeMono(FloatArray(2048), 2048)
        analyzer.publishWave()
        PcmRing.writeMono(FloatArray(2048) { 0.9f }, 2048)
        // Let the wall-clock cursor slide a full window forward (>23 ms).
        Thread.sleep(60)
        analyzer.publishWave()
        val wave = PlaybackBus.waveform.value
        assertTrue("wave ignores fresh pcm, last=${wave.last()}", wave.last() > 0.5f)
        PcmRing.clear()
    }

    @Test
    fun fftIsRealNotFaked() {
        // Two different inputs must peak in different bands; a fake
        // (timer/sine/random bar animator) would not track the input.
        val analyzer = AudioAnalyzer()
        val a = analyzer.analyzeSamples(sine(200.0, 44_100, 4096), 4096, 44_100).copyOf()
        val analyzer2 = AudioAnalyzer()
        val b = analyzer2.analyzeSamples(sine(5000.0, 44_100, 4096), 4096, 44_100).copyOf()
        val peakA = a.indices.maxBy { a[it] }
        val peakB = b.indices.maxBy { b[it] }
        assertTrue("peaks overlap: $peakA vs $peakB", kotlin.math.abs(peakA - peakB) > 10)
    }
}
