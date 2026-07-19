package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.LatLon
import com.example.learning.repos.StopTimesRecord
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TripsViewModelTest {
    @get:Rule val rule = MainDispatcherRule()

    private val stopLoc = LatLon(-33.8688, 151.2093)
    private val date = LocalDate.of(2026, 7, 19)
    private val baseTime = LocalDateTime.of(2026, 7, 19, 9, 0)

    private fun stop(id: String, seq: Int) = StopTimesRecord(
        tripId = "T1", departureTime = baseTime.plusMinutes(seq * 5L), arrivalTime = baseTime,
        sequence = seq, routeId = "R1", serviceId = "svc", tripHeadsign = "City",
        routeShortName = "100", routeLongName = "Route 100", routeType = 3,
        globbedStopId = id, globbedStopName = "Stop $id",
        stopId = id, stopName = "Stop $id", stopLoc = stopLoc, wheelchairBoarding = false,
    )

    private val tripStops = listOf(stop("S1", 1), stop("S2", 2), stop("S3", 3))

    private fun TestScope.buildVm(focusedStopId: String = "S2"): TripsViewModel {
        val transitInfo = TransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(
                stopTimesRecords = tripStops,
            ),
            gtfsRealtimeRepository = FakeRealtimeSource(),
            locationRepo = FakeLocationSource(),
            settingsRepo = FakeSettingsSource(),
            scope = backgroundScope,
        )
        return TripsViewModel("T1", focusedStopId, date, transitInfo)
    }

    @Test
    fun `stopTimesRecord loads all stops for the trip`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        assertEquals(3, vm.stopTimesRecord.value.size)
    }

    @Test
    fun `scrollTarget points one past focused stop index`() = runTest(rule.dispatcher) {
        val vm = buildVm(focusedStopId = "S2")
        // S2 is at index 1, scrollTarget.index should be 2
        assertEquals(ScrollTarget(2, -24), vm.scrollTarget.value)
    }

    @Test
    fun `scrollTarget is null when focused stop is not in this trip`() = runTest(rule.dispatcher) {
        val vm = buildVm(focusedStopId = "S99")
        assertNull(vm.scrollTarget.value)
    }

    @Test
    fun `onBackClicked emits PopBack`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.navEvents.test {
            vm.onBackClicked()
            assertEquals(TripsNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onStopClicked emits PopBack`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.navEvents.test {
            vm.onStopClicked("S1")
            assertEquals(TripsNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
