package com.therapylocal.app.engine.runner

import com.therapylocal.app.config.MemoryConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** AD-07: the pipeline never forces a speaker count; a hint above 0 is the only way to fix one. */
class DiarizeClusteringTest {
    @Test
    fun theConfiguredHintLeavesTheCountToTheThreshold() {
        assertEquals(0, MemoryConfig.DIARIZE_SPEAKERS_HINT)
    }

    @Test
    fun hintZeroIsNumClustersMinusOneWithTheThreshold() {
        val c = DiarizeRunner.clustering(0)
        assertEquals(-1, c.numClusters)
        assertEquals(DiarizeRunner.CLUSTER_THRESHOLD, c.threshold, 0f)
    }

    @Test
    fun aHintAboveZeroBecomesNumClusters() {
        assertEquals(2, DiarizeRunner.clustering(2).numClusters)
        assertEquals(3, DiarizeRunner.clustering(3).numClusters)
    }
}
