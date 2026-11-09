package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepProcessingWindowPolicyTest {
    @Test fun normalMorningAnalysisIsSevenDays() {
        assertEquals(7, IntelligenceEngine.NORMAL_ANALYSIS_DAYS)
    }

    @Test fun fullHistoryRepairRemainsExplicitAndSeparate() {
        assertTrue(IntelligenceEngine.EFFORT_RESCORE_HISTORY_DAYS > IntelligenceEngine.NORMAL_ANALYSIS_DAYS)
        assertEquals(4000, IntelligenceEngine.EFFORT_RESCORE_HISTORY_DAYS)
    }
}
