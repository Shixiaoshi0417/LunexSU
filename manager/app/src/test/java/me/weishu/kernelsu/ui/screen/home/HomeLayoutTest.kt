package me.weishu.kernelsu.ui.screen.home

import me.weishu.kernelsu.data.CpuFrequency
import me.weishu.kernelsu.data.model.HomeLayoutStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun everySupportedTokenRoundTrips() {
        assertEquals(5, HomeLayoutStyle.supported.size)
        for (token in HomeLayoutStyle.supported) {
            assertEquals(token, HomeLayoutStyle.fromValue(token))
        }
    }

    @Test
    fun unknownTokensFallBackToCircle() {
        assertEquals(HomeLayoutStyle.CIRCLE, HomeLayoutStyle.fromValue(null))
        assertEquals(HomeLayoutStyle.CIRCLE, HomeLayoutStyle.fromValue(""))
        assertEquals(HomeLayoutStyle.CIRCLE, HomeLayoutStyle.fromValue("wallpaper"))
    }

    @Test
    fun onlyFocusAndStatsPollMetrics() {
        assertTrue(HomeLayoutStyle.usesMetrics(HomeLayoutStyle.FOCUS))
        assertTrue(HomeLayoutStyle.usesMetrics(HomeLayoutStyle.STATS))
        assertFalse(HomeLayoutStyle.usesMetrics(HomeLayoutStyle.CIRCLE))
        assertFalse(HomeLayoutStyle.usesMetrics(HomeLayoutStyle.GRID))
        assertFalse(HomeLayoutStyle.usesMetrics(HomeLayoutStyle.DASHBOARD))
    }

    @Test
    fun statsHeaderDefaultsToStatusCard() {
        assertEquals(HomeLayoutStyle.STATS_TOP_GRID, HomeLayoutStyle.statsTopFromValue("grid"))
        assertEquals(HomeLayoutStyle.STATS_TOP_STATUS, HomeLayoutStyle.statsTopFromValue(null))
        assertEquals(HomeLayoutStyle.STATS_TOP_STATUS, HomeLayoutStyle.statsTopFromValue("circle"))
    }

    @Test
    fun coresWithTheSameCeilingFormOneCluster() {
        val clusters = cpuClusters(
            listOf(
                CpuFrequency(coreIndex = 0, currentFreqKHz = 1_000_000, maxFreqKHz = 2_000_000),
                CpuFrequency(coreIndex = 1, currentFreqKHz = 500_000, maxFreqKHz = 2_000_000),
                CpuFrequency(coreIndex = 2, currentFreqKHz = 3_000_000, maxFreqKHz = 3_000_000),
            )
        )
        assertEquals(2, clusters.size)
        assertEquals("CPU0-1", clusters[0].label)
        assertEquals(750_000L, clusters[0].averageFreqKHz)
        assertEquals(0.375f, clusters[0].usedFraction, 0.0001f)
        assertEquals("CPU2", clusters[1].label)
        assertEquals(1f, clusters[1].usedFraction, 0.0001f)
    }

    @Test
    fun frequenciesUseTheReadableUnit() {
        assertEquals("2.40 GHz", formatFrequency(2_400_000))
        assertEquals("850 MHz", formatFrequency(850_000))
        assertEquals("900 kHz", formatFrequency(900))
    }
}
