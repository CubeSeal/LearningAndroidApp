package com.example.learning

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.example.learning.repos.GlobbedStopRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate

object AppViewModelProvider {
    val Factory = viewModelFactory {

        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            HomeViewModel(app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)
            val args: Trips = createSavedStateHandle().toRoute()
            TripsViewModel(args.tripId, args.stopId, LocalDate.parse(args.date), app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            PickStopViewModel(app.repos.transitInfo)
        }
    }
}

/** The two tabs of the stop picker. Owned by [PickStopViewModel] so the selection survives config
 *  changes and is testable without rendering. */
enum class SearchTab(val label: String) {
    Search("Search"),
    Saved("Saved")
}

/** Where the departure list should scroll to, computed by the ViewModel; the composable just applies
 *  it to its [androidx.compose.foundation.lazy.LazyListState]. */
data class ScrollTarget(val index: Int, val offset: Int)

/** The Trips screen's static header text, derived in the ViewModel so the composable stays a dumb
 *  renderer. Null until the trip loads. */
data class TripHeader(val routeShortName: String, val routeLongName: String)

/** One-shot navigation intents emitted by [HomeViewModel]. The composable collects these and drives
 *  the NavController, so the *decision* to navigate lives (and is tested) in the ViewModel. */
sealed interface HomeNavEvent {
    data object OpenPickStop : HomeNavEvent
    data object OpenFilters : HomeNavEvent
    data object PopBack : HomeNavEvent
    data class OpenTrip(val tripId: String, val stopId: String, val date: String) : HomeNavEvent
}

sealed interface PickStopNavEvent {
    data object PopBack : PickStopNavEvent
}

sealed interface TripsNavEvent {
    data object PopBack : TripsNavEvent
}

