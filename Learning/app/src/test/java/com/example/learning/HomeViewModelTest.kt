package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeClockSource
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.RealtimeTripInfo
import com.example.learning.repos.ScheduledStopTime
import com.example.learning.repos.ServiceCalendar
import com.example.learning.repos.StopRecord
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class HomeViewModelTest {
    @get:Rule val rule = MainDispatcherRule()

    private val stopLoc = LatLon(-33.8688, 151.2093)
    private val soon = LocalDateTime.now().plusHours(1)
    private val today = LocalDate.now()
    // The schedule world: every departure below belongs to service "svc", which runs today.
    private val calendar = ServiceCalendar(mapOf(today to setOf("svc")))
    private val todayAndTomorrow = ServiceCalendar(mapOf(today to setOf("svc"), today.plusDays(1) to setOf("svc")))
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
        ScheduledStopTime(
            tripId = "trip-$route",
            departureOffset = Duration.between(today.atStartOfDay(), at),
            arrivalOffset = Duration.between(today.atStartOfDay(), at),
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
        val clock: FakeClockSource,
    )

    private fun TestScope.buildVm(
        departures: List<ScheduledStopTime> = standardDepartures,
        stops: List<GlobbedStopRecord> = listOf(stop),
        location: LatLon = stopLoc,
        serviceCalendar: ServiceCalendar = calendar,
        realtime: List<RealtimeTripInfo> = emptyList(),
    ): TestDependencies {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = stops, scheduledStopTimes = departures, calendar = serviceCalendar)
        val gtfsRealtimeRepository = FakeRealtimeSource(realtime)
        val locationRepo = FakeLocationSource(location)
        val settingsRepo = FakeSettingsSource()
        val clock = FakeClockSource()

        val transitInfo = buildTransitInfo(gtfsStaticRepository, gtfsRealtimeRepository, locationRepo, settingsRepo, clock)

        return TestDependencies(
            HomeViewModel(transitInfo),
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            clock,
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

    // A 25:30 departure leaves at 1:30am tomorrow but belongs to today's service: the trip must open
    // on today, or the Trips screen would place the whole trip a day late.
    @Test
    fun `onDepartureClicked opens an after-midnight departure on its service day`() = runTest(rule.dispatcher) {
        val afterMidnight = today.atStartOfDay().plusHours(25).plusMinutes(30)
        val (vm, _) = buildVm(listOf(dep("100", "Downtown", at = afterMidnight)))
        vm.navEvents.test {
            vm.onDepartureClicked(vm.associatedStopTimes.value.single().second)
            assertEquals(today.toString(), (awaitItem() as HomeNavEvent.OpenTrip).date)
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
        val transitInfo = buildTransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar),
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

    // SavedStops navigates home with `resetTo(Home)`, which pops the start destination inclusively —
    // so the Home the combo lands on is a *brand new* ViewModel, constructed after the filters were
    // committed. Its startup must not clear them (only pull-to-refresh does).
    @Test
    fun `a saved-stop combo survives Home being recreated on the way back`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar),
        )
        val savedStopsViewModel = SavedStopsViewModel(transitInfo)

        savedStopsViewModel.onSavedStopSelected(stop, setOf(filter100))

        val recreatedHome = HomeViewModel(transitInfo)
        assertEquals(setOf(filter100), recreatedHome.selectedFiltersForBusStop.value)
    }

    @Test
    fun `refresh keeps selected filters`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.toggleFilterForBusStops(filter100)
        assertEquals(setOf(filter100), vm.selectedFiltersForBusStop.value)
        vm.refresh()
        assertEquals(setOf(filter100), vm.selectedFiltersForBusStop.value)
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
        val transitInfo = buildTransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(listOf(stop, stop2), standardDepartures, calendar),
            locationRepo = FakeLocationSource(stop2Loc),
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
        val transitInfo = buildTransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(listOf(stop, stop2), standardDepartures, calendar),
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
        val transitInfo = buildTransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(listOf(stop, stop2), standardDepartures, calendar),
            locationRepo = FakeLocationSource(stop2Loc),
        )

        val homeViewModel = HomeViewModel(transitInfo)
        val savedStopsViewModel = SavedStopsViewModel(transitInfo)

        assertTrue(homeViewModel.followLocation.value)

        savedStopsViewModel.onSavedStopSelected(stop2, emptySet())
        assertFalse(homeViewModel.followLocation.value)
    }

    // --- Stitched, paged departures ---

    private val pageSize = HomeViewModel.PAGE_SIZE
    private val busyStop = (1..1000L).map { dep("610x", "Excellent 610x.", at = soon.plusMinutes(it)) }

    @Test
    fun `a service running on several days shows a departure on each`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = listOf(dep("100", "Downtown")), serviceCalendar = todayAndTomorrow)

        assertEquals(
            listOf(soon, soon.plusDays(1)),
            vm.associatedStopTimes.value.map { it.second.stopTimesRecord.departureTime },
        )
    }

    // No location fix yet, so there's no stop to load departures for.
    @Test
    fun `departures are loading until there's a stop to load them for`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(stops = emptyList())

        assertTrue(vm.loadingDepartures.value)
    }

    // A stop whose timetable has run out is loaded, just empty — not a spinner forever.
    @Test
    fun `a stop with no departures finishes loading with an empty list`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = emptyList())

        assertFalse(vm.loadingDepartures.value)
        assertTrue(vm.associatedStopTimes.value.isEmpty())
    }

    @Test
    fun `the departure list starts at one page`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = busyStop)

        assertEquals(pageSize, vm.associatedStopTimes.value.size)
    }

    @Test
    fun `scrolling near the end of the list loads another page`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = busyStop)

        vm.onScrolledTo(vm.associatedStopTimes.value.lastIndex)
        assertEquals(2 * pageSize, vm.associatedStopTimes.value.size)
    }

    @Test
    fun `scrolling short of the end does not load another page`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = busyStop)

        vm.onScrolledTo(0)
        assertEquals(pageSize, vm.associatedStopTimes.value.size)
    }

    @Test
    fun `loading another page does not scroll back to the top`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = busyStop)
        vm.scrollToTop.test {
            awaitItem() // the initial load
            vm.onScrolledTo(vm.associatedStopTimes.value.lastIndex)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing filters goes back to one page`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = busyStop)
        vm.onScrolledTo(vm.associatedStopTimes.value.lastIndex)

        vm.toggleFilterForBusStops(TransitFilterOptions.RouteShortName("610x", TransitMode.BUS))
        assertEquals(pageSize, vm.associatedStopTimes.value.size)
    }

    // The filter row comes from the whole timetable, not the page: a rarely-run route past the first
    // page is still offered, and selecting it finds its departure.
    @Test
    fun `filters cover departures beyond the first page`() = runTest(rule.dispatcher) {
        val busy = (1..2L * pageSize).map { dep("100", "Downtown", at = soon.plusMinutes(it)) }
        val rare = dep("200", "Uptown", at = soon.plusMinutes(3L * pageSize))
        val (vm, _) = buildVm(departures = busy + rare)
        val route200 = TransitFilterOptions.RouteShortName("200", TransitMode.BUS)

        assertTrue(route200 in vm.availableFiltersForBusStop.value)
        vm.toggleFilterForBusStops(route200)
        assertEquals(listOf("trip-200"), vm.associatedStopTimes.value.map { it.second.stopTimesRecord.tripId })
    }

    // The realtime feed only describes a trip's current run; the same trip id tomorrow has no realtime yet.
    @Test
    fun `realtime attaches to a trip's next run only`() = runTest(rule.dispatcher) {
        val realtime = RealtimeTripInfo(
            id = "entity-1",
            tripId = "trip-100",
            updatedAt = LocalDateTime.now(),
            stopTimeDelays = listOf("S1" to 60),
            vehicleLicencePlate = "ABC123",
        )
        val (vm, _) = buildVm(
            departures = listOf(dep("100", "Downtown")),
            serviceCalendar = todayAndTomorrow,
            realtime = listOf(realtime),
        )

        val (todays, tomorrows) = vm.associatedStopTimes.value.map { it.second }
        assertNotNull(todays.realtimeStopTimesRecord)
        assertNull(tomorrows.realtimeStopTimesRecord)
    }

    // The phone stays put (no location update), so only the minute tick can pick up the new delay.
    @Test
    fun `realtime refreshes on the minute tick`() = runTest(rule.dispatcher) {
        val realtime = RealtimeTripInfo(
            id = "entity-1",
            tripId = "trip-100",
            updatedAt = LocalDateTime.now(),
            stopTimeDelays = listOf("S1" to 60),
            vehicleLicencePlate = "ABC123",
        )
        val (vm, _, fakeRealtimeSource, _, _, fakeClockSource) = buildVm(
            departures = listOf(dep("100", "Downtown")),
            realtime = listOf(realtime),
        )
        val delay = { vm.associatedStopTimes.value.single().second.realtimeStopTimesRecord?.stopTimeDelay }
        assertEquals(Duration.ofSeconds(60), delay())

        fakeRealtimeSource.changeData(listOf(realtime.copy(stopTimeDelays = listOf("S1" to 300))))
        fakeClockSource.advanceMinutes()
        assertEquals(Duration.ofSeconds(300), delay())
    }

    // --- How each departure's time reads ---

    @Test
    fun `a departure with no realtime shows only its timetabled time`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(departures = listOf(dep("100", "Downtown")))

        assertEquals(DepartureTimeDisplay.TimetableOnly, vm.associatedStopTimes.value.single().second.timeDisplay)
    }

    @Test
    fun `realtime moving a departure by a minute or more shows it as moved`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(
            departures = listOf(dep("100", "Downtown")),
            realtime = listOf(
                RealtimeTripInfo(
                    id = "entity-1",
                    tripId = "trip-100",
                    updatedAt = LocalDateTime.now(),
                    stopTimeDelays = listOf("S1" to 120),
                    vehicleLicencePlate = "ABC123",
                )
            ),
        )

        assertEquals(DepartureTimeDisplay.Moved, vm.associatedStopTimes.value.single().second.timeDisplay)
    }

    // Under a minute renders as the same clock time, so there's nothing to strike through.
    @Test
    fun `realtime within the same minute shows the departure as on time`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(
            departures = listOf(dep("100", "Downtown")),
            realtime = listOf(
                RealtimeTripInfo(
                    id = "entity-1",
                    tripId = "trip-100",
                    updatedAt = LocalDateTime.now(),
                    stopTimeDelays = listOf("S1" to 30),
                    vehicleLicencePlate = "ABC123",
                )
            ),
        )

        assertEquals(DepartureTimeDisplay.OnTime, vm.associatedStopTimes.value.single().second.timeDisplay)
    }
}
