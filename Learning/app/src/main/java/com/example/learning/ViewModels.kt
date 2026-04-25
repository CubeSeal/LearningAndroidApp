package com.example.learning

import android.Manifest
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.learning.repos.BusStopInfo
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.FileRepository
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class ApplicationRepos(private val applicationContext: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val locationRepo = LocationRepository(applicationContext, applicationScope)
    val fileRepository = FileRepository(applicationContext, "busStops")
    val httpClient = OkHttpClient()
    val loaded = MutableStateFlow(false)

    lateinit var gtfsStaticRepository: GtfsStaticRepository
        private set

    val gtfsRealtimeRepository = GtfsRealtimeRepository(httpClient = httpClient)
    val busInfo by lazy {
        BusInfo(
            gtfsStaticRepository = gtfsStaticRepository,
            gtfsRealtimeRepository = gtfsRealtimeRepository,
            locationRepo = locationRepo,
            scope = applicationScope
        )
    }
    suspend fun initAll() {
        withContext(Dispatchers.Default) {
            Log.d("INIT", "Start loading...")

            locationRepo.onPermissionGranted()

            gtfsStaticRepository = GtfsStaticRepository(
                applicationContext,
                fileRepository,
                httpClient
            )
            gtfsStaticRepository.syncGtfsDatabase(
                ghOwner = "CubeSeal",
                ghRepo = "LearningAndroidApp"
            )

            Log.d("INIT", "Finished loading.")
        }

        loaded.update { true }
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
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)

            PickStopViewModel(app.repos.busInfo)
        }
    }
}

class HomeViewModel(
    private val busInfo: BusInfo
) : ViewModel() {

    val focusedBusStop = busInfo.focusedBusStop
    val associatedStopTimes = combine(busInfo.associatedStopTimes, busInfo.currentMinute) { stopTimes, currentMinute ->
        val pastBuffer = Duration.ofMinutes(2)
        val realTimeSorted = stopTimes.sortedWith(
            compareBy(
                { it.busStopTimesRecord.stopTimesInfo.departureTime },
                { it.realtimeBusInfo?.distance ?: Double.MAX_VALUE }
            )
        )
        realTimeSorted
            .filter { it.busStopTimesRecord.stopTimesInfo.departureTime > currentMinute - pastBuffer }
            .map {
                (it.busStopTimesRecord.stopTimesInfo.departureTime >= currentMinute) to it
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList())

    val isUpToDate = busInfo.gtfsStaticRepository.isUpToDate
    private val _isRefreshing = MutableStateFlow(true)

    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        refreshLocation()
        focusOnClosestStop()
    }

    fun focusOnClosestStop() {
        viewModelScope.launch {
            busInfo.closestBusStops.first { it.isNotEmpty() }.firstOrNull()?.let {
                busInfo.updateFocusedBusStop(it)
            }
        }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            _isRefreshing.update { true }
            try {
                busInfo.refreshLocation() // the real suspend call
            } finally {
                _isRefreshing.update { false }
            }
        }
    }
}

class PickStopViewModel(
    private val busInfo: BusInfo
) : ViewModel() {

    val busStops = busInfo.allBusStops
    val closestBusStops = busInfo.closestBusStops

    // ViewModel
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val filteredBusStops: StateFlow<List<BusStopInfo>> = _query
        .map { query ->
            if (query.isEmpty()) busStops
            else busStops.filter { it.stopName.contains(query, ignoreCase = true) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), busStops)

    fun updateFocusedBusStop(stop: BusStopInfo) = busInfo.updateFocusedBusStop(stop)
    fun onQueryChange(q: String) {
        _query.value = q
    }
}

class TripsViewModel(
    private val busInfo: BusInfo
) : ViewModel() {

    private val _busStopTimesRecord = MutableStateFlow<List<BusStopTimesRecord>>(emptyList())
    val busStopTimesRecord = _busStopTimesRecord.asStateFlow()

    fun updateBusStopTimesRecord(busStopTimesRecord: BusStopTimesRecord) {
        viewModelScope.launch {
            _busStopTimesRecord.update {
                busInfo.getByTrip(busStopTimesRecord)
            }
        }
    }
}

class SharedViewModel : ViewModel() {
    var selectedRecord: BusStopTimesRecord? by mutableStateOf(null)
        private set

    fun select(record: BusStopTimesRecord) {
        selectedRecord = record
    }
}