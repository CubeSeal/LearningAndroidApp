package com.example.learning

import com.example.learning.repos.parseGtfsDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Behaviour of [parseGtfsDateTime]. A pure function, so no fakes or coroutines are needed — this is
 * the smallest possible first test and confirms the unit-test toolchain runs end to end.
 */
class ParseGtfsDateTimeTest {
    private val serviceDate = LocalDate.of(2026, 6, 1)

    @Test
    fun `time within the day resolves on the service date`() {
        val result = parseGtfsDateTime(serviceDate, "08:30:00")
        assertEquals(LocalDate.of(2026, 6, 1).atTime(8, 30), result)
    }

    @Test
    fun `time past midnight rolls into the next calendar day`() {
        // GTFS encodes a 1:30am after-midnight service as 25:30:00 on the *prior* service date.
        val result = parseGtfsDateTime(serviceDate, "25:30:00")
        assertEquals(LocalDate.of(2026, 6, 2).atTime(1, 30), result)
    }
}
