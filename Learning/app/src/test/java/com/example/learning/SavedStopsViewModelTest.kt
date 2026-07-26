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

    private fun TestScope.buildInfo(
        settings: FakeSettingsSource = FakeSettingsSource(),
    ) = TransitInfo(
        gtfsStaticRepository = FakeStaticGtfsSource(
            globbedStops = listOf(stop),
            stopTimesRecords = emptyList(),
        ),
        gtfsRealtimeRepository = FakeRealtimeSource(),
        locationRepo = FakeLocationSource(),
        settingsRepo = settings,
        scope = backgroundScope,
    )

    private fun TestScope.buildVm(
        entries: List<SavedStopEntry> = emptyList(),
    ): SavedStopsViewModel = SavedStopsViewModel(buildInfo(FakeSettingsSource(savedStops = entries)))

    @Test
    fun `savedStops hydrates each stop with its filter combos`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
        val saved = vm.savedStops.value.single()
        assertEquals("G1", saved.stop.globbedStopId)
        assertEquals(listOf(setOf(filter100)), saved.combos)
    }

    @Test
    fun `toggleExpanded adds then removes the stop id`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(SavedStopEntry("G1")))
        assertEquals(emptySet<String>(), vm.expandedSavedStops.value)
        vm.toggleExpanded("G1")
        assertEquals(setOf("G1"), vm.expandedSavedStops.value)
        vm.toggleExpanded("G1")
        assertEquals(emptySet<String>(), vm.expandedSavedStops.value)
    }

    @Test
    fun `onSavedStopSelected focuses the stop and emits its combo`() = runTest(rule.dispatcher) {
        val settings = FakeSettingsSource(savedStops = listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
        val transitInfo = buildInfo(settings)
        val vm = SavedStopsViewModel(transitInfo)

        transitInfo.filterSelection.test {
            vm.onSavedStopSelected(stop, setOf(filter100))
            assertEquals(setOf(filter100), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("G1", settings.homeStopId.first())
    }

    @Test
    fun `naked onSavedStopSelected emits an empty selection`() = runTest(rule.dispatcher) {
        val transitInfo = buildInfo(FakeSettingsSource(savedStops = listOf(SavedStopEntry("G1"))))
        val vm = SavedStopsViewModel(transitInfo)
        transitInfo.filterSelection.test {
            vm.onSavedStopSelected(stop, emptySet())
            assertEquals(emptySet<TransitFilterOptions>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `duplicate onSavedStopSelected navigates to Home once`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
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
        val vm = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100), listOf(filter200)))))
        vm.removeCombo("G1", setOf(filter100))
        val saved = vm.savedStops.value.single()
        assertEquals(listOf(setOf(filter200)), saved.combos)
    }

    @Test
    fun `clear-all shows confirmation, then removes the whole stop on confirm`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(SavedStopEntry("G1", listOf(listOf(filter100)))))
        assertNull(vm.pendingClearAll.value)
        vm.onClearAllClicked(stop)
        assertEquals(stop, vm.pendingClearAll.value)
        vm.onConfirmClearAll()
        assertNull(vm.pendingClearAll.value)
        assertTrue(vm.savedStops.value.isEmpty())
    }

    @Test
    fun `dismissing clear-all keeps the stop`() = runTest(rule.dispatcher) {
        val vm = buildVm(listOf(SavedStopEntry("G1")))
        vm.onClearAllClicked(stop)
        vm.onDismissClearAll()
        assertNull(vm.pendingClearAll.value)
        assertEquals(1, vm.savedStops.value.size)
    }
}
