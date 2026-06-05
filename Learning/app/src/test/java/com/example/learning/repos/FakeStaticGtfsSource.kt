package com.example.learning.repos

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/**
 * State-based fake for [StaticGtfsSource], seeded with canned lookup tables. Returns whatever was
 * seeded; `syncGtfsDatabase` just flips [isUpToDate] so tests can observe the effect via the flow.
 */
class FakeStaticGtfsSource(
    private val stopsById: Map<String, GlobbedBusStopRecord> = emptyMap(),
    private val stopsByName: Map<String, List<GlobbedBusStopRecord>> = emptyMap(),
    private val closest: List<GlobbedBusStopRecord> = emptyList(),
    private val stopTimesByStop: Map<String, List<BusStopTimesRecord>> = emptyMap(),
    private val stopTimesByTrip: Map<String, List<BusStopTimesRecord>> = emptyMap(),
) : StaticGtfsSource {
    override val isUpToDate = MutableStateFlow(false)

    override suspend fun getGlobbedStopById(globbedStopId: String): GlobbedBusStopRecord? =
        stopsById[globbedStopId]

    override suspend fun getGlobbedStopsByName(globbedStopName: String): List<GlobbedBusStopRecord> =
        stopsByName[globbedStopName] ?: emptyList()

    override suspend fun getNClosestStops(location: LatLon, length: Int): List<GlobbedBusStopRecord> =
        closest.take(length)

    override suspend fun getStopTimesByStop(globbedBusStopRecord: GlobbedBusStopRecord): List<BusStopTimesRecord> =
        stopTimesByStop[globbedBusStopRecord.globbedStopId] ?: emptyList()

    override suspend fun getStopTimesByTripId(tripId: String, date: LocalDate): List<BusStopTimesRecord> =
        stopTimesByTrip[tripId] ?: emptyList()

    override suspend fun syncGtfsDatabase(
        ghOwner: String,
        ghRepo: String,
        force: Boolean,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        isUpToDate.value = true
    }
}
