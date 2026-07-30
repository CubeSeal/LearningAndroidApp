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

    private fun dep(
        route: String,
        headsign: String,
        at: LocalDateTime = soon
    ) =
        StopTimesRecord(
            tripId = "trip-$route",
            departureTime = at,
            arrivalTime = at,
            sequence = 1,
            routeId = "route-$route",
            serviceId = "svc",
            tripHeadsign = headsign,
            routeShortName = route,
            routeLongName = "Route $route",
            routeType = TransitMode.BUS,
            globbedStopId = stopId,
            globbedStopName = "Test Stop",
            stopId = "S1",
            stopName = "Test Stop",
            stopLoc = stopLoc,
            wheelchairBoarding = false
        )

    private val standardDepartures = listOf(dep("100", "Downtown"), dep("200", "Uptown", soon.plusMinutes(10)))

    // Standard type to return injected dependencies to inspect.
    private data class TestDependencies(
        val vm: HomeViewModel,
        val gtfsStaticRepository: FakeStaticGtfsSource,
        val gtfsRealtimeRepository: FakeRealtimeSource,
        val locationRepository: FakeLocationSource,
        val settingsRepository: FakeSettingsSource,
    )

    private fun TestScope.buildVm(
        departures: List<StopTimesRecord> = standardDepartures,
        stops: List<GlobbedStopRecord> = listOf(stop),
        location: LatLon = stopLoc
    ): TestDependencies {
        val gtfsStaticRepository = FakeStaticGtfsSource(globbedStops = stops, stopTimesRecords = departures)
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource(location)
        val settingsRepo = FakeSettingsSource()

        val transitInfo = TransitInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            backgroundScope,
        )

        return TestDependencies(
            HomeViewModel(transitInfo),
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
        )
    }

    private val filter100 = TransitFilterOptions.RouteShortName("100", TransitMode.BUS)

    @Test
    fun `route filter narrows departures then restores`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()

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
        val (vm, _) = buildVm()
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
        val (vm, _) = buildVm()
        vm.beginStaging()
        // Both departures are bus-only, so Modes never differentiates anything and is dropped;
        // Routes precedes Destinations in FilterCategory's broad -> specific order.
        assertEquals(FilterCategory.Routes, vm.selectedFilterCategory.value)
    }

    @Test
    fun `selectFilterCategory updates the selected category`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.beginStaging()
        vm.selectFilterCategory(FilterCategory.Destinations)
        assertEquals(FilterCategory.Destinations, vm.selectedFilterCategory.value)
    }

    @Test
    fun `scrollToTop fires when data loads`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(dep("100", "Downtown")))
        vm.scrollToTop.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onEditStopClicked emits OpenPickStop`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.navEvents.test {
            vm.onEditStopClicked()
            assertEquals(HomeNavEvent.OpenPickStop, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onOpenFilters emits OpenFilters`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.navEvents.test {
            vm.onOpenFilters()
            assertEquals(HomeNavEvent.OpenFilters, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDepartureClicked emits OpenTrip with correct ids`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(dep("100", "Downtown")))
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
        val (vm, _) = buildVm(listOf(dep("100", "Downtown")))
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
        val (vm, _) = buildVm()
        vm.snackbarMessages.test {
            vm.addSavedStop(stop)
            val msg = awaitItem()
            assertTrue(msg.contains("Test Stop"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addSavedStop with active filters saves them as a combo`() = runTest(rule.dispatcher) {
        val testDepartures = listOf(dep("100", "Downtown"))
        val (vm, _, _, _, fakeSettingsSource) = buildVm(testDepartures)

        vm.toggleFilterForBusStops(filter100)
        vm.addSavedStop(stop)

        fakeSettingsSource.savedStops.test {
            val saved = awaitItem().single()
            assertEquals("G1", saved.stopId)
            assertEquals(listOf(listOf<TransitFilterOptions>(filter100)), saved.combos)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addSavedStop with no active filters saves a naked stop`() = runTest(rule.dispatcher) {
        val (vm, _, _, _, fakeSettingsSource) = buildVm()

        vm.addSavedStop(stop)

        fakeSettingsSource.savedStops.test {
            val saved = awaitItem().single()
            assertEquals("G1", saved.stopId)
            assertTrue(saved.combos.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a saved-stop combo selection is applied to the home filters`() = runTest(rule.dispatcher) {
        val gtfsStaticRepository = FakeStaticGtfsSource(globbedStops = listOf(stop), stopTimesRecords = standardDepartures)
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource()
        val settingsRepo = FakeSettingsSource()
        val transitInfo = TransitInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            backgroundScope,
        )
        val homeViewModel = HomeViewModel(transitInfo)
        val savedStopsViewModel = SavedStopsViewModel(transitInfo)

        savedStopsViewModel.onSavedStopSelected(stop, setOf(filter100))
        assertEquals(setOf(filter100), homeViewModel.selectedFiltersForBusStop.value)

        // The naked option clears the filters again.
        savedStopsViewModel.onScreenResumed()
        savedStopsViewModel.onSavedStopSelected(stop, emptySet())
        assertEquals(emptySet<TransitFilterOptions>(), homeViewModel.selectedFiltersForBusStop.value)
    }

    @Test
    fun `refresh clears selected filters`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
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

    @Test
    fun `followLocation defaults to true`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(stops = listOf(stop, stop2))
        assertTrue(vm.followLocation.value)
    }

    @Test
    fun `toggleFollowLocation flips and persists`() = runTest(rule.dispatcher) {
        val (vm, _, _, _, fakeSettingsSource) = buildVm(stops = listOf(stop, stop2))

        vm.toggleFollowLocation()
        assertFalse(vm.followLocation.value)
        assertFalse(fakeSettingsSource.followLocation.first())

        vm.toggleFollowLocation()
        assertTrue(vm.followLocation.value)
        assertTrue(fakeSettingsSource.followLocation.first())
    }

    @Test
    fun `while following, moving location re-focuses the closest stop`() = runTest(rule.dispatcher) {
        val (vm, _, _, fakeLocationSource, _) = buildVm(stops = listOf(stop, stop2))

        vm.focusedBusStop.test {
            assertEquals("G1", awaitItem()?.globbedStopId)
            fakeLocationSource.changeLocation(stop2Loc)
            assertEquals("G2", awaitItem()?.globbedStopId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with following off, moving location leaves the focused stop alone`() = runTest(rule.dispatcher) {
        val (vm, _, _, fakeLocationSource, _) = buildVm(stops = listOf(stop, stop2))

        vm.toggleFollowLocation()
        vm.focusedBusStop.test {
            assertEquals("G1", awaitItem()?.globbedStopId)
            fakeLocationSource.changeLocation(stop2Loc)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enabling following re-snaps to the closest stop`() = runTest(rule.dispatcher) {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = listOf(stop, stop2), stopTimesRecords = standardDepartures)
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource(stop2Loc)
        val settingsRepo = FakeSettingsSource()
        val transitInfo = TransitInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            backgroundScope,
        )
        val homeViewModel = HomeViewModel(transitInfo)
        val savedStopsViewModel = SavedStopsViewModel(transitInfo)

        // Toggle off and then select G1.
        homeViewModel.toggleFollowLocation() // off
        savedStopsViewModel.onSavedStopSelected(stop, emptySet())
        assertEquals("G1", homeViewModel.focusedBusStop.value?.globbedStopId)

        // Toggle on and test for G2.
        homeViewModel.toggleFollowLocation() // back on
        assertEquals("G2", homeViewModel.focusedBusStop.value?.globbedStopId)
    }

    @Test
    fun `re-enabling follow after a manual pick snaps back to the closest stop`() = runTest(rule.dispatcher) {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = listOf(stop, stop2), stopTimesRecords = standardDepartures)
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource()
        val settingsRepo = FakeSettingsSource()
        val transitInfo = TransitInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            backgroundScope,
        )
        val homeViewModel = HomeViewModel(transitInfo)
        val savedStopsViewModel = SavedStopsViewModel(transitInfo)

        assertEquals("G1", homeViewModel.focusedBusStop.value?.globbedStopId)

        // Manually pick the far stop (G2) — turns following off, same as picking it from search.
        savedStopsViewModel.onSavedStopSelected(stop2, emptySet())
        assertEquals("G2", homeViewModel.focusedBusStop.value?.globbedStopId)
        assertFalse(homeViewModel.followLocation.value)

        // Re-enable following without the phone having moved: closest is still G1, the same value
        // it was before the manual pick, so a plain distinctUntilChangedBy on the closest stop's id
        // would (wrongly) think nothing changed and skip re-snapping.
        homeViewModel.toggleFollowLocation()
        assertEquals("G1", homeViewModel.focusedBusStop.value?.globbedStopId)
    }

    @Test
    fun `enabling follow location requests a fresh fix, disabling does not`() = runTest(rule.dispatcher) {
        val (vm, _, _, fakeLocationSource, _) = buildVm()
        val baseline = fakeLocationSource.freshFixRequests // init's refresh() already requested one

        vm.toggleFollowLocation() // on -> off
        assertEquals(baseline, fakeLocationSource.freshFixRequests)

        vm.toggleFollowLocation() // off -> on
        assertEquals(baseline + 1, fakeLocationSource.freshFixRequests)
    }

    @Test
    fun `enabling follow location shows the refresh spinner while it runs`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(stops = listOf(stop, stop2))
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
        val (vm, _) = buildVm(stops = listOf(stop, stop2))
        vm.isRefreshing.test {
            assertFalse(awaitItem())
            vm.toggleFollowLocation() // on -> off: no location fetch needed
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting a saved stop turns following off`() = runTest(rule.dispatcher) {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = listOf(stop, stop2), stopTimesRecords = standardDepartures)
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource(stop2Loc)
        val settingsRepo = FakeSettingsSource()

        val transitInfo = TransitInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            backgroundScope,
        )

        val homeViewModel = HomeViewModel(transitInfo)
        val savedStopsViewModel = SavedStopsViewModel(transitInfo)

        assertTrue(homeViewModel.followLocation.value)

        savedStopsViewModel.onSavedStopSelected(stop2, emptySet())
        assertFalse(homeViewModel.followLocation.value)
    }
}
