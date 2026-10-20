package com.example.learning

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.repos.ClockSource
import com.example.learning.repos.GTFS_GH_OWNER
import com.example.learning.repos.GTFS_GH_REPO
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LocationMode
import com.example.learning.repos.LocationSource
import com.example.learning.repos.RealtimeGtfsSource
import com.example.learning.repos.RealtimeTripInfo
import com.example.learning.repos.ScheduledStopTime
import com.example.learning.repos.ServiceCalendar
import com.example.learning.repos.SettingsSource
import com.example.learning.repos.StaticGtfsSource
import com.example.learning.repos.StopTimesRecord
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
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

@Serializable
sealed interface TransitFilterOptions: Comparable<TransitFilterOptions> {
    // Mandatory rank params
    val typeRank: Int
    val modeRank: Int
    val filterLabel: String

    override fun compareTo(other: TransitFilterOptions): Int =
        compareValuesBy(this, other,
            { it.typeRank },
            { it.modeRank },
            // Labels are compared numerically first so bus routes read as numbers ("9" before "370" before
            // "412"), with non-numeric labels ("L90") falling after them.
            { it.filterLabel.toIntOrNull() ?: Int.MAX_VALUE },
            { it.filterLabel })

    @Serializable
    data class TransportMode(val mode: TransitMode): TransitFilterOptions {
        override val typeRank = 0
        override val modeRank = mode.rank
        override val filterLabel = this.mode.label
    }

    @Serializable
    data class StopStand(val stopStand: String, val mode: TransitMode): TransitFilterOptions {
        override val typeRank = 1
        override val modeRank = mode.rank
        override val filterLabel = this.stopStand
        init { require(stopStand.isNotBlank()) }
    }

    @Serializable
    data class RouteShortName(val routeShortName: String, val mode: TransitMode): TransitFilterOptions {
        override val typeRank = 2
        override val modeRank = mode.rank
        override val filterLabel = this.routeShortName
        init { require(routeShortName.isNotBlank()) }
    }

    @Serializable
    data class TripHeadsign(val tripHeadsign: String): TransitFilterOptions {
        override val typeRank = 3
        override val modeRank = 0
        override val filterLabel = this.tripHeadsign
        init { require(tripHeadsign.isNotBlank()) }
    }
}

/**
 * A saved stop as persisted: the stop id plus the filter **combos** saved against it. Each combo is
 * a set of filters (stored as a `List` for stable serialization). An entry with no combos is a
 * "naked" saved stop. See [SettingsSource]. [SavedStop] is the hydrated, UI-facing projection.
 */
@Serializable
data class SavedStopEntry(val stopId: String, val combos: List<List<TransitFilterOptions>> = emptyList())

/** Hydrated saved stop for the UI: the resolved [GlobbedStopRecord] plus its saved filter combos. */
@Immutable
data class SavedStop(val stop: GlobbedStopRecord, val combos: List<Set<TransitFilterOptions>>)

@Immutable
data class StopTimesRecordWithRealtime(
    // I think it's okay to have nesting like this if the underlying data structures are still flat.
    // Just becomes a convenient grouping and constructor thing.
    val stopTimesRecord: StopTimesRecord,
    // Normally I'd make this flat instead of nesting it like this, but making this nullable this is a convenient way
    // to mark something as not having realtime info.
    val realtimeStopTimesRecord: RealtimeStopTimesRecord?,
)

/** The focused stop's timetable, undated, plus the calendar that places it onto days. */
@Immutable
data class StopSchedule(
    val stopTimes: List<ScheduledStopTime> = emptyList(),
    val calendar: ServiceCalendar = ServiceCalendar(emptyMap()),
)

// Set of filters that apply to this departure.
val ScheduledStopTime.applicableFilters: Set<TransitFilterOptions>
    get() = setOfNotNull(
        routeShortName?.takeIf { it.isNotBlank() }?.let { TransitFilterOptions.RouteShortName(it, routeType) },
        tripHeadsign?.takeIf { it.isNotBlank() }?.let { TransitFilterOptions.TripHeadsign(it) },
        stopName.takeIf { it.isNotBlank() }?.let { TransitFilterOptions.StopStand(it, routeType) },
        TransitFilterOptions.TransportMode(routeType),
    )

/**
 * Joins realtime onto stitched departures. The feed only describes a trip's current run, but the same
 * trip id recurs on every day its service runs — so it attaches to the first (nearest) run only.
 */
