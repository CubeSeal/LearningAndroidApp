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

    private fun TestScope.buildVm(): TestDependencies {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = listOf(stop), stopTimesRecords = emptyList())
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
    fun `onStopSelected turns off follow-my-location`() = runTest(rule.dispatcher) {
        val (vm, _, _, _, fakeSettingsSource) = buildVm()

        assertTrue(fakeSettingsSource.followLocation.first())
        vm.onStopSelected(stop)
        assertFalse(fakeSettingsSource.followLocation.first())
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
        val transitInfo = TransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(globbedStops = listOf(stop, stop2)),
            gtfsRealtimeRepository = FakeRealtimeSource(),
            locationRepo = FakeLocationSource(),
            settingsRepo = FakeSettingsSource(),
            scope = backgroundScope,
        )
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
