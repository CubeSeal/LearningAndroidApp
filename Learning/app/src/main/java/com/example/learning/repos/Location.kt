package com.example.learning.repos

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await

class LocationRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val currentLocation: StateFlow<Location?> = updateLocation()

    @SuppressLint("MissingPermission")
    private fun updateLocation(): StateFlow<Location?> {
        return callbackFlow {
            if (!hasLocationPermission()) {
                close(SecurityException("No location permission"))
                return@callbackFlow
            }

            @SuppressLint("MissingPermission")
            fusedLocationClient.lastLocation.await()?.let { trySend(it) }

            val cancellationToken = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationToken.token
            ).await()?.let { trySend(it) }

            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10000L)
                .setMinUpdateIntervalMillis(5000L)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { trySend(it) }
                }
            }

            @SuppressLint("MissingPermission")
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()

            awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? = runCatching {
        fusedLocationClient.lastLocation.await()
    }.getOrNull()
}
