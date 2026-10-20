package com.example.learning

import com.example.learning.db.StopTimeWithDetails
import com.example.learning.repos.toScheduledStopTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledStopTimeTest {
    private fun row(pickupType: Int?, dropOffType: Int?) = StopTimeWithDetails(
        tripId = "T1",
        departureTime = "08:00:00",
        arrivalTime = "08:00:00",
        stopSequence = 1,
        routeId = "R1",
        serviceId = "svc",
        tripHeadsign = null,
        routeShortName = null,
        routeLongName = "Route",
        routeType = 2,
        globbedStopName = "Stop",
        globbedStopId = "G1",
        stopId = "S1",
        stopName = "Stop",
        stopLat = 0.0,
        stopLon = 0.0,
        wheelchairBoarding = 0,
        pickupType = pickupType,
        dropOffType = dropOffType,
    )

    private fun StopTimeWithDetails.allowed() =
        toScheduledStopTime().let { it.pickupAllowed to it.dropOffAllowed }

    // GTFS types: 0 / empty = regular, 1 = none, 2 = phone the agency, 3 = ask the driver. Only 1 rules it out.
    @Test
    fun `only type 1 rules out a pickup or drop-off`() {
        assertEquals(true to true, row(pickupType = null, dropOffType = null).allowed())
        assertEquals(true to true, row(pickupType = 0, dropOffType = 0).allowed())
        assertEquals(false to false, row(pickupType = 1, dropOffType = 1).allowed())
        assertEquals(false to true, row(pickupType = 1, dropOffType = 0).allowed())
        assertEquals(true to true, row(pickupType = 3, dropOffType = 2).allowed())
    }
}
