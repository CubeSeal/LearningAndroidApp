package com.example.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.learning.repos.GlobbedStopRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PickStopNavEvent {
    data object PopBack : PickStopNavEvent
}

class PickStopViewModel(
    private val transitInfo: TransitInfo
) : ViewModel() {
    val closestBusStops = transitInfo.closestBusStops
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    val filteredBusStops: StateFlow<List<GlobbedStopRecord>> = _query
        .map { query -> if (query.isBlank()) emptyList() else transitInfo.searchStops(query) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchExpanded = MutableStateFlow(false)
    val searchExpanded: StateFlow<Boolean> = _searchExpanded.asStateFlow()

    private val _navEvents = Channel<PickStopNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<PickStopNavEvent> = _navEvents.receiveAsFlow()

    private var navigating = false
    fun onScreenResumed() { navigating = false }

    fun onQueryChange(q: String) { _query.value = q }
    fun onSearchExpandedChange(expanded: Boolean) { _searchExpanded.value = expanded }

    fun onStopSelected(stop: GlobbedStopRecord) {
        if (navigating) return
        navigating = true
        viewModelScope.launch {
            transitInfo.updateFocusedBusStop(stop)
            _navEvents.trySend(PickStopNavEvent.PopBack)
        }
    }

    fun onBackClicked() {
        if (navigating) return
        navigating = true
        _navEvents.trySend(PickStopNavEvent.PopBack)
    }
}
