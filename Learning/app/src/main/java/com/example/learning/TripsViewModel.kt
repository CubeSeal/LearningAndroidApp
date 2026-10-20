package com.example.learning

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Where the departure list should scroll to, computed by the ViewModel; the composable just applies
 *  it to its [androidx.compose.foundation.lazy.LazyListState]. */
data class ScrollTarget(val index: Int, val offset: Int)

/** The Trips screen's static header text, derived in the ViewModel so the composable stays a dumb
 *  renderer. Null until the trip loads. */
data class TripHeader(val routeShortName: String, val routeLongName: String)

sealed interface TripsNavEvent {
    data object PopBack : TripsNavEvent
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
