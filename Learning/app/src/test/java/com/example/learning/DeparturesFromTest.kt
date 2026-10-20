package com.example.learning

import com.example.learning.repos.LatLon
import com.example.learning.repos.ScheduledStopTime
import com.example.learning.repos.ServiceCalendar
import com.example.learning.repos.StopTimesRecord
import com.example.learning.repos.TransitMode
import com.example.learning.repos.departuresFrom
import com.example.learning.repos.parseGtfsOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class DeparturesFromTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)

    private fun row(serviceId: String, gtfsTime: String) = ScheduledStopTime(
        tripId = "$serviceId@$gtfsTime",
        departureOffset = parseGtfsOffset(gtfsTime),
        arrivalOffset = parseGtfsOffset(gtfsTime),
        sequence = 1,
        routeId = "route",
        serviceId = serviceId,
        tripHeadsign = null,
        routeShortName = null,
        routeLongName = "Route",
        routeType = TransitMode.BUS,
        globbedStopId = "G1",
        globbedStopName = "Stop",
        stopId = "S1",
        stopName = "Stop",
        stopLoc = LatLon(0.0, 0.0),
        wheelchairBoarding = false,
    )

    private fun Sequence<StopTimesRecord>.times() = map { it.departureTime }.toList()

    @Test
    fun `parses offsets past 24 hours`() {
        assertEquals(Duration.ofHours(25).plusMinutes(30).plusSeconds(15), parseGtfsOffset("25:30:15"))
    }

    @Test
    fun `places each row on every day its service runs, in time order`() {
        val calendar = ServiceCalendar(mapOf(monday to setOf("A"), wednesday to setOf("A")))
        val rows = listOf(row("A", "09:00:00"), row("A", "08:00:00"))

        assertEquals(
            listOf(monday.atTime(8, 0), monday.atTime(9, 0), wednesday.atTime(8, 0), wednesday.atTime(9, 0)),
            rows.departuresFrom(monday.atStartOfDay(), calendar).times(),
        )
    }

    // Monday's service runs past midnight, overlapping the start of Tuesday's: concatenating the two
    // days would put Monday's 25:05 ahead of Tuesday's 00:45.
    @Test
    fun `interleaves a service's past-midnight tail with the next day's services`() {
        val calendar = ServiceCalendar(mapOf(monday to setOf("A"), tuesday to setOf("B")))
        val rows = listOf(
            row("A", "23:10:00"), row("A", "24:20:00"), row("A", "25:05:00"),
            row("B", "00:45:00"), row("B", "05:30:00"), row("B", "06:00:00"),
        )

        assertEquals(
            listOf(
                monday.atTime(23, 10),
                tuesday.atTime(0, 20), tuesday.atTime(0, 45), tuesday.atTime(1, 5),
                tuesday.atTime(5, 30), tuesday.atTime(6, 0),
            ),
            rows.departuresFrom(monday.atStartOfDay(), calendar).times(),
        )
    }

    @Test
    fun `starting after midnight still picks up the previous service day's tail`() {
        val calendar = ServiceCalendar(mapOf(monday to setOf("A"), tuesday to setOf("B")))
        val rows = listOf(row("A", "24:20:00"), row("A", "25:05:00"), row("B", "00:45:00"))

        assertEquals(
            listOf(tuesday.atTime(0, 45), tuesday.atTime(1, 5)),
            rows.departuresFrom(tuesday.atTime(0, 30), calendar).times(),
        )
    }

    @Test
    fun `ends once the calendar's last service day has run out`() {
        val calendar = ServiceCalendar(mapOf(monday to setOf("A")))
        val rows = listOf(row("A", "08:00:00"), row("A", "25:00:00"))

        assertEquals(
            listOf(monday.atTime(8, 0), tuesday.atTime(1, 0)),
            rows.departuresFrom(monday.atStartOfDay(), calendar).times(),
        )
    }

    @Test
    fun `nothing runs after the calendar ends`() {
        val calendar = ServiceCalendar(mapOf(monday to setOf("A")))
        val rows = listOf(row("A", "08:00:00"))

        assertTrue(rows.departuresFrom(wednesday.atStartOfDay(), calendar).times().isEmpty())
    }
}
