package com.example.learning.repos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State-based fake for [LocationSource]. Tests drive position with [emit]; `requestFreshFix` is a
 * no-op since there's no real fused-location provider to query.
 */
class FakeLocationSource(
    location: LatLon? = null,
) : LocationSource {
    private val _currentLocation = MutableStateFlow(location)
    override val currentLocation: StateFlow<LatLon?> = _currentLocation

    fun emit(latLon: LatLon?) {
        _currentLocation.value = latLon
    }

    override suspend fun requestFreshFix() = Unit
}
