package com.example.learning.repos

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class LocationMode {
    HighAccuracy,   // GPS + wifi/cell — walking nav, precise stop disambiguation
    Balanced,       // wifi/cell/bluetooth (~100m) — normal browsing
    LowPower,       // cell/wifi only (~10km) — battery-conscious
    Passive,        // no active request; piggyback on other apps' requests
    Off,            // no continuous updates; StateFlow holds last known value
}

/**
 * Device location, as consumed by [com.example.learning.BusInfo]. Exposed as the framework-free
 * domain type [LatLon] (not android.location.Location) so the domain stays unit-testable without
 * Robolectric. Plain interface so tests can drive a state-based fake (see `src/test`).
 */
interface LocationSource {
    val currentLocation: StateFlow<LatLon?>
    val mode: StateFlow<LocationMode>
    val hasPermission: StateFlow<Boolean>
    fun setMode(mode: LocationMode): Unit
    fun onPermissionGranted(): Unit
    fun onPermissionRevoked(): Unit
    suspend fun requestFreshFix()
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepository(
    context: Context,
    scope: CoroutineScope,
) : LocationSource {
    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val _currentLocation = MutableStateFlow<LatLon?>(null)
    private val _hasPermission = MutableStateFlow(false)
    private val _mode = MutableStateFlow(LocationMode.Off)

    override val mode: StateFlow<LocationMode> = _mode.asStateFlow()
    override val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()
    override val currentLocation: StateFlow<LatLon?> = _currentLocation.asStateFlow()
    override fun onPermissionGranted() { _hasPermission.value = true }
    override fun onPermissionRevoked() { _hasPermission.value = false }

    override fun setMode(mode: LocationMode) {
        _mode.value = mode
    }

    private fun Location.toLatLon() = LatLon(latitude, longitude)

    init {
        combine(_hasPermission, _mode, ::Pair)
            .flatMapLatest { (hasPerm, mode) ->
                if (!hasPerm) {
                    emptyFlow()
                } else {
                    locationFlowFor(mode)
                }
            }
            .onEach { location ->
                _currentLocation.value = location.toLatLon()
            }
            .launchIn(scope)
    }

    @SuppressLint("MissingPermission")
    private fun locationFlowFor(mode: LocationMode): Flow<Location> =
        when (mode) {
            LocationMode.Off -> emptyFlow()

            LocationMode.Passive ->
                updates(
                    priority = Priority.PRIORITY_PASSIVE,
                    intervalMs = Long.MAX_VALUE,
                    minIntervalMs = 5_000L,
                )

            LocationMode.LowPower ->
                updates(
                    priority = Priority.PRIORITY_LOW_POWER,
                    intervalMs = 30_000L,
                    minIntervalMs = 15_000L,
                )

            LocationMode.Balanced ->
                updates(
                    priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    intervalMs = 10_000L,
                    minIntervalMs = 5_000L,
                )

            LocationMode.HighAccuracy ->
                updates(
                    priority = Priority.PRIORITY_HIGH_ACCURACY,
                    intervalMs = 5_000L,
                    minIntervalMs = 2_000L,
                )
        }

    @SuppressLint("MissingPermission")
    private fun updates(
        priority: Int,
        intervalMs: Long,
        minIntervalMs: Long,
    ): Flow<Location> =
        callbackFlow {
            if (!_hasPermission.value) {
                close(SecurityException("No location permission"))
                return@callbackFlow
            }

            Log.d("Location", "Checked permissions.")

            val request = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .setMaxUpdateAgeMillis(60_000L)
                .setWaitForAccurateLocation(false)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        Log.d("Location", "Sending location from continuous updates.")
                        trySend(location)
                    }
                }
            }

            Log.d("Location", "Requesting continuous location updates.")
            fusedLocationClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper(),
            )

            // Race in a cached fix — usually faster than waiting for first callback.
            launch {
                runCatching {
                    fusedLocationClient.lastLocation.await()
                }.getOrNull()?.let { cachedLocation ->
                    Log.d("Location", "Sending cached last location.")
                    trySend(cachedLocation)
                }
            }

            awaitClose {
                Log.d("Location", "Removing continuous location updates.")
                fusedLocationClient.removeLocationUpdates(callback)
            }
        }

    /**
     * One-shot fresh fix for pull-to-refresh or explicit refresh actions.
     *
     * Unlike the old version, this also publishes the result into currentLocation.
     */
    @SuppressLint("MissingPermission")
    override suspend fun requestFreshFix() {
        if (!_hasPermission.value) return

        val cts = CancellationTokenSource()

        runCatching {
            fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .await()
        }.getOrNull()
            ?.also { freshLocation ->
                Log.d("Location", "Updating currentLocation from fresh fix.")
                _currentLocation.value = freshLocation.toLatLon()
            }
    }
}

class FakeLocationSource(
    location: LatLon? = LatLon(-33.8688, 151.2093),
) : LocationSource {
    private val _currentLocation = MutableStateFlow(location)
    private val _mode = MutableStateFlow(LocationMode.Off)
    private val _hasPermission = MutableStateFlow(false)

    fun changeLocation(latLon: LatLon) = _currentLocation.update { latLon }

    override val currentLocation = _currentLocation.asStateFlow()
    override val mode = _mode.asStateFlow()
    override val hasPermission = _hasPermission.asStateFlow()

    override fun setMode(mode: LocationMode) { _mode.value = mode }
    override fun onPermissionGranted() { _hasPermission.value = true }
    override fun onPermissionRevoked() = Unit
    override suspend fun requestFreshFix(): Unit = Unit
}
