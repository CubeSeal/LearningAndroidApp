package com.example.learning.repos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class LocationMode {
    HighAccuracy,   // GPS + wifi/cell — walking nav, precise stop disambiguation
    Balanced,       // wifi/cell/bluetooth (~100m) — normal browsing
    LowPower,       // cell/wifi only (~10km) — battery-conscious
    Passive,        // no active request; piggyback on other apps' requests
    Off,            // no updates; StateFlow holds last known value
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepository(
    context: Context,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val _hasPermission = MutableStateFlow(false)
    fun onPermissionGranted() { _hasPermission.value = true }

    private val _mode = MutableStateFlow(LocationMode.Off)
    val mode: StateFlow<LocationMode> = _mode.asStateFlow()
    fun setMode(m: LocationMode) {
        _mode.value = m
    }

    val currentLocation: StateFlow<Location?> =
        combine(_hasPermission, _mode, ::Pair)
            .flatMapLatest { (hasPerm, mode) ->
                if (hasPerm) locationFlowFor(mode) else emptyFlow()
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    @SuppressLint("MissingPermission")
    private fun locationFlowFor(mode: LocationMode): Flow<Location> = when (mode) {
        // One time update
        LocationMode.Off -> flow { requestFreshFix()?.let { emit(it) } }
        // Continuous updates
        LocationMode.Passive -> updates(Priority.PRIORITY_PASSIVE, Long.MAX_VALUE, 5_000L)
        LocationMode.LowPower -> updates(Priority.PRIORITY_LOW_POWER, 30_000L, 15_000L)
        LocationMode.Balanced -> updates(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L, 5_000L)
        LocationMode.HighAccuracy -> updates(Priority.PRIORITY_HIGH_ACCURACY, 5_000L, 2_000L)
    }

    @SuppressLint("MissingPermission")
    private fun updates(priority: Int, intervalMs: Long, minIntervalMs: Long): Flow<Location> =
        callbackFlow {
            if (!_hasPermission.value) {
                close(SecurityException("No location permission")); return@callbackFlow
            }

            Log.d("Location", "Checked permissions.")

            val request = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .setMaxUpdateAgeMillis(60_000L)
                .setWaitForAccurateLocation(false)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    Log.d("Location", "Sending location.")
                    result.lastLocation?.let { trySend(it) }
                }
            }

            Log.d("Location", "Requesting location.")
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

            // Race in a cached fix — usually faster than waiting for the first callback
            launch {
                runCatching { fusedLocationClient.lastLocation.await() }
                    .getOrNull()?.let { trySend(it) }
            }

            awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
        }

    // One-shot fresh fix for pull-to-refresh — doesn't affect the stream
    @SuppressLint("MissingPermission")
    suspend fun requestFreshFix(
        priority: Int = Priority.PRIORITY_HIGH_ACCURACY,
    ): Location? {
        if (!_hasPermission.value) return null
        val cts = CancellationTokenSource()
        return runCatching {
            fusedLocationClient.getCurrentLocation(priority, cts.token).await()
        }.getOrNull()
    }

}
