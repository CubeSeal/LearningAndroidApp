package com.example.learning

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.repos.StopTimesRecord
import com.example.learning.repos.GTFS_GH_OWNER
import com.example.learning.repos.GTFS_GH_REPO
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LocationSource
import com.example.learning.repos.RealtimeTripInfo
import com.example.learning.repos.RealtimeGtfsSource
import com.example.learning.repos.SettingsSource
import com.example.learning.repos.StaticGtfsSource
import com.example.learning.repos.TransitMode
import com.example.learning.repos.transitModeOf
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
data class RealtimeStopTimesRecord(
    val tripId: String,
    val stopId: String,
    val updatedAt: LocalDateTime,
    val stopTimeDelay: Duration?,
    val vehicleLicencePlate: String
)

private fun RealtimeTripInfo.toRealtimeStopTimesRecord(): List<RealtimeStopTimesRecord> {
    return this.stopTimeDelays.map { stopTimeDelayPair ->
        RealtimeStopTimesRecord(
            tripId = this.tripId,
            stopId = stopTimeDelayPair.first,
            updatedAt = this.updatedAt,
            stopTimeDelay = stopTimeDelayPair.second?.let { Duration.ofSeconds(it.toLong())},
            vehicleLicencePlate = this.vehicleLicencePlate
        )
    }
}

sealed interface TransitFilterOptions {
    // Route/stand carry their [mode] so the UI can name them per transport type (train "lines" and
    // "platforms" vs bus "routes" and "stands"). The mode is part of the option's identity, so both
    // the filter-list builder and the per-trip applicable set below must stamp it consistently.
    data class RouteShortName(val routeShortName: String, val mode: TransitMode): TransitFilterOptions {
        init { require(routeShortName.isNotBlank()) }
    }
    data class TripHeadsign(val tripHeadsign: String): TransitFilterOptions {
        init { require(tripHeadsign.isNotBlank()) }
    }
    data class StopStand(val stopStand: String, val mode: TransitMode): TransitFilterOptions {
        init { require(stopStand.isNotBlank()) }
    }
    data class TransportMode(val mode: TransitMode): TransitFilterOptions
}

/**
 * The natural hierarchy of filter types, broad → specific: mode, then stand/platform, then route/
 * line, then destination. Used to order the Home filter row (and mirrored by the FilterPage's
 * section order) consistently, regardless of the order options were discovered in.
 */
val filterTypeRank: (TransitFilterOptions) -> Int = { option ->
    when (option) {
        is TransitFilterOptions.TransportMode -> 0
        is TransitFilterOptions.StopStand -> 1
        is TransitFilterOptions.RouteShortName -> 2
        is TransitFilterOptions.TripHeadsign -> 3
    }
}

/** Within a tier, order transport modes train → bus → other so trains lead. */
fun transitModeRank(mode: TransitMode): Int = when (mode) {
    TransitMode.TRAIN -> 0
    TransitMode.BUS -> 1
    TransitMode.OTHER -> 2
}

/**
 * Secondary ordering key (after [filterTypeRank]) that keeps trains ahead of buses within a tier —
 * the mode-selector chips (train roundel before bus) as well as the route/line and stand/platform
 * tiers. Destinations are mode-agnostic, so they share a value.
 */
val filterModeRank: (TransitFilterOptions) -> Int = { option ->
    when (option) {
        is TransitFilterOptions.RouteShortName -> transitModeRank(option.mode)
        is TransitFilterOptions.StopStand -> transitModeRank(option.mode)
        is TransitFilterOptions.TransportMode -> transitModeRank(option.mode)
        is TransitFilterOptions.TripHeadsign -> 0
    }
}

/** The user-facing text of a filter option, used as the final alphabetical sort key. */
val filterLabel: (TransitFilterOptions) -> String = { option ->
    when (option) {
        is TransitFilterOptions.RouteShortName -> option.routeShortName
        is TransitFilterOptions.StopStand -> option.stopStand
        is TransitFilterOptions.TransportMode -> option.mode.label
        is TransitFilterOptions.TripHeadsign -> option.tripHeadsign
    }
}

