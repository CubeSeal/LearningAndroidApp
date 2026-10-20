package com.example.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface FilterNavEvent {
    data object PopBack : FilterNavEvent
}

/**
 * The FilterPage's tab categories, broad -> specific matching [TransitFilterOptions.typeRank]. Trains
 * and buses use different terminology for the same facet (platforms vs stands, lines vs routes), so
 * those tiers split into a train and a bus tab; destinations are mode-agnostic.
 */
enum class FilterTabs(val title: String) {
    Modes("Modes"),
    Platforms("Platforms"),
    Stands("Stands"),
    Lines("Lines"),
    Routes("Routes"),
    Destinations("Destinations"),
}

/** Which [FilterTabs] tab a filter option belongs under. */
private fun TransitFilterOptions.toFilterTab(): FilterTabs =
    when (this) {
        is TransitFilterOptions.TransportMode -> FilterTabs.Modes
        is TransitFilterOptions.StopStand ->
            if (mode == TransitMode.TRAIN) FilterTabs.Platforms else FilterTabs.Stands
        is TransitFilterOptions.RouteShortName ->
            if (mode == TransitMode.TRAIN) FilterTabs.Lines else FilterTabs.Routes
        is TransitFilterOptions.TripHeadsign -> FilterTabs.Destinations
    }

/**
 * View Model for Filter Page
 *
 * Scoped to the Filter destination, so the staged selection lives exactly as long as the screen and
 * backing out discards it. Apply commits to [TransitInfo], which is where Home reads it from.
 */
class FilterViewModel(
    private val transitInfo: TransitInfo
) : ViewModel() {
    // Nav channel
    private val _navEvents = Channel<FilterNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<FilterNavEvent> = _navEvents.receiveAsFlow()

    // Navigation locking
    private var navigating = false

    // Seeded at construction, which is when the screen opens.
    private val _stagedFilters = MutableStateFlow(transitInfo.selectedFilters.value)
    val stagedFilters: StateFlow<Set<TransitFilterOptions>> = _stagedFilters.asStateFlow()

    private val filterOptionsInTabs: StateFlow<Map<FilterTabs, List<TransitFilterOptions>>> =
        transitInfo.filtersForBusStop
            .map { available -> available.groupBy { it.toFilterTab() }.mapValues { it.value.sorted() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Only the tabs this stop has options for — a single-mode stop never shows the other mode's tabs.
    // Filtering the entries (rather than reading the group's keys) keeps them in declaration order.
    val tabs: StateFlow<List<FilterTabs>> = filterOptionsInTabs
        .map { options -> FilterTabs.entries.filter { it in options } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Falls back to the first tab until the user picks one, and again if their pick stops existing
    // (the stop changed underneath).
    private val _tabSelection = MutableStateFlow<FilterTabs?>(null)
    val selectedFilterTab: StateFlow<FilterTabs?> =
        combine(_tabSelection, tabs) { selection, tabs -> selection?.takeIf { it in tabs } ?: tabs.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The selected tab's chips, in sorted() order.
    val visibleOptions: StateFlow<List<TransitFilterOptions>> =
        combine(filterOptionsInTabs, selectedFilterTab) { options, tab -> options[tab].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onScreenResumed() { navigating = false }

    private fun navigate(event: FilterNavEvent) {
        if (navigating) return
        navigating = true
        _navEvents.trySend(event)
    }

    // --- FilterPage staging actions ---
    fun selectFilterTab(tab: FilterTabs) { _tabSelection.value = tab }
    fun toggleStaged(option: TransitFilterOptions) {
        _stagedFilters.update { if (option in it) it - option else it + option }
    }
    fun resetStaging() { _stagedFilters.value = emptySet() }
    // Commit the staged selection and return to Home.
    fun applyStaging() {
        transitInfo.applyFilters(_stagedFilters.value)
        navigate(FilterNavEvent.PopBack)
    }
    // The FilterPage's back button (discards the staged selection and returns).
    fun onBackClicked() = navigate(FilterNavEvent.PopBack)
}
