package com.example.learning

import android.location.Location
import com.example.learning.database.BusStopInfo
import com.example.learning.database.BusStopInfoEntity
import com.example.learning.database.GtfsStaticRepository
import com.example.learning.database.ScheduledStopTimesInfo
import com.example.learning.database.TripInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.collections.sortedBy
import kotlin.math.pow

class BusInfo(
    private val gtfsStaticRepository: GtfsStaticRepository,
    private val location: StateFlow<Location?>,
    private val scope: CoroutineScope,
) {
    var allBusStops: List<BusStopInfo> = emptyList()
    private val _focusedBusStop = MutableStateFlow<BusStopInfo?>(null)
    val focusedBusStop = _focusedBusStop.asStateFlow()

    // Derived state - automatically updates when location or allBusStops change
    val closestBusStops: StateFlow<List<BusStopInfo>> = updateClosestBusStops()
    val associatedStopTimes: StateFlow<List<ScheduledStopTimesInfo>> = updateAssociatedStopTimes()

    init {
        scope.launch {
            allBusStops = gtfsStaticRepository.getStops()
        }
    }

    private fun updateClosestBusStops(): StateFlow<List<BusStopInfo>> {
        return location.map { loc ->
            loc?.let {allBusStops.sortedBy {
                (loc.latitude - it.latitude.toDouble()).pow(2) + (loc.longitude - it.longitude.toDouble()).pow(2)
            }.take(10)
            } ?: emptyList()
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )
    }

    private fun updateAssociatedStopTimes(): StateFlow<List<ScheduledStopTimesInfo>> {
        @OptIn(ExperimentalCoroutinesApi::class)
        return focusedBusStop
            .filterNotNull()
            .distinctUntilChanged()
            .transformLatest { busStop ->
                emit(emptyList())
                val time = LocalDateTime.now()
                val (trips, index) = gtfsStaticRepository.getAssociatedTrips(busStop.id, time)
                emit(trips.drop(index) + trips.take(index))
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

    suspend fun getTripInfo(tripId: String): TripInfo {
        return gtfsStaticRepository.getTripInfo(tripId)
    }
}