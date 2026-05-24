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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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

sealed interface BusFilterOptions {
    data class RouteShortName(val routeShortName: String): BusFilterOptions
    data class TripHeadsign(val tripHeadsign: String): BusFilterOptions
}


@Immutable
data class BusStopTimesRecordScheduleAndRealtime(
    val busStopTimesRecord: BusStopTimesRecord,
    val appliedFilters: Set<BusFilterOptions>,
    val realtimeBusStopTimesInfo: RealtimeBusStopTimesInfo?
)

@OptIn(ExperimentalCoroutinesApi::class)
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
    }.stateIn(scope, SharingStarted.Eagerly, LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))
    private val _focusedBusStop = MutableStateFlow<BusStopInfo?>(null)
    val focusedBusStop = _focusedBusStop.asStateFlow()

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<BusStopInfo>> = locationRepo.currentLocation.map { loc ->
        Log.d("BusInfo", "Updating closest info with $loc.")
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

    val associatedTrips = focusedBusStop
        .filterNotNull()
        .mapLatest {
            val associatedTrips = gtfsStaticRepository.getAssociatedTrips(it)
            Log.d("BusInfo", "Associated Trips = $associatedTrips")
            associatedTrips
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val filtersForBusStop = associatedTrips
        .filter { it.isNotEmpty() }
        .mapLatest {
            val filterList = mutableSetOf<BusFilterOptions>()
            it.map { stopTimesRecord ->
                filterList.add(BusFilterOptions.RouteShortName(stopTimesRecord.routeInfo.routeShortName))
                filterList.add(BusFilterOptions.TripHeadsign(stopTimesRecord.tripInfo.tripHeadsign))
            }
            Log.d("BusInfo", "Filter list = $filterList")
            filterList
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    val associatedStopTimes = combine(
        associatedTrips.filter { it.isNotEmpty() },
        realtimeBusStopTimesInfo.filter { it.isNotEmpty() },
        filtersForBusStop.filter { it.isNotEmpty() }
    ) { associatedTrips, realTimeInfo, filters ->
        Log.d("BusInfo", "Got over here.")
        Triple(associatedTrips, realTimeInfo, filters)
    }
        .distinctUntilChanged()
        .transformLatest { (trips, realtimeBusStopTimesInfo, filters) ->
            Log.d("BusInfo", "Loading associated stop times.")
            emit(
                trips.map {
                    BusStopTimesRecordScheduleAndRealtime(
                        it,
                        appliedFilters = setOf(
                            //TODO: Guard the sealed interface constructors.
                            BusFilterOptions.RouteShortName(it.routeInfo.routeShortName),
                            BusFilterOptions.TripHeadsign(it.tripInfo.tripHeadsign),
                        ),
                        realtimeBusStopTimesInfo = null
                    )
                }
            )
            Log.d("BusInfo", "First emit of records")

            // Then enrich with realtime once we have a location
            val emitValue = trips.map { record ->
                val matchingRealTimeRecord = realtimeBusStopTimesInfo.let {
                    val tripId = record.tripInfo.tripId.toInt()

                    val index = it.binarySearchBy(tripId) { realtimeRecord ->
                        realtimeRecord.tripId.toInt()
                    }

                    if (index >= 0) it[index] else null
                }
                BusStopTimesRecordScheduleAndRealtime(
                    busStopTimesRecord = record,
                    appliedFilters = setOf(
                        //TODO: Guard the sealed interface constructors.
                        BusFilterOptions.RouteShortName(record.routeInfo.routeShortName),
                        BusFilterOptions.TripHeadsign(record.tripInfo.tripHeadsign),
                    ),
                    realtimeBusStopTimesInfo = matchingRealTimeRecord
                )
            }
            Log.d("BusInfo", "Second emit of records $emitValue")
            emit(emitValue)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyList()
        )

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
        gtfsStaticRepository.syncGtfsDatabase(
            ghOwner = "CubeSeal",
            ghRepo = "LearningAndroidApp"
        )
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