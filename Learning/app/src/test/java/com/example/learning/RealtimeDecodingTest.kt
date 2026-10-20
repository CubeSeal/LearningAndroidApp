package com.example.learning

import com.example.learning.repos.toRealtimeTripInfo
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(listOf("S1" to 90), decoded.stopTimeDelays)
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

        assertEquals(listOf("S2" to 30), entity(noData, scheduled).toRealtimeTripInfo().stopTimeDelays)
    }
}
