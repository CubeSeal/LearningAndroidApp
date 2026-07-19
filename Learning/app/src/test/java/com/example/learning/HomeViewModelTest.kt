package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.StopRecord
import com.example.learning.repos.StopTimesRecord
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

class HomeViewModelTest {
    @get:Rule val rule = MainDispatcherRule()

    private val stopLoc = LatLon(-33.8688, 151.2093)
    private val soon = LocalDateTime.now().plusHours(1)
    private val stopId = "G1"
    private val stop = GlobbedStopRecord(
        globbedStopId = stopId,
        globbedStopName = "Test Stop",
        stopRecords = listOf(StopRecord("S1", "Test Stop", stopLoc, false)),
    )

    private fun dep(route: String, headsign: String, at: LocalDateTime = soon) = StopTimesRecord(
        tripId = "trip-$route", departureTime = at, arrivalTime = at, sequence = 1,
        routeId = "route-$route", serviceId = "svc", tripHeadsign = headsign,
        routeShortName = route, routeLongName = "Route $route", routeType = 3,
        globbedStopId = stopId, globbedStopName = "Test Stop",
        stopId = "S1", stopName = "Test Stop", stopLoc = stopLoc, wheelchairBoarding = false,
    )

    private fun TestScope.buildVm(departures: List<StopTimesRecord> = emptyList()): HomeViewModel {
        val transitInfo = TransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(
                globbedStops = listOf(stop),
                stopTimesRecords = departures,
            ),
            gtfsRealtimeRepository = FakeRealtimeSource(),
            locationRepo = FakeLocationSource(),
            settingsRepo = FakeSettingsSource(),
            scope = backgroundScope,
        )
        return HomeViewModel(transitInfo)
    }

    @Test
    fun `route filter narrows departures then restores`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown"), dep("200", "Uptown", soon.plusMinutes(10))))

        vm.associatedStopTimes.test {
            assertEquals(2, awaitItem().size)

            val filter100 = TransitFilterOptions.RouteShortName("100", TransitMode.BUS)
            vm.toggleFilterForBusStops(filter100)
            assertEquals(1, awaitItem().size)

            vm.toggleFilterForBusStops(filter100)
            assertEquals(2, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `staging seeds from committed, applyStaging commits and emits PopBack`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown"), dep("200", "Uptown", soon.plusMinutes(10))))
        val filter100 = TransitFilterOptions.RouteShortName("100", TransitMode.BUS)
        vm.toggleFilterForBusStops(filter100)

        vm.navEvents.test {
            vm.beginStaging()
            assertEquals(setOf(filter100), vm.stagedFilters.value)

            vm.toggleStaged(filter100)
            assertEquals(emptySet<TransitFilterOptions>(), vm.stagedFilters.value)

            vm.applyStaging()
            assertEquals(emptySet<TransitFilterOptions>(), vm.selectedFiltersForBusStop.value)
            assertEquals(HomeNavEvent.PopBack, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `headerAlpha is 1f at top, 0f past first item, partial when partially scrolled`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.onListScrolled(0, 0)
        assertEquals(1f, vm.headerAlpha.value)

        vm.onListScrolled(2, 0)
        assertEquals(0f, vm.headerAlpha.value)

        vm.onListScrolled(0, 200)
        val alpha = vm.headerAlpha.value
        assertTrue("expected 0 < alpha < 1, got $alpha", alpha > 0f && alpha < 1f)
    }

    @Test
    fun `scrollToTop fires when data loads`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown")))
        vm.scrollToTop.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onEditStopClicked emits OpenPickStop`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.navEvents.test {
            vm.onEditStopClicked()
            assertEquals(HomeNavEvent.OpenPickStop, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onOpenFilters emits OpenFilters`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.navEvents.test {
            vm.onOpenFilters()
            assertEquals(HomeNavEvent.OpenFilters, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDepartureClicked emits OpenTrip with correct ids`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown")))
        vm.navEvents.test {
            val record = vm.associatedStopTimes.value.first().second
            vm.onDepartureClicked(record)
            val event = awaitItem() as HomeNavEvent.OpenTrip
            assertEquals("trip-100", event.tripId)
            assertEquals("S1", event.stopId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addSavedStop emits snackbar message containing stop name`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.snackbarMessages.test {
            vm.addSavedStop(stop)
            val msg = awaitItem()
            assertTrue(msg.contains("Test Stop"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh clears selected filters`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown"), dep("200", "Uptown", soon.plusMinutes(10))))
        val filter100 = TransitFilterOptions.RouteShortName("100", TransitMode.BUS)
        vm.toggleFilterForBusStops(filter100)
        assertFalse(vm.selectedFiltersForBusStop.value.isEmpty())

        vm.refresh()
        assertTrue(vm.selectedFiltersForBusStop.value.isEmpty())
    }
}
