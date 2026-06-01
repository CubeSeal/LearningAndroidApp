package com.example.learning.repos

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * State-based fake for [SettingsSource]. Holds real in-memory state in [MutableStateFlow]s, so a
 * test drives it through the same public API as production (`addSavedStop`, `setHomeStopId`) and
 * asserts on what the flows emit — never on which methods were called.
 */
class FakeSettingsSource(
    homeStopId: String? = null,
    savedStops: Set<String> = emptySet(),
) : SettingsSource {
    private val _homeStopId = MutableStateFlow(homeStopId)
    override val homeStopId: Flow<String?> = _homeStopId

    private val _savedStops = MutableStateFlow(savedStops)
    override val savedStops: Flow<Set<String>> = _savedStops

    override suspend fun setHomeStopId(stopId: String) {
        _homeStopId.value = stopId
    }

    override suspend fun addSavedStop(stopId: String) {
        _savedStops.value = _savedStops.value + stopId
    }

    override suspend fun removeSavedStop(stopId: String) {
        _savedStops.value = _savedStops.value - stopId
    }
}
