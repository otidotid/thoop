package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class OnlineFirstWindowTest {
    private val today = LocalDate.of(2026, 11, 9)

    @Test fun localWorkingSetIncludesTodayAndPrevious89Days() {
        assertEquals("2026-08-12", OnlineFirstWindow.firstLocalDay(today))
        assertEquals("2026-08-12", OnlineFirstWindow.clampDay("0000-01-01", today))
        assertEquals("2026-10-01", OnlineFirstWindow.clampDay("2026-10-01", today))
    }

    @Test fun timestampReadsUseTheSameLocalBoundary() {
        val expected = LocalDate.of(2026, 8, 12).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        assertEquals(expected, OnlineFirstWindow.firstLocalSecond(today, ZoneOffset.UTC))
    }
}
