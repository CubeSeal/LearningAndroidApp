package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.StopRecord
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

    private fun TestScope.buildVm(): PickStopViewModel {
        val transitInfo = TransitInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(
                stopsById = mapOf("G1" to stop),
                closest = listOf(stop),
            ),
            gtfsRealtimeRepository = FakeRealtimeSource(),
            locationRepo = FakeLocationSource(),
            settingsRepo = FakeSettingsSource(),
            scope = backgroundScope,
        )
        return PickStopViewModel(transitInfo)
    }

    @Test
    fun `selectedTab defaults to Search`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        assertEquals(SearchTab.Search, vm.selectedTab.value)
    }

    @Test
    fun `onTabSelected updates selectedTab`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.onTabSelected(SearchTab.Saved)
        assertEquals(SearchTab.Saved, vm.selectedTab.value)
    }

    @Test
    fun `searchExpanded defaults to false`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun `onSearchExpandedChange updates searchExpanded`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.onSearchExpandedChange(true)
        assertTrue(vm.searchExpanded.value)
        vm.onSearchExpandedChange(false)
        assertFalse(vm.searchExpanded.value)
    }

    @Test
    fun `onStopSelected emits PopBack`() = runTest(rule.dispatcher) {
        val vm = buildVm()
        vm.navEvents.test {
            vm.onStopSelected(stop)
            assertEquals(PickStopNavEvent.PopBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
