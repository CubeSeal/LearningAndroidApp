package com.example.learning

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.repos.BusStopInfo
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.LocationRepository
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
import kotlinx.coroutines.flow.flatMap
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Immutable
data class RealtimeBusStopTimesInfo(
    val id: String,
    val tripId: String,
    val updatedAt: LocalDateTime,
    val stopTimeDelay: Pair<String, Int?>,
    val vehicleLicencePlate: String,
)

@Immutable
data class BusStopTimesRecordScheduleAndRealtime(
    val busStopTimesRecord: BusStopTimesRecord,
    val realtimeBusStopTimesInfo: RealtimeBusStopTimesInfo?
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

    private val realtimeBusStopTimesInfo: StateFlow<List<RealtimeBusStopTimesInfo>> =
        locationRepo.currentLocation.map { _ ->
            gtfsRealtimeRepository.getBusData()
                .flatMap { realtimeBusInfo ->
                    realtimeBusInfo.stopTimeDelays.map {
                        RealtimeBusStopTimesInfo(
                            id = realtimeBusInfo.id,
                            tripId = realtimeBusInfo.tripId,
                            updatedAt = realtimeBusInfo.updatedAt,
                            stopTimeDelay = it,
                            vehicleLicencePlate = realtimeBusInfo.vehicleLicencePlate
                        )
                    }
                }
                .sortedBy { it.tripId }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val associatedStopTimes: StateFlow<List<BusStopTimesRecordScheduleAndRealtime>> =
        combine( focusedBusStop.filterNotNull(), realtimeBusStopTimesInfo ) {
            focusedBusStop, realTimeInfo -> focusedBusStop to realTimeInfo
        }
        .distinctUntilChanged()
        .transformLatest { (busStop, realtimeBusStopTimesInfo) ->
            // Emit static trips immediately — no location needed
            val trips = gtfsStaticRepository.getAssociatedTrips(busStop)
            emit(trips.map { BusStopTimesRecordScheduleAndRealtime(it, realtimeBusStopTimesInfo = null) })
            Log.d("BusInfo", "First emit of records")

            // Then enrich with realtime once we have a location
            emit(trips.map { record ->
                val matchingRealTimeRecord = realtimeBusStopTimesInfo.let {
                    val tripId = record.tripInfo.tripId.toInt()

                    val index = it.binarySearchBy(tripId) { realtimeRecord ->
                        realtimeRecord.tripId.toInt()
                    }

                    if (index >= 0) it[index] else null
                }
                BusStopTimesRecordScheduleAndRealtime(
                    record,
                    matchingRealTimeRecord
                )
            })
            Log.d("BusInfo", "Second emit of records")
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val savedStops: StateFlow<List<BusStopInfo>> = settingsRepo.savedStops
        .map { ids ->
            ids.mapNotNull { id -> gtfsStaticRepository.getStopByStopId(id) }
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

    suspend fun refresh() {
        locationRepo.requestFreshFix()
    }

    suspend fun searchStops(stopName: String): List<BusStopInfo> {
        return gtfsStaticRepository.getStopsByName(stopName)
    }

    suspend fun getByTrip(tripId: String, date: LocalDate): List<BusStopTimesRecord> {
        return gtfsStaticRepository.getByTrip(tripId, date)
    }

    suspend fun addSavedStop(busStopInfo: BusStopInfo) {
        settingsRepo.addSavedStop(busStopInfo.stopId)
    }

    suspend fun removeSavedStop(busStopInfo: BusStopInfo) {
        settingsRepo.removeSavedStop(busStopInfo.stopId)
    }
}