package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.StopRecord
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SavedStopsViewModelTest {
    @get:Rule val rule = MainDispatcherRule()

    private val stopLoc = LatLon(-33.8688, 151.2093)
    private val stop = GlobbedStopRecord(
        globbedStopId = "G1",
        globbedStopName = "Test Stop",
        stopRecords = listOf(StopRecord("S1", "Test Stop", stopLoc, false)),
    )

    private val filter100 = TransitFilterOptions.RouteShortName("100", TransitMode.BUS)
    private val filter200 = TransitFilterOptions.RouteShortName("200", TransitMode.BUS)

    // Standard type to return injected dependencies to inspect.
    private data class TestDependencies(
        val vm: SavedStopsViewModel,
        val gtfsStaticRepository: FakeStaticGtfsSource,
        val gtfsRealtimeRepository: FakeRealtimeSource,
        val locationRepository: FakeLocationSource,
        val settingsRepository: FakeSettingsSource,
    )

    // A saved-stop tap also drives Home (focus + filter combo), so the cross-screen tests need both
    // ViewModels over one TransitInfo. Everything else only needs the saved-stops VM.
    private data class TestVmPair(
        val vm: SavedStopsViewModel,
        val homeViewModel: HomeViewModel,
        val settingsRepository: FakeSettingsSource,
    )

    private fun TestScope.buildTransitInfo(
        gtfsStaticRepository: FakeStaticGtfsSource =
            FakeStaticGtfsSource(globbedStops = listOf(stop), stopTimesRecords = emptyList()),
        gtfsRealtimeRepository: FakeRealtimeSource = FakeRealtimeSource(),
        locationRepo: FakeLocationSource = FakeLocationSource(),
        settingsRepo: FakeSettingsSource = FakeSettingsSource(),
    ) = TransitInfo(
        gtfsStaticRepository,
        gtfsRealtimeRepository,
        locationRepo,
        settingsRepo,
        backgroundScope,
    )

    private fun TestScope.buildVm(
        entries: List<SavedStopEntry> = emptyList(),
    ): TestDependencies {
        val gtfsStaticRepository =
            FakeStaticGtfsSource(globbedStops = listOf(stop), stopTimesRecords = emptyList())
        val gtfsRealtimeRepository = FakeRealtimeSource()
        val locationRepo = FakeLocationSource()
        val settingsRepo = FakeSettingsSource(savedStops = entries)

        val transitInfo = buildTransitInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
        )

        return TestDependencies(
            SavedStopsViewModel(transitInfo),
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
        )
    }

    private fun TestScope.buildVmPair(entries: List<SavedStopEntry> = emptyList()): TestVmPair {
        val settingsRepo = FakeSettingsSource(savedStops = entries)
        val transitInfo = buildTransitInfo(settingsRepo = settingsRepo)
        return TestVmPair(SavedStopsViewModel(transitInfo), HomeViewModel(transitInfo), settingsRepo)
    }

    @Test
    fun `savedStops hydrates each stop with its filter combos`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
        val saved = vm.savedStops.value.single()
        assertEquals("G1", saved.stop.globbedStopId)
        assertEquals(listOf(setOf(filter100)), saved.combos)
    }

    @Test
    fun `toggleExpanded adds then removes the stop id`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(SavedStopEntry("G1")))
        assertEquals(emptySet<String>(), vm.expandedSavedStops.value)
        vm.toggleExpanded("G1")
        assertEquals(setOf("G1"), vm.expandedSavedStops.value)
        vm.toggleExpanded("G1")
        assertEquals(emptySet<String>(), vm.expandedSavedStops.value)
    }

    @Test
    fun `onSavedStopSelected focuses the stop and applies its combo to Home`() = runTest(rule.dispatcher) {
        val (vm, homeViewModel, settings) =
            buildVmPair(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))

        vm.onSavedStopSelected(stop, setOf(filter100))

        assertEquals(setOf(filter100), homeViewModel.selectedFiltersForBusStop.value)
        assertEquals("G1", settings.homeStopId.first())
    }

    @Test
    fun `naked onSavedStopSelected clears the Home filters`() = runTest(rule.dispatcher) {
        val (vm, homeViewModel, _) = buildVmPair(listOf(SavedStopEntry("G1")))
        // Start with something selected, so clearing is an observable change rather than a no-op.
        homeViewModel.toggleFilterForBusStops(filter100)

        vm.onSavedStopSelected(stop, emptySet())

        assertEquals(emptySet<TransitFilterOptions>(), homeViewModel.selectedFiltersForBusStop.value)
    }

    @Test
    fun `duplicate onSavedStopSelected navigates to Home once`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
        vm.navEvents.test {
            vm.onSavedStopSelected(stop, setOf(filter100))
            assertEquals(SavedNavEvent.NavigateToHome, awaitItem())
            vm.onSavedStopSelected(stop, setOf(filter100))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeCombo drops just that combo, keeping the stop`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100), listOf(filter200)))))
        vm.removeCombo("G1", setOf(filter100))
        val saved = vm.savedStops.value.single()
        assertEquals(listOf(setOf(filter200)), saved.combos)
    }

    @Test
    fun `clear-all shows confirmation, then removes the whole stop on confirm`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
        assertNull(vm.pendingClearAll.value)
        vm.onClearAllClicked(stop)
        assertEquals(stop, vm.pendingClearAll.value)
        vm.onConfirmClearAll()
        assertNull(vm.pendingClearAll.value)
        assertTrue(vm.savedStops.value.isEmpty())
    }

    @Test
    fun `dismissing clear-all keeps the stop`() = runTest(rule.dispatcher) {
        val (vm, _) = buildVm(listOf(SavedStopEntry("G1")))
        vm.onClearAllClicked(stop)
        vm.onDismissClearAll()
        assertNull(vm.pendingClearAll.value)
        assertEquals(1, vm.savedStops.value.size)
    }
}
