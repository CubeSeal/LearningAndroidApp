package com.example.learning

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.GlobbedBusStopRecord
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.LocationRepository
import com.example.learning.repos.RealtimeBusTripInfo
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
import kotlinx.coroutines.flow.forEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// Required since the info record isn't at stopTimes level, only at trip level, but has stop level information (and so
// can't be just broadcast across.
@Immutable
data class RealtimeBusStopTimesRecord(
    val tripId: String,
    val stopId: String,
    val updatedAt: LocalDateTime,
    val stopTimeDelay: Duration?,
    val vehicleLicencePlate: String
)

private fun RealtimeBusTripInfo.toRealtimeBusStopTimesRecord(): List<RealtimeBusStopTimesRecord> {
    return this.stopTimeDelays.map { stopTimeDelayPair ->
        RealtimeBusStopTimesRecord(
            tripId = this.tripId,
            stopId = stopTimeDelayPair.first,
            updatedAt = this.updatedAt,
            stopTimeDelay = stopTimeDelayPair.second?.let { Duration.ofSeconds(it.toLong())},
            vehicleLicencePlate = this.vehicleLicencePlate
        )
    }
}

sealed interface BusFilterOptions {
    data class RouteShortName(val routeShortName: String): BusFilterOptions
    data class TripHeadsign(val tripHeadsign: String): BusFilterOptions
    data class StopStand(val stopStand: String): BusFilterOptions
}

@Immutable
data class BusStopTimesRecordWithRealtime(
    // I think it's okay to have nesting like this if the underlying data structures are still flat.
    // Just becomes a convenient grouping and constructor thing.
    val busStopTimesRecord: BusStopTimesRecord,
    // Normally I'd make this flat instead of nesting it like this, but making this nullable this is a convenient way
    // to mark something as not having realtime info.
    val realtimeBusStopTimesRecord: RealtimeBusStopTimesRecord?,
    // Set of filters that apply to this busStop
    val applicableFilters: Set<BusFilterOptions>
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

    val focusedBusStop = settingsRepo
        .homeStopId
        .filterNotNull()
        .transformLatest {
            val value = gtfsStaticRepository.getGlobbedStopById(it)
            Log.d("BusInfo", "Settings focused bus stop to $value")
            emit(value)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<GlobbedBusStopRecord>> = locationRepo.currentLocation.map { loc ->
        Log.d("BusInfo", "Updating closest info with $loc.")
        loc?.let { gtfsStaticRepository.getNClosestStops(it, 10) } ?: emptyList()
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val realtimeBusStopTimesRecord: StateFlow<Map<Pair<String, String>, RealtimeBusStopTimesRecord>> =
        locationRepo.currentLocation.map { _ ->
            gtfsRealtimeRepository.getBusData()
                .flatMap { it.toRealtimeBusStopTimesRecord() }
                .associateBy { it.tripId to it.stopId }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    val associatedTrips = focusedBusStop
        .filterNotNull()
        .mapLatest {
            val associatedTrips = gtfsStaticRepository.getStopTimesByStop(it)
            Log.d("BusInfo", "Associated Trips = $associatedTrips")
            associatedTrips
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val filtersForBusStop = associatedTrips
        .filter { it.isNotEmpty() }
        .mapLatest { associatedTrips ->
            val filterList = mutableSetOf<BusFilterOptions>()
            associatedTrips.map { stopTimesRecord ->
                filterList.add(BusFilterOptions.RouteShortName(stopTimesRecord.routeShortName))
                filterList.add(BusFilterOptions.TripHeadsign(stopTimesRecord.tripHeadsign))
                focusedBusStop
                    .value
                    ?.busStopRecords
                    ?.takeIf { it.size > 2}
                    ?.filter { it.stopId == stopTimesRecord.stopId }
                    ?.forEach { filterList.add(BusFilterOptions.StopStand(it.stopName)) }
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
        realtimeBusStopTimesRecord,
        filtersForBusStop.filter { it.isNotEmpty() }
    ) { associatedTrips, realTimeInfo, filters ->
        Log.d("BusInfo", "Got over here.")
        Triple(associatedTrips, realTimeInfo, filters)
    }
        .distinctUntilChanged()
        .transformLatest { (trips, realtimeBusStopTimesRecords, filters) ->
             val busStopTimesRecordWithRealtime = trips.map { staticRecord ->
                BusStopTimesRecordWithRealtime(
                    busStopTimesRecord = staticRecord,
                    realtimeBusStopTimesRecord = realtimeBusStopTimesRecords[staticRecord.tripId to staticRecord.stopId],
                    applicableFilters = setOf(
                        //TODO: Guard the sealed interface constructors.
                        BusFilterOptions.RouteShortName(staticRecord.routeShortName),
                        BusFilterOptions.TripHeadsign(staticRecord.tripHeadsign),
                        BusFilterOptions.StopStand(staticRecord.stopName),
                    )
                )
            }
            emit(busStopTimesRecordWithRealtime)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyList()
        )

    val savedStops: StateFlow<List<GlobbedBusStopRecord>> = settingsRepo.savedStops
        .map { ids ->
            ids.mapNotNull { id -> gtfsStaticRepository.getGlobbedStopById(id) }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    suspend fun updateFocusedBusStop(globbedBusStopRecord: GlobbedBusStopRecord) {
        settingsRepo.setHomeStopId(globbedBusStopRecord.globbedStopId)
        Log.d("BusInfo", "Setting home bus stop to ${globbedBusStopRecord.globbedStopId}")
    }

    suspend fun updateFocusedBusStopByStopId(stopId: String) {
        getGlobbedStopById(stopId)?.let { updateFocusedBusStop(it) }
    }

    suspend fun refresh() {
        gtfsStaticRepository.syncGtfsDatabase(
            ghOwner = "CubeSeal",
            ghRepo = "LearningAndroidApp"
        )
        locationRepo.requestFreshFix()
    }

    suspend fun searchStops(globbedStopName: String): List<GlobbedBusStopRecord> {
        return gtfsStaticRepository.getGlobbedStopsByName(globbedStopName)
    }

    val getGlobbedStopById = gtfsStaticRepository::getGlobbedStopById

    suspend fun getByTrip(tripId: String, date: LocalDate): List<BusStopTimesRecord> {
        return gtfsStaticRepository.getStopTimesByTripId(tripId, date)
    }

    suspend fun addSavedStop(globbedBusStopRecord: GlobbedBusStopRecord) {
        settingsRepo.addSavedStop(globbedBusStopRecord.globbedStopId)
    }

    suspend fun removeSavedStop(globbedBusStopRecord: GlobbedBusStopRecord) {
        settingsRepo.removeSavedStop(globbedBusStopRecord.globbedStopId)
    }
}