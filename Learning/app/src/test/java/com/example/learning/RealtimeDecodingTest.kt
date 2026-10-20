package com.example.learning

import com.example.learning.repos.RealtimeStopUpdate
import com.example.learning.repos.toRealtimeTripInfo
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RealtimeDecodingTest {
    private fun entity(vararg stops: StopTimeUpdate) = FeedEntity.newBuilder()
        .setId("entity-1")
        .setTripUpdate(
            TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setTripId("trip-1"))
                .addAllStopTimeUpdate(stops.toList())
        )
        .build()

    @Test
    fun `a scheduled stop carries its departure delay`() {
        val stop = StopTimeUpdate.newBuilder()
            .setStopId("S1")
            .setDeparture(StopTimeEvent.newBuilder().setDelay(90))
            .build()

        val decoded = entity(stop).toRealtimeTripInfo()

        assertEquals("trip-1", decoded.tripId)
        assertEquals(listOf(RealtimeStopUpdate("S1", delaySeconds = 90)), decoded.stopUpdates)
    }

    // NO_DATA means the feed knows nothing about this stop — the departure should read as timetable
    // only, not as tracked with no delay. (About 1 in 9 bus stop updates.)
    @Test
    fun `a NO_DATA stop is left out, as if there were no realtime for it`() {
        val noData = StopTimeUpdate.newBuilder()
            .setStopId("S1")
            .setScheduleRelationship(StopTimeUpdate.ScheduleRelationship.NO_DATA)
            .build()
        val scheduled = StopTimeUpdate.newBuilder()
            .setStopId("S2")
            .setDeparture(StopTimeEvent.newBuilder().setDelay(30))
            .build()

        assertEquals(
            listOf(RealtimeStopUpdate("S2", delaySeconds = 30)),
            entity(noData, scheduled).toRealtimeTripInfo().stopUpdates,
        )
    }

    @Test
    fun `an absolute departure time is kept`() {
        val epoch = 1_792_501_381L
        val stop = StopTimeUpdate.newBuilder()
            .setStopId("S1")
            .setDeparture(StopTimeEvent.newBuilder().setTime(epoch))
            .build()

        assertEquals(
            listOf(
                RealtimeStopUpdate(
                    "S1",
                    delaySeconds = null,
                    departure = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault()),
                )
            ),
            entity(stop).toRealtimeTripInfo().stopUpdates,
        )
    }

    // Protobuf reads an unset delay as 0; "no delay given" must not become "exactly on time".
    @Test
    fun `a stop with neither delay nor time has no delay rather than zero`() {
        val stop = StopTimeUpdate.newBuilder().setStopId("S1").build()

        assertEquals(listOf(RealtimeStopUpdate("S1", delaySeconds = null)), entity(stop).toRealtimeTripInfo().stopUpdates)
    }

    @Test
    fun `a SKIPPED stop is decoded as skipped`() {
        val skipped = StopTimeUpdate.newBuilder()
            .setStopId("S1")
            .setScheduleRelationship(StopTimeUpdate.ScheduleRelationship.SKIPPED)
            .build()

        assertEquals(
            listOf(RealtimeStopUpdate("S1", delaySeconds = null, skipped = true)),
            entity(skipped).toRealtimeTripInfo().stopUpdates,
        )
    }

    @Test
    fun `a cancelled trip is decoded as cancelled`() {
        val cancelled = FeedEntity.newBuilder()
            .setId("entity-1")
            .setTripUpdate(
                TripUpdate.newBuilder().setTrip(
                    TripDescriptor.newBuilder()
                        .setTripId("trip-1")
                        .setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED)
                )
            )
            .build()

        assertTrue(cancelled.toRealtimeTripInfo().cancelled)
        assertFalse(entity().toRealtimeTripInfo().cancelled)
    }

    // The last stop of a trip only has an arrival.
    @Test
    fun `a stop with only an arrival uses it`() {
        val stop = StopTimeUpdate.newBuilder()
            .setStopId("S1")
            .setArrival(StopTimeEvent.newBuilder().setDelay(45))
            .build()

        assertEquals(listOf(RealtimeStopUpdate("S1", delaySeconds = 45)), entity(stop).toRealtimeTripInfo().stopUpdates)
    }
}
