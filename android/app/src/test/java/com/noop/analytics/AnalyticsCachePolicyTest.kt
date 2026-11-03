package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsCachePolicyTest {
    @Test
    fun `normal twenty one day window is preserved`() {
        val days = (1..21).map { "2026-10-${it.toString().padStart(2, '0')}" }

        assertEquals(days.toSet(), AnalyticsCachePolicy.retainedDays(days))
    }

    @Test
    fun `history repair cache keeps only newest bounded days`() {
        val days = (1..100).map { day -> "2026-${day.toString().padStart(3, '0')}" }

        val retained = AnalyticsCachePolicy.retainedDays(days)

        assertEquals(AnalyticsCachePolicy.MAX_RETAINED_DAYS, retained.size)
        assertTrue("2026-100" in retained)
        assertTrue("2026-001" !in retained)
    }

    @Test
    fun `zero limit retains nothing`() {
        assertTrue(AnalyticsCachePolicy.retainedDays(listOf("2026-11-03"), limit = 0).isEmpty())
    }
}
