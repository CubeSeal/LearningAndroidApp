package com.example.learning

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.repos.BusStopInfo
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.LocationRepository
import com.example.learning.repos.RealtimeBusInfo
import com.example.learning.repos.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Immutable
data class RealtimeBusStopTimesRecord(
    val busStopTimesRecord: BusStopTimesRecord,
    val realtimeBusInfo: RealtimeBusInfo?
)

class BusInfo(
    val gtfsStaticRepository: GtfsStaticRepository,
    private val gtfsRealtimeRepository: GtfsRealtimeRepository,
    private val locationRepo: LocationRepository,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val currentMinute: StateFlow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now.truncatedTo(ChronoUnit.MINUTES))
            val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(ChronoUnit.MILLIS.between(now, nextMinute))
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))
    private val _focusedBusStop = MutableStateFlow<BusStopInfo?>(null)
    val focusedBusStop = _focusedBusStop.asStateFlow()

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<BusStopInfo>> = locationRepo.currentLocation.map { loc ->
            loc?.let { gtfsStaticRepository.getNClosestStops(it, 10) } ?: emptyList()
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val associatedStopTimes: StateFlow<List<RealtimeBusStopTimesRecord>> = combine(
            focusedBusStop.filterNotNull(),
            locationRepo.currentLocation.filterNotNull().take(1)
        ) { busStop, loc ->
            Pair(busStop, loc)
        }
            .distinctUntilChanged()
            .transformLatest { (busStop, loc) ->
                emit(emptyList())
                val trips = gtfsStaticRepository.getAssociatedTrips(busStop)
                val closestBuses = gtfsRealtimeRepository.getBusData(loc)
                val returnVal = trips.map { busStopTimesRecord ->
                    RealtimeBusStopTimesRecord(
                        busStopTimesRecord,
                        closestBuses.firstOrNull {
                            it.tripId == busStopTimesRecord.tripInfo.tripId
                        }
                    )
                }
                emit(returnVal)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    init {
        scope.launch {
            Log.d("BusInfo", "Populate stop from settings.")
            settingsRepo.homeStopId.collect {
                it?.let { stopId ->
                    Log.d("BusInfo", "Will try to populate stop id = $stopId")
                    val busStop = gtfsStaticRepository.getStopByStopId(stopId)
                    Log.d("BusInfo", "Got busStop $busStop.")
                    _focusedBusStop.update { busStop }
                }
            }
            Log.d("BusInfo", "Populated stop from settings.")
        }
    }


    suspend fun updateFocusedBusStop(busStopInfo: BusStopInfo) {
        _focusedBusStop.value = busStopInfo
        Log.d("BusInfo", "Will save bus stop to ${busStopInfo.stopId}")
        settingsRepo.setHomeStopId(busStopInfo.stopId)
        Log.d("BusInfo", "Setting saved bus stop to ${busStopInfo.stopId}")
    }
    suspend fun refreshLocation() { locationRepo.requestFreshFix() }
    suspend fun searchStops(stopName: String): List<BusStopInfo> { return gtfsStaticRepository.getStopsByName(stopName) }
    suspend fun getByTrip(busStopTimesRecord: BusStopTimesRecord): List<BusStopTimesRecord> {
        return gtfsStaticRepository.getByTrip(busStopTimesRecord)
    }
}