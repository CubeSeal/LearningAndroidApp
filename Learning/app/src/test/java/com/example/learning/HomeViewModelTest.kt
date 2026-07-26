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

    private fun TestScope.buildInfo(departures: List<StopTimesRecord> = emptyList()): TransitInfo =
        TransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(
                globbedStops = listOf(stop),
                stopTimesRecords = departures,
            ),
            gtfsRealtimeRepository = FakeRealtimeSource(),
            locationRepo = FakeLocationSource(),
            settingsRepo = FakeSettingsSource(),
            scope = backgroundScope,
        )

    private fun TestScope.buildVm(departures: List<StopTimesRecord> = emptyList()): HomeViewModel =
        HomeViewModel(buildInfo(departures))

    private val filter100 = TransitFilterOptions.RouteShortName("100", TransitMode.BUS)

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

    // A single gesture that delivers two intents (fast double-tap, or a tap landing during the slide
    // transition) must push one destination; the latch re-arms once the screen is shown again.
    @Test
    fun `duplicate onDepartureClicked navigates once until the screen is shown again`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown")))
        vm.navEvents.test {
            val record = vm.associatedStopTimes.value.first().second
            vm.onDepartureClicked(record)
            assertTrue(awaitItem() is HomeNavEvent.OpenTrip)   // first tap navigates
            vm.onDepartureClicked(record)                       // duplicate of the same gesture
            expectNoEvents()                                    // ...is dropped

            vm.onScreenResumed()                                // returned to Home
            vm.onDepartureClicked(record)
            assertTrue(awaitItem() is HomeNavEvent.OpenTrip)    // navigates again
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
    fun `addSavedStop with active filters saves them as a combo`() = runTest(rule.dispatcher) {
        val transitInfo = buildInfo(listOf(dep("100", "Downtown")))
        val vm = HomeViewModel(transitInfo)
        vm.toggleFilterForBusStops(filter100)

        vm.addSavedStop(stop)

        val saved = transitInfo.savedStops.value.single()
        assertEquals("G1", saved.stop.globbedStopId)
        assertEquals(listOf(setOf<TransitFilterOptions>(filter100)), saved.combos)
    }

    @Test
    fun `addSavedStop with no active filters saves a naked stop`() = runTest(rule.dispatcher) {
        val transitInfo = buildInfo()
        val vm = HomeViewModel(transitInfo)

        vm.addSavedStop(stop)

        val saved = transitInfo.savedStops.value.single()
        assertEquals("G1", saved.stop.globbedStopId)
        assertTrue(saved.combos.isEmpty())
    }

    @Test
    fun `a saved-stop combo selection is applied to the home filters`() = runTest(rule.dispatcher) {
        val transitInfo = buildInfo()
        val vm = HomeViewModel(transitInfo)

        transitInfo.selectSavedStop(stop, setOf(filter100))
        assertEquals(setOf(filter100), vm.selectedFiltersForBusStop.value)

        // The naked option clears the filters again.
        transitInfo.selectSavedStop(stop, emptySet())
        assertTrue(vm.selectedFiltersForBusStop.value.isEmpty())
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
