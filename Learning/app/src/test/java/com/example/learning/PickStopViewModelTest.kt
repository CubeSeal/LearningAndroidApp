package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.StopRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PickStopViewModelTest {
    @get:Rule val rule = MainDispatcherRule()

    private val stopLoc = LatLon(-33.8688, 151.2093)
    private val stop = GlobbedStopRecord(
        globbedStopId = "G1",
        globbedStopName = "Test Stop",
        stopRecords = listOf(StopRecord("S1", "Test Stop", stopLoc, false)),
    )

    // Standard type to return injected dependencies to inspect.
    private data class TestDependencies(
        val vm: PickStopViewModel,
        val gtfsStaticRepository: FakeStaticGtfsSource,
        val gtfsRealtimeRepository: FakeRealtimeSource,
        val locationRepository: FakeLocationSource,
        val settingsRepository: FakeSettingsSource,
    )

    // Far from the phone, so following location never focuses it on its own.
    private val farStop = GlobbedStopRecord(
        globbedStopId = "G2",
        globbedStopName = "Wynyard",
        stopRecords = listOf(StopRecord("S9", "Wynyard", LatLon(-33.9, 151.3), false)),
    )

    private fun TestScope.buildVm(stops: List<GlobbedStopRecord> = listOf(stop)): TestDependencies {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = stops, scheduledStopTimes = emptyList())
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource()
        val settingsRepo = FakeSettingsSource()

        val transitInfo = buildTransitInfo(gtfsStaticRepository, gtfsRealtimeRepository, locationRepo, settingsRepo)

        return TestDependencies(
            PickStopViewModel(transitInfo),
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
        )
    }

    @Test
    fun `searchExpanded defaults to false`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun `onSearchExpandedChange updates searchExpanded`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.onSearchExpandedChange(true)
        assertTrue(vm.searchExpanded.value)
        vm.onSearchExpandedChange(false)
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun `onStopSelected emits PopBack`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.navEvents.test {
            vm.onStopSelected(stop)
            assertEquals(PickStopNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `duplicate onStopSelected pops back once`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.navEvents.test {
            vm.onStopSelected(stop)
            assertEquals(PickStopNavEvent.PopBack, awaitItem())
            vm.onStopSelected(stop)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onStopSelected closes the search`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.onSearchExpandedChange(true)

        vm.onStopSelected(stop)
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun `onStopSelected turns off follow-my-location`() = runTest(rule.dispatcher) {
        val (vm, _, _, _, fakeSettingsSource) = buildVm()

        assertTrue(fakeSettingsSource.followLocation.first())
        vm.onStopSelected(stop)
        assertFalse(fakeSettingsSource.followLocation.first())
    }

    // Pressing search on the keyboard is a shortcut for tapping the top result.
    @Test
    fun `submitting a search picks the top result and goes back`() = runTest(rule.dispatcher) {
        val (vm, _, _, _, fakeSettingsSource) = buildVm(stops = listOf(stop, farStop))
        vm.onQueryChange("Wynyard")
        vm.onSearchExpandedChange(true)

        vm.navEvents.test {
            vm.onSearchSubmitted()
            assertEquals(PickStopNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("G2", fakeSettingsSource.homeStopId.first())
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun `submitting a search with no results stays put`() = runTest(rule.dispatcher) {
        val (vm, _, _, _, fakeSettingsSource) = buildVm(stops = listOf(stop, farStop))
        vm.onQueryChange("Nowhere")
        vm.onSearchExpandedChange(true)

        vm.navEvents.test {
            vm.onSearchSubmitted()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        // Still following, still on the closest stop: nothing was picked.
        assertTrue(fakeSettingsSource.followLocation.first())
        assertEquals("G1", fakeSettingsSource.homeStopId.first())
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun filteredStopsIsEmptyListWhenQueryEmpty() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm()
        vm.onQueryChange("")
        assertTrue(vm.filteredBusStops.value.isEmpty())
    }

    @Test
    fun showStopsInFilteredList() = runTest(rule.dispatcher) {
        val stop2 = GlobbedStopRecord(
            globbedStopId = "G2",
            globbedStopName = "Wynyard",
            stopRecords = listOf(
                StopRecord("S1", "Test Stop 1", stopLoc, false),
                StopRecord("S2", "Test Stop 2", stopLoc, false),
            )
        )
        val transitInfo = buildTransitInfo(gtfsStaticRepository = FakeStaticGtfsSource(globbedStops = listOf(stop, stop2)))
        val vm = PickStopViewModel(transitInfo)

        vm.filteredBusStops.test {
            skipItems(1)
            vm.onQueryChange("Wynyard")
            val value = awaitItem()
            assertEquals("value = $value", stop2, value.firstOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
