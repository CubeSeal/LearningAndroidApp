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
import kotlinx.coroutines.flow.first
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
    fun `beginStaging seeds the first available filter category`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown"), dep("200", "Uptown", soon.plusMinutes(10))))
        vm.beginStaging()
        // Both departures are bus-only, so Modes never differentiates anything and is dropped;
        // Routes precedes Destinations in FilterCategory's broad -> specific order.
        assertEquals(FilterCategory.Routes, vm.selectedFilterCategory.value)
    }

    @Test
    fun `selectFilterCategory updates the selected category`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(dep("100", "Downtown"), dep("200", "Uptown", soon.plusMinutes(10))))
        vm.beginStaging()
        vm.selectFilterCategory(FilterCategory.Destinations)
        assertEquals(FilterCategory.Destinations, vm.selectedFilterCategory.value)
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

    // --- Follow-my-location toggle ---

    private val stop2Loc = LatLon(-33.9, 151.3)
    private val stop2 = GlobbedStopRecord(
        globbedStopId = "G2",
        globbedStopName = "Other Stop",
        stopRecords = listOf(StopRecord("S2", "Other Stop", stop2Loc, false)),
    )

    private fun TestScope.buildInfoWithTwoStops(
        location: FakeLocationSource = FakeLocationSource(stopLoc),
        settings: FakeSettingsSource = FakeSettingsSource(),
    ): TransitInfo = TransitInfo(
        gtfsStaticRepository = FakeStaticGtfsSource(globbedStops = listOf(stop, stop2)),
        gtfsRealtimeRepository = FakeRealtimeSource(),
        locationRepo = location,
        settingsRepo = settings,
        scope = backgroundScope,
    )

    @Test
    fun `followLocation defaults to true`() = runTest(rule.dispatcher) {
        val vm = HomeViewModel(buildInfoWithTwoStops())
        assertTrue(vm.followLocation.value)
    }

    @Test
    fun `toggleFollowLocation flips and persists`() = runTest(rule.dispatcher) {
        val settings = FakeSettingsSource()
        val vm = HomeViewModel(buildInfoWithTwoStops(settings = settings))

        vm.toggleFollowLocation()
        assertFalse(vm.followLocation.value)
        assertFalse(settings.followLocation.first())

        vm.toggleFollowLocation()
        assertTrue(vm.followLocation.value)
        assertTrue(settings.followLocation.first())
    }

    @Test
    fun `while following, moving location re-focuses the closest stop`() = runTest(rule.dispatcher) {
        val location = FakeLocationSource(stopLoc)
        val transitInfo = buildInfoWithTwoStops(location = location)

        transitInfo.focusedBusStop.test {
            assertEquals("G1", awaitItem()?.globbedStopId)
            location.changeLocation(stop2Loc)
            assertEquals("G2", awaitItem()?.globbedStopId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with following off, moving location leaves the focused stop alone`() = runTest(rule.dispatcher) {
        val location = FakeLocationSource(stopLoc)
        val transitInfo = buildInfoWithTwoStops(location = location)
        val vm = HomeViewModel(transitInfo)
        vm.toggleFollowLocation()

        transitInfo.focusedBusStop.test {
            assertEquals("G1", awaitItem()?.globbedStopId)
            location.changeLocation(stop2Loc)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enabling following re-snaps to the closest stop`() = runTest(rule.dispatcher) {
        val location = FakeLocationSource(stop2Loc)
        val transitInfo = buildInfoWithTwoStops(location = location)
        val vm = HomeViewModel(transitInfo)
        vm.toggleFollowLocation() // off
        assertEquals("G2", transitInfo.focusedBusStop.value?.globbedStopId)

        vm.toggleFollowLocation() // back on
        assertEquals("G2", transitInfo.focusedBusStop.value?.globbedStopId)
    }

    @Test
    fun `re-enabling follow after a manual pick snaps back to the closest stop`() = runTest(rule.dispatcher) {
        val location = FakeLocationSource(stopLoc) // closest is G1
        val transitInfo = buildInfoWithTwoStops(location = location)
        val vm = HomeViewModel(transitInfo)
        assertEquals("G1", transitInfo.focusedBusStop.value?.globbedStopId)

        // Manually pick the far stop (G2) — turns following off, same as picking it from search.
        transitInfo.updateFocusedBusStop(stop2)
        assertEquals("G2", transitInfo.focusedBusStop.value?.globbedStopId)
        assertFalse(transitInfo.followLocation.value)

        // Re-enable following without the phone having moved: closest is still G1, the same value
        // it was before the manual pick, so a plain distinctUntilChangedBy on the closest stop's id
        // would (wrongly) think nothing changed and skip re-snapping.
        vm.toggleFollowLocation()
        assertEquals("G1", transitInfo.focusedBusStop.value?.globbedStopId)
    }

    @Test
    fun `enabling follow location requests a fresh fix, disabling does not`() = runTest(rule.dispatcher) {
        val location = FakeLocationSource(stopLoc)
        val vm = HomeViewModel(buildInfoWithTwoStops(location = location))
        val baseline = location.freshFixRequests // init's refresh() already requested one

        vm.toggleFollowLocation() // on -> off
        assertEquals(baseline, location.freshFixRequests)

        vm.toggleFollowLocation() // off -> on
        assertEquals(baseline + 1, location.freshFixRequests)
    }

    @Test
    fun `enabling follow location shows the refresh spinner while it runs`() = runTest(rule.dispatcher) {
        val vm = HomeViewModel(buildInfoWithTwoStops())
        vm.toggleFollowLocation() // on -> off, settles before we start observing

        vm.isRefreshing.test {
            assertFalse(awaitItem())
            vm.toggleFollowLocation() // off -> on: should spin while the fresh fix is requested
            assertTrue(awaitItem())
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disabling follow location does not show the refresh spinner`() = runTest(rule.dispatcher) {
        val vm = HomeViewModel(buildInfoWithTwoStops())
        vm.isRefreshing.test {
            assertFalse(awaitItem())
            vm.toggleFollowLocation() // on -> off: no location fetch needed
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting a saved stop turns following off`() = runTest(rule.dispatcher) {
        val transitInfo = buildInfoWithTwoStops()
        HomeViewModel(transitInfo)
        assertTrue(transitInfo.followLocation.value)

        transitInfo.selectSavedStop(stop2, emptySet())
        assertFalse(transitInfo.followLocation.value)
    }
}
