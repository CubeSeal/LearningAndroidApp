package com.example.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.learning.repos.GlobbedStopRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/** One-shot navigation intents emitted by [HomeViewModel]. The composable collects these and drives
 *  the NavController, so the *decision* to navigate lives (and is tested) in the ViewModel. */
sealed interface HomeNavEvent {
    data object OpenPickStop : HomeNavEvent
    data object OpenFilters : HomeNavEvent
    data class OpenTrip(val tripId: String, val stopId: String, val date: String) : HomeNavEvent
}

class HomeViewModel(
    private val transitInfo: TransitInfo
) : ViewModel() {
    val focusedBusStop = transitInfo.focusedBusStop

    val availableFiltersForBusStop = transitInfo.filtersForBusStop
    // If empty then we don't filter anything, but if not empty we only include what in the set.
    val selectedFiltersForBusStop = transitInfo.selectedFilters

    // The Home row shows a base slice of the available filters, plus any "pinned" extras promoted
    // from the FilterPage so they remain visible (and quickly re-toggleable) even after deselection.
    // A refresh clears the pins, resetting the row to the base slice.
    val rowFilters: StateFlow<List<TransitFilterOptions>> =
        combine(availableFiltersForBusStop, transitInfo.pinnedFilters) { available, pinned ->
            // Sort before capping, so the row keeps the highest-priority tiers rather than whichever
            // filters happened to be discovered first. Pinned extras are folded in and re-sorted so
            // they slot into place.
            (available.sorted().take(ROW_FILTER_CAP) + pinned.filter { it in available })
                .distinct()
                .sorted()
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
    // shown again ([onScreenResumed], forwarded from the composable's ON_RESUME).
    private var navigating = false
    fun onScreenResumed() { navigating = false }
    private fun navigate(event: HomeNavEvent) {
        if (navigating) return
        navigating = true
        _navEvents.trySend(event)
    }

    // Whether the focused stop keeps following the user's current location. Owned by TransitInfo
    // (survives independently of which screen is on the stack); this just re-exposes it.
    val followLocation: StateFlow<Boolean> = transitInfo.followLocation

    init {
        load()
        // Scroll the list back to the top whenever its contents change.
        associatedStopTimes.onEach { _scrollToTop.trySend(Unit) }.launchIn(viewModelScope)
    }

    // Turning follow-location ON re-fetches a fresh fix (see TransitInfo.setFollowLocation), so the
    // same refresh spinner used by pull-to-refresh shows while that's in flight. Turning it off is
    // instant (no location fetch), so the spinner stays untouched.
    fun toggleFollowLocation() = viewModelScope.launch {
        val enabling = !transitInfo.followLocation.value
        if (!enabling) {
            transitInfo.setFollowLocation(false)
            return@launch
        }
        _isRefreshing.update { true }
        try {
            transitInfo.setFollowLocation(true)
        } finally {
            _isRefreshing.update { false }
        }
    }

    // Pull-to-refresh: fresh data → reset the filter row to its base slice with nothing selected.
    // Startup deliberately doesn't clear — the filters live in TransitInfo, which outlives this
    // ViewModel, and a saved-stop combo commits them *before* navigating to a freshly-created Home
    // (resetTo pops the start destination inclusively, so that Home really is a new instance).
    fun refresh() {
//        transitInfo.clearFilters()
        load()
    }

    private fun load() = viewModelScope.launch {
        _isRefreshing.update { true }
        try {
            transitInfo.refresh()
        } finally {
            _isRefreshing.update { false }
        }
    }

    // Save the stop along with whatever filters are currently active (an empty set saves it "naked").
    fun addSavedStop(globbedBusStopRecord: GlobbedStopRecord) = viewModelScope.launch {
        val filters = selectedFiltersForBusStop.value
        transitInfo.saveStop(globbedBusStopRecord, filters)
        val suffix = if (filters.isEmpty()) "" else " with filters"
        _snackbarMessages.trySend("Saved ${globbedBusStopRecord.globbedStopName}$suffix")
    }

    fun toggleFilterForBusStops(busStopFilterOptions: TransitFilterOptions) =
        transitInfo.toggleFilter(busStopFilterOptions)

    // Reset filtering back to "show everything": clear the active selection and any pinned extras so
    // the row falls back to its base slice.
    fun clearFilters() = transitInfo.clearFilters()

    // --- Navigation actions (called by the composable) ---
    fun onEditStopClicked() = navigate(HomeNavEvent.OpenPickStop)
    fun onOpenFilters() = navigate(HomeNavEvent.OpenFilters)
    fun onDepartureClicked(record: StopTimesRecordWithRealtime) {
        val r = record.stopTimesRecord
        navigate(HomeNavEvent.OpenTrip(r.tripId, r.stopId, r.departureTime.toLocalDate().toString()))
    }

    companion object {
        const val ROW_FILTER_CAP = 10
    }
}
