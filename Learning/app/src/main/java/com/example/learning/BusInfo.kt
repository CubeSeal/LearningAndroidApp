package com.example.learning

import android.location.Location
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.repos.BusStopInfo
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.LatLon
import com.example.learning.repos.RealtimeBusInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import kotlin.collections.sortedBy
import kotlin.math.pow

@Serializable
@Immutable
data class RealtimeBusStopTimesRecord(
    val busStopTimesRecord: BusStopTimesRecord,
    val realtimeBusInfo: RealtimeBusInfo?
)

class BusInfo(
    private val gtfsStaticRepository: GtfsStaticRepository,
    private val gtfsRealtimeRepository: GtfsRealtimeRepository,
    private val location: StateFlow<Location?>,
    private val scope: CoroutineScope,
) {
    var allBusStops: List<BusStopInfo> = emptyList()
    private val _focusedBusStop = MutableStateFlow<BusStopInfo?>(null)
    val focusedBusStop = _focusedBusStop.asStateFlow()

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<BusStopInfo>> = updateClosestBusStops()
    val associatedStopTimes: StateFlow<List<RealtimeBusStopTimesRecord>> = updateAssociatedStopTimes()

    init {
        scope.launch {
            allBusStops = gtfsStaticRepository.getStops()
        }
    }

    private fun updateClosestBusStops(): StateFlow<List<BusStopInfo>> {
        return location.map { loc ->
            loc?.let {allBusStops.sortedBy {
                (loc.latitude - it.stopLoc.latitude).pow(2) + (loc.longitude - it.stopLoc.longitude).pow(2)
            }.take(10)
            } ?: emptyList()
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )
    }

    @OptIn(FlowPreview::class)
    private fun updateAssociatedStopTimes(): StateFlow<List<RealtimeBusStopTimesRecord>> {
        @OptIn(ExperimentalCoroutinesApi::class)
        return combine(
            focusedBusStop.filterNotNull(),
            location.filterNotNull().take(1)
        ) { busStop, loc ->
            Pair(busStop, loc)
        }
            .distinctUntilChanged()
            .transformLatest { (busStop, loc) ->
                emit(emptyList())
                coroutineScope {
                    val time = LocalDateTime.now()
                    val (trips, index) = gtfsStaticRepository.getAssociatedTrips(busStop, time)
                    val closestBuses = gtfsRealtimeRepository.getBusData(loc)
                    Log.d("BusInfo", "Got busInfo from repos: $trips")
                    val returnVal = trips
                        .let { trips.drop(index) + trips.take(index) }
                        .also { Log.d("BusInfo", "Got sorted list of associated trips: $it") }
                        .map { busStopTimesRecord ->
                            RealtimeBusStopTimesRecord(
                                busStopTimesRecord,
                                closestBuses.firstOrNull {
                                    it.tripId == busStopTimesRecord.tripInfo.tripId
                                }
                            )
                        }
                        .also { Log.d("BusInfo", "Finished expensive map with $it.") }
                    emit(returnVal)
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )
    }

    fun updateFocusedBusStop(busStopInfo: BusStopInfo) {
        _focusedBusStop.value = busStopInfo
    }

    suspend fun getByTrip(busStopTimesRecord: BusStopTimesRecord): List<BusStopTimesRecord> {
        return gtfsStaticRepository.getByTrip(busStopTimesRecord)
    }
}