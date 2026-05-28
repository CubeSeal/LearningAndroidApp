package com.example.learning

import android.Manifest
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.example.learning.repos.BusStopRecord
import com.example.learning.repos.FileRepository
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.LocationRepository
import com.example.learning.repos.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.time.LocalDate

class ApplicationRepos(private val applicationContext: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val locationRepo = LocationRepository(applicationContext, applicationScope)
    val fileRepository = FileRepository(applicationContext, "busStops")
    val settingsRepo = SettingsRepository(applicationContext)
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
            settingsRepo = settingsRepo,
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

            TripsViewModel(createSavedStateHandle(), app.repos.busInfo)
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

    val availableFiltersForBusStop = busInfo.filtersForBusStop
    // If empty then we don't filter anything, but if not empty we only include what in the set.
    private val _selectedFiltersForBusStop = MutableStateFlow(setOf<BusFilterOptions>())
    val selectedFiltersForBusStop = _selectedFiltersForBusStop.asStateFlow()

    val associatedStopTimes = combine(
        busInfo.associatedStopTimes,
        busInfo.currentMinute,
        selectedFiltersForBusStop
    ) { stopTimes, currentMinute, selectedFilters ->
        val pastBuffer = Duration.ofMinutes(2)
        val realTimeSorted = stopTimes.sortedBy {
            // Have to carry null delay's all the way through, since that's a valid state when the data just isn't there.
            // Only make the choice to treat it as zero when re-calculating departureTimes.
            it.busStopTimesRecord.departureTime + (it.realtimeBusStopTimesRecord?.stopTimeDelay ?: Duration.ZERO)
        }

        realTimeSorted
            //TODO: Rarefy the types here so that I'm only pulling in what I need instead of mutating things
            // all over the place.
            .filter {
                val newTime = it.busStopTimesRecord.departureTime +
                    (it.realtimeBusStopTimesRecord?.stopTimeDelay ?: Duration.ZERO)
                newTime > currentMinute - pastBuffer && (selectedFilters.isEmpty()
                        || it.applicableFilters.any { filter -> filter in selectedFilters })
            }
            .map {
                val newTime = it.busStopTimesRecord.departureTime +
                    (it.realtimeBusStopTimesRecord?.stopTimeDelay ?: Duration.ZERO)
                (newTime >= currentMinute) to it
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList())


    val isUpToDate = busInfo.gtfsStaticRepository.isUpToDate
    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        refresh()
        focusOnClosestStop()
    }

    private fun focusOnClosestStop() = viewModelScope.launch {
        busInfo.closestBusStops.first { it.isNotEmpty() }.firstOrNull()?.let {
            Log.d("VM", "Updating focused bus stop after refresh.")
            busInfo.updateFocusedBusStop(it)
        }
    }

    fun refresh() = viewModelScope.launch {
        _isRefreshing.update { true }
        try {
            busInfo.refresh()
        } finally {
            _isRefreshing.update { false }
        }
    }

    fun addSavedStop(busStopRecord: BusStopRecord) = viewModelScope.launch {
        busInfo.addSavedStop(busStopRecord)
    }

    fun toggleFilterForBusStops(busStopFilterOptions: BusFilterOptions) {
        if (busStopFilterOptions in selectedFiltersForBusStop.value) {
            _selectedFiltersForBusStop.update { it - busStopFilterOptions}
        } else {
            _selectedFiltersForBusStop.update { it + busStopFilterOptions}
        }
    }
}

class PickStopViewModel(
    private val busInfo: BusInfo
) : ViewModel() {
    val closestBusStops = busInfo.closestBusStops
    val savedStops = busInfo.savedStops
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    val filteredBusStops: StateFlow<List<BusStopRecord>> = _query
        .map { query -> busInfo.searchStops(query) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            closestBusStops.value
        )

    fun updateFocusedBusStop(stop: BusStopRecord) = viewModelScope.launch { busInfo.updateFocusedBusStop(stop) }
    fun onQueryChange(q: String) { _query.value = q }
    fun addSavedStop(busStopRecord: BusStopRecord) = viewModelScope.launch {
        busInfo.addSavedStop(busStopRecord)
    }
    fun removeSavedStop(busStopRecord: BusStopRecord) = viewModelScope.launch {
        busInfo.removeSavedStop(busStopRecord)
    }
}

class TripsViewModel(
    savedStateHandle: SavedStateHandle,
    private val busInfo: BusInfo
) : ViewModel() {

    private val args: Trips = savedStateHandle.toRoute()
    val tripId = args.tripId
    val stopId = args.stopId
    val date = LocalDate.parse(args.date)
    val busStopTimesRecord = flow {
        val result = busInfo.getByTrip(tripId, date)
        emit(result)
        Log.d("VM", result.toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

}