@Immutable
data class StopTimesRecordWithRealtime(
    // I think it's okay to have nesting like this if the underlying data structures are still flat.
    // Just becomes a convenient grouping and constructor thing.
    val stopTimesRecord: StopTimesRecord,
    // Normally I'd make this flat instead of nesting it like this, but making this nullable this is a convenient way
    // to mark something as not having realtime info.
    val realtimeStopTimesRecord: RealtimeStopTimesRecord?,
    // Set of filters that apply to this busStop
    val applicableFilters: Set<TransitFilterOptions>
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransitInfo(
    val gtfsStaticRepository: StaticGtfsSource,
    private val gtfsRealtimeRepository: RealtimeGtfsSource,
    private val locationRepo: LocationSource,
    private val settingsRepo: SettingsSource,
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
            Log.d("TransitInfo", "Settings focused bus stop to ${value?.globbedStopId} (${value?.stopRecords?.size} stops)")
            emit(value)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<GlobbedStopRecord>> = locationRepo.currentLocation.map { loc ->
        Log.d("TransitInfo", "Updating closest info with $loc.")
        loc?.let { gtfsStaticRepository.getNClosestStops(it, 10) } ?: emptyList()
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val realtimeStopTimesRecord: StateFlow<Map<Pair<String, String>, RealtimeStopTimesRecord>> =
        locationRepo.currentLocation.map { _ ->
            gtfsRealtimeRepository.getRealtimeData()
                .flatMap { it.toRealtimeStopTimesRecord() }
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
            // Log only the size: stringifying the whole list OOM-crashes at large stations (Central
            // expands to a list whose toString() is >100MB).
            Log.d("TransitInfo", "Associated Trips = ${associatedTrips.size} records")
            associatedTrips
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val filtersForBusStop = associatedTrips
        .filter { it.isNotEmpty() }
        .mapLatest { associatedTrips ->
            // Each trip contributes the set of filter options that apply to it. A filter only helps if
            // it distinguishes some trips from others — an option every trip carries (e.g. a stop served
            // by a single route, or all-bus services) filters nothing, so we drop it.
            val perTripFilters = associatedTrips.map { stopTimesRecord ->
                val mode = transitModeOf(stopTimesRecord.routeType)
                buildSet {
                    stopTimesRecord.routeShortName?.takeIf { it.isNotBlank() }?.let { add(TransitFilterOptions.RouteShortName(it, mode)) }
                    stopTimesRecord.tripHeadsign?.takeIf { it.isNotBlank() }?.let { add(TransitFilterOptions.TripHeadsign(it)) }
                    add(TransitFilterOptions.TransportMode(mode))
                    focusedBusStop
                        .value
                        ?.stopRecords
                        ?.takeIf { it.size > 2}
                        ?.filter { it.stopId == stopTimesRecord.stopId }
                        ?.forEach { if (it.stopName.isNotBlank()) add(TransitFilterOptions.StopStand(it.stopName, mode)) }
                }
            }
            val totalTrips = perTripFilters.size
            val filterList = perTripFilters
                .flatten()
                .groupingBy { it }
                .eachCount()
                .filterValues { it < totalTrips }
                .keys
            Log.d("TransitInfo", "Filter list = ${filterList.size} options")
            filterList
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    val associatedStopTimes = combine(
        associatedTrips.filter { it.isNotEmpty() },
        realtimeStopTimesRecord,
    ) { associatedTrips, realTimeInfo ->
        associatedTrips to realTimeInfo
    }
        .distinctUntilChanged()
        .transformLatest { (trips, realtimeStopTimesRecords) ->
             val stopTimesRecordWithRealtime = trips.map { staticRecord ->
                val mode = transitModeOf(staticRecord.routeType)
                StopTimesRecordWithRealtime(
                    stopTimesRecord = staticRecord,
                    realtimeStopTimesRecord = realtimeStopTimesRecords[staticRecord.tripId to staticRecord.stopId],
                    applicableFilters = setOfNotNull(
                        staticRecord.routeShortName?.takeIf { it.isNotBlank() }?.let { TransitFilterOptions.RouteShortName(it, mode) },
                        staticRecord.tripHeadsign?.takeIf { it.isNotBlank() }?.let { TransitFilterOptions.TripHeadsign(it) },
                        staticRecord.stopName.takeIf { it.isNotBlank() }?.let { TransitFilterOptions.StopStand(it, mode) },
                        TransitFilterOptions.TransportMode(mode),
                    )
                )
            }
            emit(stopTimesRecordWithRealtime)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyList()
        )

    val savedStops: StateFlow<List<GlobbedStopRecord>> = settingsRepo.savedStops
        .map { ids ->
            ids.mapNotNull { id -> gtfsStaticRepository.getGlobbedStopById(id) }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    suspend fun updateFocusedBusStop(globbedBusStopRecord: GlobbedStopRecord) {
        settingsRepo.setHomeStopId(globbedBusStopRecord.globbedStopId)
        Log.d("TransitInfo", "Setting home bus stop to ${globbedBusStopRecord.globbedStopId}")
    }

    suspend fun updateFocusedBusStopByStopId(stopId: String) {
        getGlobbedStopById(stopId)?.let { updateFocusedBusStop(it) }
    }

    suspend fun refresh() {
        gtfsStaticRepository.syncGtfsDatabase(
            ghOwner = GTFS_GH_OWNER,
            ghRepo = GTFS_GH_REPO
        )
        locationRepo.requestFreshFix()
    }

    suspend fun searchStops(globbedStopName: String): List<GlobbedStopRecord> {
        return gtfsStaticRepository.getGlobbedStopsByName(globbedStopName)
    }

    val getGlobbedStopById = gtfsStaticRepository::getGlobbedStopById

    suspend fun getByTrip(tripId: String, date: LocalDate): List<StopTimesRecord> {
        return gtfsStaticRepository.getStopTimesByTripId(tripId, date)
    }

    suspend fun addSavedStop(globbedBusStopRecord: GlobbedStopRecord) {
        settingsRepo.addSavedStop(globbedBusStopRecord.globbedStopId)
    }

    suspend fun removeSavedStop(globbedBusStopRecord: GlobbedStopRecord) {
        settingsRepo.removeSavedStop(globbedBusStopRecord.globbedStopId)
    }
}