class HomeViewModel(
    private val transitInfo: TransitInfo
) : ViewModel() {
    val focusedBusStop = transitInfo.focusedBusStop

    val availableFiltersForBusStop = transitInfo.filtersForBusStop
    // If empty then we don't filter anything, but if not empty we only include what in the set.
    private val _selectedFiltersForBusStop = MutableStateFlow(setOf<TransitFilterOptions>())
    val selectedFiltersForBusStop = _selectedFiltersForBusStop.asStateFlow()

    // The Home row shows a base slice of the available filters, plus any "pinned" extras promoted
    // from the FilterPage so they remain visible (and quickly re-toggleable) even after deselection.
    // A refresh clears the pins, resetting the row to the base slice.
    private val _pinnedFilters = MutableStateFlow(setOf<TransitFilterOptions>())
    val rowFilters: StateFlow<List<TransitFilterOptions>> =
        combine(availableFiltersForBusStop, _pinnedFilters) { available, pinned ->
            // Sort the full set into hierarchy order (Modes → Stands/Platforms → Routes/Lines →
            // Destinations, then train-before-bus, then numeric-aware alphabetical by label) first,
            // so the row cap keeps the highest-priority tiers rather than whichever filters happened
            // to be discovered first. Pinned extras are then folded in and re-sorted so they slot
            // into place.
            val order = compareBy(filterTypeRank, filterModeRank)
                .thenBy { filterLabel(it).toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { filterLabel(it) }
            (available.sortedWith(order).take(ROW_FILTER_CAP) + pinned.filter { it in available })
                .distinct()
                .sortedWith(order)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hasMoreFilters: StateFlow<Boolean> = availableFiltersForBusStop
        .map { it.size > ROW_FILTER_CAP }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val associatedStopTimes = combine(
        transitInfo.associatedStopTimes,   // already time-sorted at the domain layer
        transitInfo.filterIndex,
        transitInfo.currentMinute,
        selectedFiltersForBusStop
    ) { sorted, filterIndex, currentMinute, selectedFilters ->
        val pastBuffer = Duration.ofMinutes(2)
        // No filter selected: use the pre-sorted full list as-is (no sort). With filters selected:
        // pull only the matching departures from the inverted index (a departure can match more than
        // one selected filter, so dedup), then sort just that smaller subset. Either way the full
        // list is never re-sorted on a filter toggle.
        val candidates =
            if (selectedFilters.isEmpty()) sorted
            else selectedFilters.flatMap { filterIndex[it].orEmpty() }
                .distinct()
                .sortedBy { it.effectiveDepartureTime }

        candidates
            .mapNotNull {
                val newTime = it.effectiveDepartureTime
                if (newTime > currentMinute - pastBuffer) (newTime >= currentMinute) to it else null
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList())


    val isUpToDate = transitInfo.gtfsStaticRepository.isUpToDate
    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing = _isRefreshing.asStateFlow()

    // One-shot effects. The list resets to the top whenever its contents change (new stop / refresh
    // / minute tick), and saving a stop surfaces a snackbar — both emitted here so the composable is
    // a dumb collector.
    private val _scrollToTop = Channel<Unit>(Channel.CONFLATED)
    val scrollToTop: Flow<Unit> = _scrollToTop.receiveAsFlow()
    private val _snackbarMessages = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarMessages.receiveAsFlow()

    private val _navEvents = Channel<HomeNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<HomeNavEvent> = _navEvents.receiveAsFlow()

    // Guards against a single user gesture producing two navigations: a fast double-tap, or a tap
    // landing during the slide transition while the leaving screen is still interactive, both queue
    // two intents on the channel and previously pushed the destination twice (forcing a double
    // back-press). Once we emit a nav intent the latch drops any further one until the screen is
    // shown again ([onScreenResumed], forwarded from the composable's ON_RESUME) — reset on resume
    // because this same ViewModel drives both Home→Filter and Filter→Home navigation.
    private var navigating = false
    fun onScreenResumed() { navigating = false }
    private fun navigate(event: HomeNavEvent) {
        if (navigating) return
        navigating = true
        _navEvents.trySend(event)
    }

    // --- FilterPage staging (shared: FilterScreen uses this same back-stack-scoped ViewModel) ---
    private val _stagedFilters = MutableStateFlow(setOf<TransitFilterOptions>())
    val stagedFilters: StateFlow<Set<TransitFilterOptions>> = _stagedFilters.asStateFlow()
    // Which filter groups have had their "…" overflow chip flicked open.
    private val _expandedFilterGroups = MutableStateFlow(setOf<String>())
    val expandedFilterGroups: StateFlow<Set<String>> = _expandedFilterGroups.asStateFlow()

    init {
        refresh()
        focusOnClosestStop()
        // Scroll the list back to the top whenever its contents change.
        associatedStopTimes.onEach { _scrollToTop.trySend(Unit) }.launchIn(viewModelScope)
        // A combo picked from the saved-stops list applies its filters here (empty set = the naked
        // stop, so clear). Home is retained on the back stack while PickStop is on top, so this
        // collector is live when the selection arrives.
        transitInfo.filterSelection
            .onEach { if (it.isEmpty()) clearFilters() else applyFilters(it) }
            .launchIn(viewModelScope)
    }

    private fun focusOnClosestStop() = viewModelScope.launch {
        transitInfo.closestBusStops.first { it.isNotEmpty() }.firstOrNull()?.let {
            Log.d("VM", "Updating focused bus stop after refresh.")
            transitInfo.updateFocusedBusStop(it)
        }
    }

    fun refresh() = viewModelScope.launch {
        _isRefreshing.update { true }
        // Fresh data → reset the filter row to its base slice with nothing selected.
        _pinnedFilters.value = emptySet()
        _selectedFiltersForBusStop.value = emptySet()
        try {
            transitInfo.refresh()
        } finally {
            _isRefreshing.update { false }
        }
    }

    // Save the stop along with whatever filters are currently active (an empty set saves it "naked").
    fun addSavedStop(globbedBusStopRecord: GlobbedStopRecord) = viewModelScope.launch {
        val filters = _selectedFiltersForBusStop.value
        transitInfo.saveStop(globbedBusStopRecord, filters)
        val suffix = if (filters.isEmpty()) "" else " with filters"
        _snackbarMessages.trySend("Saved ${globbedBusStopRecord.globbedStopName}$suffix")
    }

    fun toggleFilterForBusStops(busStopFilterOptions: TransitFilterOptions) {
        if (busStopFilterOptions in selectedFiltersForBusStop.value) {
            _selectedFiltersForBusStop.update { it - busStopFilterOptions}
        } else {
            _selectedFiltersForBusStop.update { it + busStopFilterOptions}
        }
    }

    // Commit a staged selection, pinning the chosen filters so they show in the Home row.
    fun applyFilters(selected: Set<TransitFilterOptions>) {
        _selectedFiltersForBusStop.value = selected
        _pinnedFilters.update { it + selected }
    }

    // Reset filtering back to "show everything": clear the active selection and any pinned extras so
    // the row falls back to its base slice.
    fun clearFilters() {
        _selectedFiltersForBusStop.value = emptySet()
        _pinnedFilters.value = emptySet()
    }

    // --- Navigation actions (called by the composable) ---
    fun onEditStopClicked() = navigate(HomeNavEvent.OpenPickStop)
    fun onOpenFilters() = navigate(HomeNavEvent.OpenFilters)
    fun onDepartureClicked(record: StopTimesRecordWithRealtime) {
        val r = record.stopTimesRecord
        navigate(HomeNavEvent.OpenTrip(r.tripId, r.stopId, r.departureTime.toLocalDate().toString()))
    }
    // The FilterPage's back button (discards the staged selection and returns).
    fun onFilterBackClicked() = navigate(HomeNavEvent.PopBack)

    // --- FilterPage staging actions ---
    // Seed the staged selection from the committed one when the filter screen opens.
    fun beginStaging() {
        _stagedFilters.value = _selectedFiltersForBusStop.value
        _expandedFilterGroups.value = emptySet()
    }
    fun toggleStaged(option: TransitFilterOptions) {
        _stagedFilters.update { if (option in it) it - option else it + option }
    }
    fun expandFilterGroup(title: String) { _expandedFilterGroups.update { it + title } }
    fun resetStaging() {
        _stagedFilters.value = emptySet()
        _expandedFilterGroups.value = emptySet()
    }
    // Commit the staged selection and return to Home.
    fun applyStaging() {
        applyFilters(_stagedFilters.value)
        navigate(HomeNavEvent.PopBack)
    }

    companion object {
        const val ROW_FILTER_CAP = 10
    }
}

class PickStopViewModel(
    private val transitInfo: TransitInfo
) : ViewModel() {
    val closestBusStops = transitInfo.closestBusStops
    val savedStops = transitInfo.savedStops
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    val filteredBusStops: StateFlow<List<GlobbedStopRecord>> = _query
        .map { query -> if (query.isBlank()) emptyList() else transitInfo.searchStops(query) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    private val _selectedTab = MutableStateFlow(SearchTab.Search)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()
    private val _searchExpanded = MutableStateFlow(false)
    val searchExpanded: StateFlow<Boolean> = _searchExpanded.asStateFlow()

    // Which saved stops have their combo dropdown expanded (keyed by globbedStopId) — mirrors the
    // FilterPage's expandedFilterGroups pattern.
    private val _expandedSavedStops = MutableStateFlow(setOf<String>())
    val expandedSavedStops: StateFlow<Set<String>> = _expandedSavedStops.asStateFlow()

    // The stop pending a "clear all" confirmation (null = no dialog). Held here so the dialog is a
    // dumb renderer of VM state and the confirm/dismiss decision is testable.
    private val _pendingClearAll = MutableStateFlow<GlobbedStopRecord?>(null)
    val pendingClearAll: StateFlow<GlobbedStopRecord?> = _pendingClearAll.asStateFlow()

    private val _navEvents = Channel<PickStopNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<PickStopNavEvent> = _navEvents.receiveAsFlow()

    // See HomeViewModel.navigating — drops the duplicate intent from a single gesture so the screen
    // pops once. Checked synchronously (before launching) so it wins the race against the second tap.
    private var navigating = false
    fun onScreenResumed() { navigating = false }

    fun onQueryChange(q: String) {  _query.value = q }
    fun onTabSelected(tab: SearchTab) { _selectedTab.value = tab }
    fun onSearchExpandedChange(expanded: Boolean) { _searchExpanded.value = expanded }

    fun toggleExpanded(stopId: String) {
        _expandedSavedStops.update { if (stopId in it) it - stopId else it + stopId }
    }

    // Remove a single saved filter combo from a stop (no confirmation — the stop itself stays saved).
    fun removeCombo(stopId: String, combo: Set<TransitFilterOptions>) = viewModelScope.launch {
        transitInfo.removeSavedCombo(stopId, combo)
    }

    // Clear-all is destructive (wipes the stop + every combo), so it goes through a confirmation.
    fun onClearAllClicked(stop: GlobbedStopRecord) { _pendingClearAll.value = stop }
    fun onDismissClearAll() { _pendingClearAll.value = null }
    fun onConfirmClearAll() {
        val stop = _pendingClearAll.value ?: return
        _pendingClearAll.value = null
        viewModelScope.launch { transitInfo.removeSavedStop(stop) }
    }

    // Focus the chosen stop (search tab), then return to Home. Leaves the active filters untouched.
    fun onStopSelected(stop: GlobbedStopRecord) {
        if (navigating) return
        navigating = true
        viewModelScope.launch {
            transitInfo.updateFocusedBusStop(stop)
            _navEvents.trySend(PickStopNavEvent.PopBack)
        }
    }

    // Focus a saved stop and apply [combo] (empty = naked), then return to Home.
    fun onSavedStopSelected(stop: GlobbedStopRecord, combo: Set<TransitFilterOptions>) {
        if (navigating) return
        navigating = true
        viewModelScope.launch {
            transitInfo.selectSavedStop(stop, combo)
            _navEvents.trySend(PickStopNavEvent.PopBack)
        }
    }

    fun onBackClicked() {
        if (navigating) return
        navigating = true
        _navEvents.trySend(PickStopNavEvent.PopBack)
    }
}

class TripsViewModel(
    val tripId: String,
    val stopId: String,
    val date: LocalDate,
    private val transitInfo: TransitInfo,
) : ViewModel() {
    val stopTimesRecord = flow {
        val result = transitInfo.getByTrip(tripId, date)
        emit(result)
        Log.d("VM", result.toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // The static header (route names), taken off the trip's first stop. Null until the trip loads.
    val tripHeader: StateFlow<TripHeader?> = stopTimesRecord
        .map { list -> list.firstOrNull()?.let { TripHeader(it.routeShortName.orEmpty(), it.routeLongName) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Where to scroll so the focused stop's row is in view (nudged down 24px so it isn't flush at the
    // top). The route header is a static element outside the list, so the row's list index is just
    // its position in the trip. Null until the trip loads / when the focused stop isn't in this trip.
    val scrollTarget: StateFlow<ScrollTarget?> = stopTimesRecord
        .map { list ->
            val idx = list.indexOfFirst { it.stopId == stopId }
            if (idx >= 0 && list.isNotEmpty()) ScrollTarget(idx, -24) else null
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _navEvents = Channel<TripsNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<TripsNavEvent> = _navEvents.receiveAsFlow()

    // See HomeViewModel.navigating — drops the duplicate intent from a single gesture so the screen
    // pops once. Checked synchronously (before launching) so it wins the race against the second tap.
    private var navigating = false
    fun onScreenResumed() { navigating = false }

    fun onBackClicked() {
        if (navigating) return
        navigating = true
        _navEvents.trySend(TripsNavEvent.PopBack)
    }
    // Focus the *globbed* stop (the station) tapped in the trip, then return to Home.
    fun onStopClicked(globbedStopId: String) {
        if (navigating) return
        navigating = true
        viewModelScope.launch {
            transitInfo.updateFocusedBusStopByStopId(globbedStopId)
            _navEvents.trySend(TripsNavEvent.PopBack)
        }
    }
}
