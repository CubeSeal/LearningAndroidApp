package com.example.learning

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.example.learning.repos.GlobbedStopRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

            TripsViewModel(createSavedStateHandle(), app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            PickStopViewModel(app.repos.transitInfo)
        }
    }
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
            (available.take(ROW_FILTER_CAP) + pinned.filter { it in available }).distinct()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hasMoreFilters: StateFlow<Boolean> = availableFiltersForBusStop
        .map { it.size > ROW_FILTER_CAP }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val associatedStopTimes = combine(
        transitInfo.associatedStopTimes,
        transitInfo.currentMinute,
        selectedFiltersForBusStop
    ) { stopTimes, currentMinute, selectedFilters ->
        val pastBuffer = Duration.ofMinutes(2)
        val realTimeSorted = stopTimes.sortedBy {
            // Have to carry null delay's all the way through, since that's a valid state when the data just isn't there.
            // Only make the choice to treat it as zero when re-calculating departureTimes.
            it.stopTimesRecord.departureTime + (it.realtimeStopTimesRecord?.stopTimeDelay ?: Duration.ZERO)
        }

        realTimeSorted
            .mapNotNull {
                val newTime = it.stopTimesRecord.departureTime +
                    (it.realtimeStopTimesRecord?.stopTimeDelay ?: Duration.ZERO)
                val passesTimeFilter = newTime > currentMinute - pastBuffer
                val passesFilterSet = selectedFilters.isEmpty()
                        || it.applicableFilters.any { filter -> filter in selectedFilters }
                if (passesTimeFilter && passesFilterSet) (newTime >= currentMinute) to it else null
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList())


    val isUpToDate = transitInfo.gtfsStaticRepository.isUpToDate
    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        refresh()
        focusOnClosestStop()
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
    }

    fun toggleFilterForBusStops(busStopFilterOptions: TransitFilterOptions) {
        if (busStopFilterOptions in selectedFiltersForBusStop.value) {
            _selectedFiltersForBusStop.update { it - busStopFilterOptions}
        } else {
            _selectedFiltersForBusStop.update { it + busStopFilterOptions}
        }
    }

    // Commit the FilterPage's staged selection, pinning the chosen filters so they show in the Home row.
    fun applyFilters(selected: Set<TransitFilterOptions>) {
        _selectedFiltersForBusStop.value = selected
        _pinnedFilters.update { it + selected }
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
        .map { query -> if (query.isBlank()) closestBusStops.value else transitInfo.searchStops(query) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            closestBusStops.value
        )

    fun updateFocusedBusStop(stop: GlobbedStopRecord) = viewModelScope.launch { transitInfo.updateFocusedBusStop(stop) }
    fun onQueryChange(q: String) { _query.value = q }
    fun removeSavedStop(globbedBusStopRecord: GlobbedStopRecord) = viewModelScope.launch {
        transitInfo.removeSavedStop(globbedBusStopRecord)
    }
}

class TripsViewModel(
    savedStateHandle: SavedStateHandle,
    private val transitInfo: TransitInfo
) : ViewModel() {

    private val args: Trips = savedStateHandle.toRoute()
    val tripId = args.tripId
    val stopId = args.stopId
    val date = LocalDate.parse(args.date)
    val stopTimesRecord = flow {
        val result = transitInfo.getByTrip(tripId, date)
        emit(result)
        Log.d("VM", result.toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateFocusedBusStopByStopId(stopId: String) = viewModelScope.launch { transitInfo.updateFocusedBusStopByStopId(stopId) }
}
