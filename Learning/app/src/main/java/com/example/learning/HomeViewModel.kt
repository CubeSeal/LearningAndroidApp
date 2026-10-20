package com.example.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.departuresFrom
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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

    // How many departures the list shows; grows a page at a time as the user scrolls (onScrolledTo).
    private val pageSize = MutableStateFlow(PAGE_SIZE)

    // The timetable rows the list is stitched from. No filter selected: the whole stop. With filters
    // selected: only the matching rows, pulled from the inverted index (a row can match more than one
    // selected filter, so dedup) — so a rare route's departures days out cost no more than the next few.
    private val candidateRows = combine(
        transitInfo.schedule,
        transitInfo.filterIndex,
        selectedFiltersForBusStop,
    ) { schedule, filterIndex, selectedFilters ->
        val rows =
            if (selectedFilters.isEmpty()) schedule.stopTimes
            else selectedFilters.flatMap { filterIndex[it].orEmpty() }.distinct()
        schedule.copy(stopTimes = rows)
    }

    // Eagerly, not WhileSubscribed: onScrolledTo reads the current page off .value.
    val associatedStopTimes = combine(
        candidateRows,
        transitInfo.currentMinute,
        transitInfo.realtimeStopTimes,
        pageSize,
    ) { schedule, currentMinute, realtime, pageSize ->
        val pastBuffer = Duration.ofMinutes(2)
        // Stitch from further back than is shown, so a departure running late past its scheduled
        // time is still picked up by its realtime-adjusted time.
        schedule.stopTimes.departuresFrom(currentMinute - REALTIME_LOOKBACK, schedule.calendar)
            .withRealtime(realtime)
            .filter { it.effectiveDepartureTime > currentMinute - pastBuffer }
            .take(pageSize)
            .sortedBy { it.effectiveDepartureTime }
            .map { (it.effectiveDepartureTime >= currentMinute) to it }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    val isUpToDate = transitInfo.gtfsStaticRepository.isUpToDate
    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing = _isRefreshing.asStateFlow()

    // One-shot effects. The list resets to the top when it becomes a different list (new stop or
    // filter selection), and saving a stop surfaces a snackbar — both emitted here so the composable
    // is a dumb collector.
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
        // A new stop or filter selection starts over: one page, from the top. Loading more pages and
        // the minute tick only extend or trim the current list, so they leave the scroll alone.
        combine(focusedBusStop.filterNotNull(), selectedFiltersForBusStop) { stop, filters ->
            stop.globbedStopId to filters
        }
            .distinctUntilChanged()
            .onEach {
                pageSize.value = PAGE_SIZE
                _scrollToTop.trySend(Unit)
            }
            .launchIn(viewModelScope)
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
        navigate(HomeNavEvent.OpenTrip(r.tripId, r.stopId, r.serviceDate.toString()))
    }

    // Forwarded from the list's scroll position. Only a full page grows: a shorter list has already
    // run out of timetable.
    fun onScrolledTo(lastVisibleIndex: Int) {
        val shown = associatedStopTimes.value.size
        if (shown == pageSize.value && lastVisibleIndex >= shown - PAGE_PREFETCH) pageSize.update { it + PAGE_SIZE }
    }

    companion object {
        const val ROW_FILTER_CAP = 10
        const val PAGE_SIZE = 50
        private const val PAGE_PREFETCH = 10
        private val REALTIME_LOOKBACK: Duration = Duration.ofHours(1)
    }
}
