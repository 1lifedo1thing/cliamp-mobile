package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoCoreTest {

    @Test
    fun dbLevelMapsFloorToZeroAndFullScaleToOne() {
        assertEquals(0f, StereoCore.dbLevel(0.0), 1e-6f)
        assertEquals(0f, StereoCore.dbLevel(0.003), 1e-3f)
        assertEquals(1f, StereoCore.dbLevel(1.0), 1e-6f)
        val mid = StereoCore.dbLevel(0.063)
        assertEquals(0.5f, mid, 0.01f)
    }

    @Test
    fun metricsFoldRmsAndPeakPerChannel() {
        val metric = StereoCore.metrics(
            sumSquaresLeft = 1.0,
            peakLeft = 1.0,
            sumSquaresRight = 0.0,
            peakRight = 0.0,
            frames = 4,
        )
        assertEquals(0.8746f, metric.leftLevel, 0.01f)
        assertEquals(1f, metric.leftPeak, 1e-6f)
        assertEquals(0f, metric.rightLevel, 1e-6f)
    }

    @Test
    fun silentInputDecaysToZero() {
        val core = StereoCore()
        core.push(StereoMetrics(1f, 1f, 1f, 1f), 1f / 60f)
        repeat(240) { core.push(StereoMetrics.silent, 1f / 60f) }
        assertEquals(0f, core.levels[0], 0.01f)
        assertEquals(0f, core.levels[1], 0.01f)
        assertEquals(0f, core.peaks[0], 0.01f)
        assertEquals(0f, core.peaks[1], 0.01f)
    }
}
