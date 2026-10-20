package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.ScheduledStopTime
import com.example.learning.repos.ServiceCalendar
import com.example.learning.repos.StopRecord
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class FilterViewModelTest {
    @get:Rule val rule = MainDispatcherRule()

    private val stopLoc = LatLon(-33.8688, 151.2093)
    private val soon = LocalDateTime.now().plusHours(1)
    private val today = LocalDate.now()
    private val calendar = ServiceCalendar(mapOf(today to setOf("svc")))
    private val stopId = "G1"

    // An interchange: buses at two stands, trains at a platform. Three stop records is the threshold
    // above which TransitInfo starts offering stand/platform filters at all.
    private val standA = StopRecord("S1", "Test Station, Stand A", stopLoc, false)
    private val standB = StopRecord("S2", "Test Station, Stand B", stopLoc, false)
    private val platform1 = StopRecord("S3", "Test Station, Platform 1", stopLoc, false)
    private val stop = GlobbedStopRecord(
        globbedStopId = stopId,
        globbedStopName = "Test Station",
        stopRecords = listOf(standA, standB, platform1),
    )

    private fun dep(
        route: String,
        headsign: String,
        mode: TransitMode = TransitMode.BUS,
        stopRecord: StopRecord = standA,
        at: LocalDateTime = soon,
    ) = ScheduledStopTime(
        tripId = "trip-$route",
        departureOffset = Duration.between(today.atStartOfDay(), at),
        arrivalOffset = Duration.between(today.atStartOfDay(), at),
        sequence = 1,
        routeId = "route-$route",
        serviceId = "svc",
        tripHeadsign = headsign,
        routeShortName = route,
        routeLongName = "Route $route",
        routeType = mode,
        globbedStopId = stopId,
        globbedStopName = "Test Station",
        stopId = stopRecord.stopId,
        stopName = stopRecord.stopName,
        stopLoc = stopLoc,
        wheelchairBoarding = false,
    )

    // Mixed modes so every tab is populated: routes 9/370/L90 also pin down the numeric-aware
    // chip ordering, and a single train service gives the Lines and Platforms tabs something.
    private val standardDepartures = listOf(
        dep("370", "Downtown"),
        dep("9", "Uptown", stopRecord = standB, at = soon.plusMinutes(5)),
        dep("L90", "Uptown", at = soon.plusMinutes(10)),
        dep("T1", "Central", mode = TransitMode.TRAIN, stopRecord = platform1, at = soon.plusMinutes(15)),
    )

    private val busRoute370 = TransitFilterOptions.RouteShortName("370", TransitMode.BUS)
    private val busRoute9 = TransitFilterOptions.RouteShortName("9", TransitMode.BUS)
    private val trainMode = TransitFilterOptions.TransportMode(TransitMode.TRAIN)

    // Standard type to return injected dependencies to inspect.
    private data class TestDependencies(
        val vm: FilterViewModel,
        val gtfsStaticRepository: FakeStaticGtfsSource,
        val gtfsRealtimeRepository: FakeRealtimeSource,
        val locationRepository: FakeLocationSource,
        val settingsRepository: FakeSettingsSource,
    )

    private fun TestScope.buildVm(
        departures: List<ScheduledStopTime> = standardDepartures,
        stops: List<GlobbedStopRecord> = listOf(stop),
        location: LatLon = stopLoc,
    ): TestDependencies {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = stops, scheduledStopTimes = departures, calendar = calendar)
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource(location)
        val settingsRepo = FakeSettingsSource()

        val transitInfo = buildTransitInfo(gtfsStaticRepository, gtfsRealtimeRepository, locationRepo, settingsRepo)

        return TestDependencies(
            FilterViewModel(transitInfo),
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
        )
    }

    // --- Staging ---

    // The FilterPage opens showing what Home currently has applied, so the user edits their existing
    // selection rather than starting from scratch. Seeding happens at construction, which is why the
    // filter has to be committed through Home before the FilterViewModel exists.
    @Test
    fun `staged selection seeds from the committed filters`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar))
        val homeViewModel = HomeViewModel(transitInfo)
        homeViewModel.toggleFilterForBusStops(busRoute370)

        val vm = FilterViewModel(transitInfo)

        assertEquals(setOf(busRoute370), vm.stagedFilters.value)
    }

    @Test
    fun `toggleStaged adds and removes without committing`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar))
        val homeViewModel = HomeViewModel(transitInfo)
        val vm = FilterViewModel(transitInfo)

        vm.toggleStaged(busRoute370)
        assertEquals(setOf(busRoute370), vm.stagedFilters.value)
        assertEquals(emptySet<TransitFilterOptions>(), homeViewModel.selectedFiltersForBusStop.value)

        vm.toggleStaged(busRoute370)
        assertEquals(emptySet<TransitFilterOptions>(), vm.stagedFilters.value)
    }

    @Test
    fun `applyStaging commits the staged set and emits PopBack`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar))
        val homeViewModel = HomeViewModel(transitInfo)
        val vm = FilterViewModel(transitInfo)

        vm.navEvents.test {
            vm.toggleStaged(busRoute370)
            vm.toggleStaged(trainMode)
            vm.applyStaging()

            assertEquals(setOf(busRoute370, trainMode), homeViewModel.selectedFiltersForBusStop.value)
            assertEquals(FilterNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Applied filters are pinned into the Home row so they stay visible (and re-toggleable) after
    // the user deselects them there.
    @Test
    fun `applied filters are pinned into the Home row`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar))
        val homeViewModel = HomeViewModel(transitInfo)
        val vm = FilterViewModel(transitInfo)

        vm.toggleStaged(busRoute370)
        vm.applyStaging()
        homeViewModel.toggleFilterForBusStops(busRoute370) // deselect it again on Home

        assertTrue(busRoute370 in homeViewModel.rowFilters.value)
    }

    @Test
    fun `resetStaging empties staging and leaves the committed set until apply`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar))
        val homeViewModel = HomeViewModel(transitInfo)
        homeViewModel.toggleFilterForBusStops(busRoute370)
        val vm = FilterViewModel(transitInfo)

        vm.resetStaging()
        assertEquals(emptySet<TransitFilterOptions>(), vm.stagedFilters.value)
        assertEquals(setOf(busRoute370), homeViewModel.selectedFiltersForBusStop.value)

        vm.applyStaging()
        assertEquals(emptySet<TransitFilterOptions>(), homeViewModel.selectedFiltersForBusStop.value)
    }

    // Back discards: the staged edits never reach Home.
    @Test
    fun `onBackClicked emits PopBack and discards staging`() = runTest(rule.dispatcher) {
        val transitInfo = buildTransitInfo(FakeStaticGtfsSource(listOf(stop), standardDepartures, calendar))
        val homeViewModel = HomeViewModel(transitInfo)
        val vm = FilterViewModel(transitInfo)

        vm.navEvents.test {
            vm.toggleStaged(busRoute370)
            vm.onBackClicked()

            assertEquals(FilterNavEvent.PopBack, awaitItem())
            assertEquals(emptySet<TransitFilterOptions>(), homeViewModel.selectedFiltersForBusStop.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // A single gesture that delivers two intents (fast double-tap, or a tap landing during the slide
    // transition) must pop once; the latch re-arms once the screen is shown again.
    @Test
    fun `duplicate applyStaging navigates once until the screen is shown again`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()

        vm.navEvents.test {
            vm.applyStaging()
            assertEquals(FilterNavEvent.PopBack, awaitItem())  // first tap pops
            vm.applyStaging()                                  // duplicate of the same gesture
            expectNoEvents()                                   // ...is dropped

            vm.onScreenResumed()                               // screen shown again
            vm.applyStaging()
            assertEquals(FilterNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Tabs ---

    @Test
    fun `tabs hold only the ones this stop has options for, broad to specific`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()

        assertEquals(
            listOf(
                FilterTabs.Modes,
                FilterTabs.Platforms,
                FilterTabs.Stands,
                FilterTabs.Lines,
                FilterTabs.Routes,
                FilterTabs.Destinations,
            ),
            vm.tabs.value,
        )
    }

    // A bus-only stop has no trains to distinguish, so Modes/Platforms/Lines never appear.
    @Test
    fun `a single-mode stop drops the tabs that do not apply`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(standardDepartures.filter { it.routeType == TransitMode.BUS })

        assertEquals(listOf(FilterTabs.Stands, FilterTabs.Routes, FilterTabs.Destinations), vm.tabs.value)
    }

    @Test
    fun `selectedFilterTab defaults to the first tab and follows the user's pick`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()

        assertEquals(FilterTabs.Modes, vm.selectedFilterTab.value)

        vm.selectFilterTab(FilterTabs.Destinations)
        assertEquals(FilterTabs.Destinations, vm.selectedFilterTab.value)
    }

    @Test
    fun `selectedFilterTab is null when the stop has no filters at all`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(dep("370", "Downtown")))

        assertNull(vm.selectedFilterTab.value)
        assertEquals(emptyList<TransitFilterOptions>(), vm.visibleOptions.value)
    }

    @Test
    fun `visibleOptions holds the selected tab's chips, numerics first`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()

        vm.selectFilterTab(FilterTabs.Routes)
        assertEquals(listOf("9", "370", "L90"), vm.visibleOptions.value.map { it.filterLabel })

        vm.selectFilterTab(FilterTabs.Lines)
        assertEquals(listOf("T1"), vm.visibleOptions.value.map { it.filterLabel })

        vm.selectFilterTab(FilterTabs.Platforms)
        assertEquals(listOf("Test Station, Platform 1"), vm.visibleOptions.value.map { it.filterLabel })
    }

    // Staging spans tabs: a chip checked on one tab stays checked after navigating away and back.
    @Test
    fun `staging survives switching tabs`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()

        vm.selectFilterTab(FilterTabs.Routes)
        vm.toggleStaged(busRoute9)
        vm.selectFilterTab(FilterTabs.Destinations)
        vm.selectFilterTab(FilterTabs.Routes)

        assertEquals(setOf(busRoute9), vm.stagedFilters.value)
        assertTrue(busRoute9 in vm.visibleOptions.value)
    }
}
