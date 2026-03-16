package com.example.learning

import android.Manifest
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.learning.database.BusStopInfo
import com.example.learning.database.GtfsStaticRepository
import com.example.learning.database.TripInfo
import com.example.learning.repos.FileRepository
import com.example.learning.repos.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class ApplicationRepos(private val applicationContext: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val locationRepo = LocationRepository(applicationContext, applicationScope)
    val fileRepository = FileRepository(applicationContext, "busStops")
    val httpClient = OkHttpClient()
    val gtfsStaticRepository = GtfsStaticRepository(
        fileRepository = fileRepository,
        httpClient = httpClient
    )
    val busInfo by lazy {
        BusInfo(
            gtfsStaticRepository = gtfsStaticRepository,
            location = locationRepo.currentLocation,
            scope = applicationScope
        )
    }
    val isLoaded = MutableStateFlow(false)

    suspend fun initAll() {
        if (isLoaded.value) return

        withContext(Dispatchers.Default) {
            Log.d("INIT", "Start loading...")
            val job1 = async { gtfsStaticRepository.updateBusStopData() }
//            val job2 = async { busResource.init(applicationContext) }

            job1.await()
//            job2.await()
            isLoaded.value = true
            Log.d("INIT", "Finished loading.")
        }
    }
}

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

        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            HomeViewModel(app.repos.busInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            TripsViewModel(app.repos.busInfo)
        }
    }
}

class HomeViewModel(
    private val busInfo: BusInfo
) : ViewModel() {

    val focusedBusStop = busInfo.focusedBusStop
    val closestBusStops = busInfo.closestBusStops
    val associatedStopTimes = busInfo.associatedStopTimes
    fun updateFocusedBusStop(stop: BusStopInfo) = busInfo.updateFocusedBusStop(stop)

    init {
        viewModelScope.launch {
            busInfo.closestBusStops.first { it.isNotEmpty() }.firstOrNull()?.let {
                busInfo.updateFocusedBusStop(it)
            }
        }
    }
}

class TripsViewModel(
    private val busInfo: BusInfo
) : ViewModel() {

    private val _tripInfo = MutableStateFlow<TripInfo?>(null)
    val tripInfo = _tripInfo.asStateFlow()

    fun updateTripInfo(tripId: String) {
        viewModelScope.launch {
            _tripInfo.update {
                busInfo.getTripInfo(tripId)
            }
        }
    }
}
