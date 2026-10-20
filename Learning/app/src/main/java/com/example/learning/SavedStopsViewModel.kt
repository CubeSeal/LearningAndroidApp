package com.example.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.learning.repos.GlobbedStopRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SavedNavEvent {
    data object NavigateToHome : SavedNavEvent
}

class SavedStopsViewModel(
    private val transitInfo: TransitInfo
) : ViewModel() {
    val savedStops = transitInfo.savedStops

    private val _expandedSavedStops = MutableStateFlow(setOf<String>())
    val expandedSavedStops: StateFlow<Set<String>> = _expandedSavedStops.asStateFlow()

    // The stop pending a "clear all" confirmation (null = no dialog). Held here so the dialog is a
    // dumb renderer of VM state and the confirm/dismiss decision is testable.
    private val _pendingClearAll = MutableStateFlow<GlobbedStopRecord?>(null)
    val pendingClearAll: StateFlow<GlobbedStopRecord?> = _pendingClearAll.asStateFlow()

    private val _navEvents = Channel<SavedNavEvent>(Channel.BUFFERED)
    val navEvents: Flow<SavedNavEvent> = _navEvents.receiveAsFlow()

    private var navigating = false
    fun onScreenResumed() { navigating = false }

    fun toggleExpanded(stopId: String) {
        _expandedSavedStops.update { if (stopId in it) it - stopId else it + stopId }
    }

    fun removeCombo(stopId: String, combo: Set<TransitFilterOptions>) = viewModelScope.launch {
        transitInfo.removeSavedCombo(stopId, combo)
    }

    fun onClearAllClicked(stop: GlobbedStopRecord) { _pendingClearAll.value = stop }
    fun onDismissClearAll() { _pendingClearAll.value = null }
    fun onConfirmClearAll() {
        val stop = _pendingClearAll.value ?: return
        _pendingClearAll.value = null
        viewModelScope.launch { transitInfo.removeSavedStop(stop) }
    }

    fun onSavedStopSelected(stop: GlobbedStopRecord, combo: Set<TransitFilterOptions>) {
        if (navigating) return
        navigating = true
        viewModelScope.launch {
            transitInfo.selectSavedStop(stop, combo)
            _navEvents.trySend(SavedNavEvent.NavigateToHome)
        }
    }
}
