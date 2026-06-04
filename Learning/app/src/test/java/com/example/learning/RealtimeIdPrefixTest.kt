package com.example.learning

import com.example.learning.repos.RealtimeBusTripInfo
import com.example.learning.repos.withIdPrefix
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * Behaviour of [withIdPrefix]. A pure transform that namespaces a realtime record's ids so a feed
 * served under a prefix (e.g. Sydney Trains, "T:") lines up with the prefixed static ids it's joined
 * against in [com.example.learning.BusInfo]. No fakes/coroutines needed.
 */
class RealtimeIdPrefixTest {
    private val record = RealtimeBusTripInfo(
        id = "e1",
        tripId = "t1",
        updatedAt = LocalDateTime.of(2026, 6, 1, 12, 0),
        stopTimeDelays = listOf("s1" to 120, "s2" to null),
        vehicleLicencePlate = "ABC123",
    )

    @Test
    fun `an empty prefix leaves the record unchanged`() {
        assertEquals(record, record.withIdPrefix(""))
    }

    @Test
    fun `a feed prefix is applied to the trip id and every stop id`() {
        val prefixed = record.withIdPrefix("T:")
        assertEquals("T:t1", prefixed.tripId)
        assertEquals(listOf("T:s1" to 120, "T:s2" to null), prefixed.stopTimeDelays)
    }

    @Test
    fun `prefixing preserves the non-id fields`() {
        val prefixed = record.withIdPrefix("T:")
        assertEquals(record.id, prefixed.id)
        assertEquals(record.updatedAt, prefixed.updatedAt)
        assertEquals(record.vehicleLicencePlate, prefixed.vehicleLicencePlate)
    }
}
