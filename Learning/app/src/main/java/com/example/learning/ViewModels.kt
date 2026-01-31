package com.example.learning

import GtfsStaticRepository
import android.Manifest
import android.app.Application
import android.location.Location
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.learning.repos.ApplicationRepos
import com.example.learning.repos.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LearningApplication : Application() {
    // This holds the data layer
    lateinit var repos: ApplicationRepos
    // This is effectively "applicationScope"
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()
        // Initialize it once when the app starts
        repos = ApplicationRepos(this)
    }
}

object AppViewModelProvider {
    val Factory = viewModelFactory {

        // 2. Recipe for SecondViewModel
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            HomeViewModel(app.repos.gtfsStaticRepository, app.repos.locationRepo)
        }
    }
}

class HomeViewModel(
    private val gtfsStaticRepository: GtfsStaticRepository,
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _allBusStops = MutableStateFlow<List<BusStopInfo>>(emptyList())
    val allBusStops = _allBusStops.asStateFlow()

    private val _closestBusStop = MutableStateFlow<BusStopInfo?>(null)
    val closestBusStop = _closestBusStop.asStateFlow()

    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private val _associatedStopTimes = MutableStateFlow<List<ScheduledStopTimesInfo>>(emptyList())
    val associatedStopTimes = _associatedStopTimes.asStateFlow()

    fun updateClosestBusStop(busStopInfo: BusStopInfo) {
        _closestBusStop.update { busStopInfo }

        viewModelScope.launch {
            val newStopTimes = gtfsStaticRepository.getAssociatedTrips(busStopInfo.id)
            _associatedStopTimes.update { newStopTimes }
        }
    }

    init {
        viewModelScope.launch {
            // Init location
            _location.value = locationRepository.getCurrentLocation()
            if (_location.value == null) {
                println("Location not available yet")
                return@launch
            }

            // Init Closest Bus Stops
            _allBusStops.value = gtfsStaticRepository.getStops()
            _closestBusStop.value = allBusStops.value.minByOrNull {
                it.getDistance(location.value!!)
            }

            // Init Associated Stop Times
            _associatedStopTimes.value = _closestBusStop.value?.id?.let {
                Log.d("VM", "Trying to get associated trips")
                val associatedTrips = gtfsStaticRepository.getAssociatedTrips(it)
                Log.d("VM", associatedTrips.toString())
                associatedTrips
            } ?: emptyList()
        }
    }
}

