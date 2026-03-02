package com.example.learning

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.example.learning.database.AppDatabase
import com.example.learning.database.BusStopInfoEntity
import com.example.learning.database.GtfsStaticRepository
import com.example.learning.database.ScheduledStopTimesInfo
import com.example.learning.repos.FileRepository
import com.example.learning.repos.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import kotlin.math.pow

class ApplicationRepos(private val applicationContext: Context) {
    val database = Room.databaseBuilder(
        applicationContext,
        AppDatabase::class.java, "bus-stuff"
    ).build()
    val locationRepo = LocationRepository(applicationContext)
    val fileRepository = FileRepository(applicationContext, "busStops")
    val httpClient = OkHttpClient()
    val gtfsStaticRepository = GtfsStaticRepository(
        database = database,
        fileRepository = fileRepository,
        httpClient = httpClient
    )
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
    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private var locationJob: Job? = null

    private val _allBusStops = MutableStateFlow<List<BusStopInfoEntity>>(emptyList())
    val allBusStops = _allBusStops.asStateFlow()

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<BusStopInfoEntity>> = combine(
        _location,
        _allBusStops
    ) { location, stops ->
        location?.let { loc ->
            stops.sortedBy { (loc.latitude - it.latitude.toDouble()).pow(2) + (loc.longitude - it.longitude.toDouble()).pow(2) }.take(10)
        } ?: emptyList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _focusedBusStop = MutableStateFlow<BusStopInfoEntity?>(null)
    val focusedBusStop = _focusedBusStop.asStateFlow()

    // Derived state - automatically updates when focusedBusStop changes
    @OptIn(ExperimentalCoroutinesApi::class)
    val associatedStopTimes: StateFlow<List<ScheduledStopTimesInfo>> = _focusedBusStop
        .filterNotNull()
        .distinctUntilChanged()
        .mapLatest { busStop ->
            val time = LocalDateTime.now()
            val (trips, index) = gtfsStaticRepository.getAssociatedTrips(busStop.id, time)
            return@mapLatest trips.drop(index) + trips.take(index)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateFocusedBusStop(busStopInfo: BusStopInfoEntity) {
        _focusedBusStop.value = busStopInfo
        // No need to manually update associatedStopTimes - it happens automatically!
    }

    fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationRepository
                .startLocationUpdates()
                .collect { _location.value = it }
            // closestBusStops automatically updates via combine()
        }
    }

    fun endLocationUpdates() {
        locationJob?.cancel()
    }

    init {
        viewModelScope.launch {
            // Init all bus stops
            Log.d("INIT", "Trying to get stops.")
            _allBusStops.value = gtfsStaticRepository.getStops()
            Log.d("INIT", "Got stops.")

            // Init location
            Log.d("INIT", "Trying to get location")
            _location.value = locationRepository.getCurrentLocation()
            if (_location.value == null) {
                Log.d("INIT", "Location not available yet")
                return@launch
            }
            Log.d("INIT", "Got location.")

            Log.d("INIT", "Trying to get *closest* stops.")
            // closestBusStops is automatically calculated via combine()
            // Wait for first emission to set focused stop
            closestBusStops.first { it.isNotEmpty() }.firstOrNull()?.let {
                updateFocusedBusStop(it)
            }
            Log.d("INIT", "Got closests stops.")
        }
    }
}