fun Sequence<StopTimesRecord>.withRealtime(
    realtime: Map<Pair<String, String>, RealtimeStopTimesRecord>,
): Sequence<StopTimesRecordWithRealtime> = sequence {
    val claimed = mutableSetOf<Pair<String, String>>()
    for (record in this@withRealtime) {
        val key = record.tripId to record.stopId
        yield(StopTimesRecordWithRealtime(record, realtime[key]?.takeIf { claimed.add(key) }))
    }
}

// Departure time adjusted by realtime delay. Null delay means "no realtime info yet" everywhere else,
// but for ordering/time-filtering we settle it to zero.
val StopTimesRecordWithRealtime.effectiveDepartureTime: LocalDateTime
    get() = stopTimesRecord.departureTime +
        (realtimeStopTimesRecord?.stopTimeDelay ?: Duration.ZERO)

@OptIn(ExperimentalCoroutinesApi::class)
class TransitInfo(
    val gtfsStaticRepository: StaticGtfsSource,
    private val gtfsRealtimeRepository: RealtimeGtfsSource,
    private val locationRepo: LocationSource,
    private val settingsRepo: SettingsSource,
    private val clock: ClockSource,
    private val scope: CoroutineScope,
) {
    val currentMinute: StateFlow<LocalDateTime> = clock.currentMinute
        .stateIn(scope, SharingStarted.Eagerly, LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))

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

    // Whether the focused stop should keep following the user's current location. Defaults to true;
    // turned off by any manual stop pick (see updateFocusedBusStop) so the user's choice isn't
    // silently overwritten by the next location update.
    val followLocation: StateFlow<Boolean> = settingsRepo.followLocation
        .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = true)

    suspend fun setFollowLocation(enabled: Boolean) {
        settingsRepo.setFollowLocation(enabled)
        if (enabled) locationRepo.requestFreshFix() // re-snap immediately, don't wait for the poll
    }

    init {
        // Drive the location provider from the toggle: Balanced gives continuous updates while
        // following, Off stops them entirely (setMode was previously never called anywhere, so
        // location updates never actually ran beyond the one-shot requestFreshFix() in refresh()).
        followLocation
            .onEach { locationRepo.setMode(if (it) LocationMode.Balanced else LocationMode.Off) }
            .launchIn(scope)

        // While following, keep the focused stop pinned to the closest one. Written straight to
        // settingsRepo (not updateFocusedBusStop) so this doesn't itself trip the manual-pick auto-off.
        //
        // distinctUntilChanged is keyed on (follow, stopId) together, not stopId alone: a manual pick
        // happens while follow=false, so it never reaches this pipeline (filtered out below), and
        // never updates "the last stopId seen here". If the user then re-enables following while
        // standing in the same spot as before the manual pick, the closest stop id is identical to
        // what it was pre-pick — keying on stopId alone would treat that as "no change" and skip
        // re-snapping, silently leaving the manually-picked stop focused. Including `follow` in the
        // key ensures the false->true edge is never conflated away.
        combine(followLocation, closestBusStops) { follow, closest ->
            follow to closest.firstOrNull()?.globbedStopId
        }
            .distinctUntilChanged()
            .onEach { (follow, stopId) -> if (follow && stopId != null) settingsRepo.setHomeStopId(stopId) }
            .launchIn(scope)
    }

    // Polled every minute so delays stay current while the phone sits still. A location update still
    // refetches too: pull-to-refresh requests a fresh fix, and that's what makes it refresh realtime.
    val realtimeStopTimes: StateFlow<Map<Pair<String, String>, RealtimeStopTimesRecord>> =
        merge(currentMinute, locationRepo.currentLocation).mapLatest {
            gtfsRealtimeRepository.getRealtimeData()
                .flatMap { it.toRealtimeStopTimesRecord() }
                .associateBy { it.tripId to it.stopId }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    // flatMapLatest cancels the previous stop's in-flight query on switch; emitting empty first
    // clears the old stop's departures immediately (rather than leaving them stale while the new
    // query runs).
    val schedule: StateFlow<StopSchedule> = focusedBusStop
        .filterNotNull()
        .flatMapLatest { stop ->
            flow {
                emit(StopSchedule())
                emit(StopSchedule(gtfsStaticRepository.getScheduledStopTimesByStop(stop), gtfsStaticRepository.getServiceCalendar()))
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = StopSchedule()
        )

    // Built from the whole timetable rather than any page of departures, so every route the stop
    // serves is offered however far out its next run is.
    val filtersForBusStop = schedule
        .map { it.stopTimes }
        .filter { it.isNotEmpty() }
        .mapLatest { associatedTrips ->
            // Each trip contributes the set of filter options that apply to it. A filter only helps if
            // it distinguishes some trips from others — an option every trip carries (e.g. a stop served
            // by a single route, or all-bus services) filters nothing, so we drop it.
            val perTripFilters = associatedTrips.map { stopTimesRecord ->
                val mode = stopTimesRecord.routeType
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

    // Inverted index: filter option -> its matching timetable rows. Built once per stop (O(4n)) so a
    // selected filter can stitch just its rows instead of the whole timetable. Consumed by
    // HomeViewModel's filtering.
    val filterIndex: StateFlow<Map<TransitFilterOptions, List<ScheduledStopTime>>> =
        schedule.map { schedule ->
            buildMap<TransitFilterOptions, MutableList<ScheduledStopTime>> {
                for (row in schedule.stopTimes)
                    for (f in row.applicableFilters)
                        getOrPut(f) { mutableListOf() }.add(row)
            }
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyMap()
        )

    val savedStops: StateFlow<List<SavedStop>> = settingsRepo.savedStops
        .map { entries ->
            entries.mapNotNull { entry ->
                gtfsStaticRepository.getGlobbedStopById(entry.stopId)?.let { stop ->
                    SavedStop(stop, entry.combos.map { it.toSet() })
                }
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    // Filters applied to the focused stop. Owned here, not in HomeViewModel: staging is per-screen,
    // but the committed set is shared with the FilterPage's own ViewModel and the saved-stops page.
    private val _selectedFilters = MutableStateFlow(emptySet<TransitFilterOptions>())
    val selectedFilters: StateFlow<Set<TransitFilterOptions>> = _selectedFilters.asStateFlow()

    // Promoted from the FilterPage so the Home row keeps showing them after deselection.
    private val _pinnedFilters = MutableStateFlow(emptySet<TransitFilterOptions>())
    val pinnedFilters: StateFlow<Set<TransitFilterOptions>> = _pinnedFilters.asStateFlow()

    /** Commit a chosen selection, pinning it so the Home row keeps it visible. */
    fun applyFilters(selected: Set<TransitFilterOptions>) {
        _selectedFilters.value = selected
        _pinnedFilters.update { it + selected }
    }

    /** A Home row tap. Doesn't pin — the option is already in the row. */
    fun toggleFilter(option: TransitFilterOptions) {
        _selectedFilters.update { if (option in it) it - option else it + option }
    }

    /** Back to "show everything", with the row falling back to its base slice. */
    fun clearFilters() {
        _selectedFilters.value = emptySet()
        _pinnedFilters.value = emptySet()
    }

    /** Focus [stop] and apply the saved [combo] (empty = the naked stop, so clear). */
    suspend fun selectSavedStop(stop: GlobbedStopRecord, combo: Set<TransitFilterOptions>) {
        updateFocusedBusStop(stop)
        if (combo.isEmpty()) clearFilters() else applyFilters(combo)
    }

    // A manual stop pick (search, trip stop tap, or a saved stop via selectSavedStop above). Turns
    // off follow-my-location so this choice isn't overwritten by the next location update.
    suspend fun updateFocusedBusStop(globbedBusStopRecord: GlobbedStopRecord) {
        settingsRepo.setFollowLocation(false)
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

    /** Save [stop]; if [filters] is non-empty, also save it as a filter combo under that stop. */
    suspend fun saveStop(stop: GlobbedStopRecord, filters: Set<TransitFilterOptions>) {
        if (filters.isEmpty()) settingsRepo.addSavedStop(stop.globbedStopId)
        else settingsRepo.addSavedCombo(stop.globbedStopId, filters.toList())
    }

    /** Remove a single saved filter combo from a stop (the stop itself stays saved). */
    suspend fun removeSavedCombo(stopId: String, combo: Set<TransitFilterOptions>) {
        settingsRepo.removeSavedCombo(stopId, combo.toList())
    }

    /** Clear-all: remove the whole saved stop, combos included. */
    suspend fun removeSavedStop(globbedBusStopRecord: GlobbedStopRecord) {
        settingsRepo.removeSavedStop(globbedBusStopRecord.globbedStopId)
    }
}
