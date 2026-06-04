package com.example.learning

import com.example.learning.repos.TransitMode
import com.example.learning.repos.transitModeOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour of [transitModeOf]. A pure function mapping a GTFS `route_type` to a [TransitMode], so no
 * fakes or coroutines are needed. TfNSW uses both the standard GTFS values (2 = rail, 3 = bus) and the
 * extended ranges (4xx = rail, 7xx = bus), so both are covered here.
 */
class TransitModeOfTest {
    @Test
    fun `standard rail and bus route types map to train and bus`() {
        assertEquals(TransitMode.TRAIN, transitModeOf(2))
        assertEquals(TransitMode.BUS, transitModeOf(3))
    }

    @Test
    fun `tfnsw extended rail and bus ranges map to train and bus`() {
        assertEquals(TransitMode.TRAIN, transitModeOf(401))
        assertEquals(TransitMode.BUS, transitModeOf(712))
    }

    @Test
    fun `an unrecognised route type falls back to other`() {
        assertEquals(TransitMode.OTHER, transitModeOf(0))   // 0 = tram/light rail, not yet modelled
        assertEquals(TransitMode.OTHER, transitModeOf(4))   // 4 = ferry
    }
}
