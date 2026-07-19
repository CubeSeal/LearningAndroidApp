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

/** The list's scroll position, pushed *into* the ViewModel by the composable so scroll-derived state
 *  (e.g. the header fade) can be computed here rather than in composition. */
data class ScrollPosition(val firstVisibleItemIndex: Int, val firstVisibleItemScrollOffset: Int)

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

    // --- Scroll-derived state (fed by the composable via onListScrolled) ---
    private val _scrollPosition = MutableStateFlow(ScrollPosition(0, 0))
    // The header fades as the list scrolls: fully hidden past the first item, otherwise linearly
    // interpolated over the first HEADER_FADE_DISTANCE px of scroll. Was a `derivedStateOf` in the
    // composable; now pure, testable state.
    val headerAlpha: StateFlow<Float> = _scrollPosition
        .map { pos ->
            if (pos.firstVisibleItemIndex > 1) 0f
            else (1f - (pos.firstVisibleItemScrollOffset / HEADER_FADE_DISTANCE)).coerceIn(0f, 1f)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    // One-shot effects. The list resets to the top whenever its contents change (new stop / refresh
    // / minute tick), and saving a stop surfaces a snackbar — both emitted here so the composable is
    // a dumb collector.
    private val _scrollToTop = Channel<Unit>(Channel.CONFLATED)
    val scrollToTop: Flow<Unit> = _scrollToTop.receiveAsFlow()
    private val _snackbarMessages = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarMessages.receiveAsFlow()

    private val _navEvents = Channel<HomeNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<HomeNavEvent> = _navEvents.receiveAsFlow()

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

    fun addSavedStop(globbedBusStopRecord: GlobbedStopRecord) = viewModelScope.launch {
        transitInfo.addSavedStop(globbedBusStopRecord)
        _snackbarMessages.trySend("Saved ${globbedBusStopRecord.globbedStopName}")
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

    // --- Scroll / navigation actions (called by the composable) ---
    fun onListScrolled(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        _scrollPosition.value = ScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    }

    fun onEditStopClicked() { _navEvents.trySend(HomeNavEvent.OpenPickStop) }
    fun onOpenFilters() { _navEvents.trySend(HomeNavEvent.OpenFilters) }
    fun onDepartureClicked(record: StopTimesRecordWithRealtime) {
        val r = record.stopTimesRecord
        _navEvents.trySend(HomeNavEvent.OpenTrip(r.tripId, r.stopId, r.departureTime.toLocalDate().toString()))
    }

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
        _navEvents.trySend(HomeNavEvent.PopBack)
    }

    companion object {
        const val ROW_FILTER_CAP = 10
        // How fast the header fades out, in pixels of scroll.
        private const val HEADER_FADE_DISTANCE = 400f
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
        .map { query -> if (query.isBlank()) closestBusStops.value else transitInfo.searchStops(query) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            closestBusStops.value
        )

    private val _selectedTab = MutableStateFlow(SearchTab.Search)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()
    private val _searchExpanded = MutableStateFlow(false)
    val searchExpanded: StateFlow<Boolean> = _searchExpanded.asStateFlow()

    private val _navEvents = Channel<PickStopNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<PickStopNavEvent> = _navEvents.receiveAsFlow()

    fun onQueryChange(q: String) { _query.value = q }
    fun onTabSelected(tab: SearchTab) { _selectedTab.value = tab }
    fun onSearchExpandedChange(expanded: Boolean) { _searchExpanded.value = expanded }
    fun removeSavedStop(globbedBusStopRecord: GlobbedStopRecord) = viewModelScope.launch {
        transitInfo.removeSavedStop(globbedBusStopRecord)
    }

    // Focus the chosen stop, then return to Home.
    fun onStopSelected(stop: GlobbedStopRecord) = viewModelScope.launch {
        transitInfo.updateFocusedBusStop(stop)
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

    // Where to scroll so the focused stop is in view (one item past it, nudged up 24px). Null until
    // the trip loads / when the focused stop isn't in this trip.
    val scrollTarget: StateFlow<ScrollTarget?> = stopTimesRecord
        .map { list ->
            val idx = list.indexOfFirst { it.stopId == stopId }
            if (idx >= 0 && list.isNotEmpty()) ScrollTarget(idx + 1, -24) else null
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _navEvents = Channel<TripsNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<TripsNavEvent> = _navEvents.receiveAsFlow()

    fun onBackClicked() { _navEvents.trySend(TripsNavEvent.PopBack) }
    // Focus the *globbed* stop (the station) tapped in the trip, then return to Home.
    fun onStopClicked(globbedStopId: String) = viewModelScope.launch {
        transitInfo.updateFocusedBusStopByStopId(globbedStopId)
        _navEvents.trySend(TripsNavEvent.PopBack)
    }
}
