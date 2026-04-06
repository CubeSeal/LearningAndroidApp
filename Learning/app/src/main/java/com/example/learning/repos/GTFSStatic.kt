package com.example.learning.repos

import android.util.Log
import android.util.Log.i
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime
import com.example.learning.BuildConfig
import com.example.learning.db.GtfsDatabase
import com.example.learning.repos.BusStopTimesInfo.Companion.parseGtfsTime
import com.example.learning.db.syncGtfsDatabase
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.Long

@Serializable
data class LatLon(val latitude: Double, val longitude: Double)

@Serializable
@Immutable
data class BusCalenderInfo (
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
)

@Serializable
@Immutable
data class BusStopTimesRecord(
    val fakeId: Int, // A fake id not provided
    val stopTimesInfo: BusStopTimesInfo,
    val stopInfo: BusStopInfo,
    val tripInfo: BusTripInfo,
    val routeInfo: BusRouteInfo,
    val calendarInfo: BusCalenderInfo
)

@Serializable
@Immutable
data class BusRouteInfo (
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String
)

@Serializable
@Immutable
data class BusStopInfo(
    val stopId: String,
    val stopName: String,
    val stopLoc: LatLon,
    val wheelchairBoarding: Boolean
)

@Serializable
@Immutable
data class BusStopTimesInfo(
    val fakeId: Long, // Unique ID for Lazy columns.
    val tripId: String,
    // In seconds since beginning of day (for ordering and to handle after 24:00 time).
    val departureTime: Int,
    val arrivalTime: Int,
    val sequence: Int,
) {
    fun formatGtfsTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
    fun formatArrivalTime(): String = formatGtfsTime(arrivalTime)
    fun formatDepartureTime(): String = formatGtfsTime(departureTime)
    companion object {
        fun parseGtfsTime(time: String): Int {
            val parts = time.split(":")
            return parts[0].toInt() * 3600 +
                    parts[1].toInt() * 60 +
                    parts[2].toInt()
        }
    }
}

@Serializable
@Immutable
data class BusTripInfo(
    val routeId: String,
    val serviceId: String,
    val tripId: String,
    val tripHeadsign: String
)

class GtfsStaticRepository(
    private val fileRepository: FileRepository,
    private val httpClient: OkHttpClient,
    private val db: GtfsDatabase
) {
    suspend fun getStops(): List<BusStopInfo> {
        val gtfsDao = db.gtfsDao()

        return gtfsDao
            .getAllStops()
            .map {
                BusStopInfo(
                   it.stopId,
                   it.stopName!!,
                    LatLon(
                        it.stopLat!!,
                        it.stopLon!!
                    ),
                    it.wheelchairBoarding == 1
                )
            }
    }

    // This is now lightning fast
    suspend fun getAssociatedTrips(busStopInfo: BusStopInfo, time: LocalDateTime): Pair<List<BusStopTimesRecord>, Int> {
        Log.d("GTFS", "Started getAssociatedTrips")
        val gtfsDao = db.gtfsDao()
        val nowTime = time.toLocalTime()

        val busStopTimesInfo = coroutineScope {
            gtfsDao.getStopTimesForStop(busStopInfo.stopId)
                .mapIndexed { i, entity ->
                    async {
                        val busTripInfo = gtfsDao.getTrip(entity.tripId)!!.let {
                            BusTripInfo(
                                routeId = it.routeId,
                                serviceId = it.serviceId,
                                tripId = entity.tripId,
                                tripHeadsign = it.tripHeadsign!!
                            )
                        }
                        val busRouteInfo = async {
                            gtfsDao.getRoute(busTripInfo.routeId)!!.let {
                                BusRouteInfo(
                                    routeId = busTripInfo.routeId,
                                    routeShortName = it.routeShortName!!,
                                    routeLongName = it.routeLongName!!,
                                )
                            }
                        }
                        val busCalenderInfo = async {
                            gtfsDao.getCalendar(busTripInfo.serviceId)!!.let {
                                BusCalenderInfo(
                                    monday = it.monday == 1,
                                    tuesday = it.tuesday == 1,
                                    wednesday = it.wednesday == 1,
                                    thursday = it.thursday == 1,
                                    friday = it.friday == 1,
                                    saturday = it.saturday == 1,
                                    sunday = it.sunday == 1,
                                )
                            }
                        }
                        BusStopTimesRecord(
                            fakeId = i,
                            stopTimesInfo = BusStopTimesInfo(
                                fakeId = i.toLong(),
                                tripId = entity.tripId,
                                departureTime = parseGtfsTime(entity.departureTime!!),
                                arrivalTime = parseGtfsTime(entity.arrivalTime!!),
                                sequence = entity.stopSequence
                            ),
                            stopInfo = busStopInfo,
                            tripInfo = busTripInfo,
                            routeInfo = busRouteInfo.await(),
                            calendarInfo = busCalenderInfo.await(),
                        )
                    }
                }.awaitAll()
        }.sortedWith(compareBy { it.stopTimesInfo.arrivalTime })

        val prefix = busStopTimesInfo.indexOfFirst { it.stopTimesInfo.arrivalTime > nowTime.toSecondOfDay()}

        return Pair(busStopTimesInfo, if (prefix == -1) 0 else prefix)
    }

    suspend fun getByTrip(busStopInfo: BusStopTimesRecord): List<BusStopTimesRecord> = withContext(Dispatchers.IO) {
        Log.d("GTFS", "Started getByTrip")
        val gtfsDao = db.gtfsDao()

        return@withContext gtfsDao.getStopTimesForTrip(busStopInfo.tripInfo.tripId)
            .mapIndexed { index, entity ->
                val busStopTimesInfo = BusStopTimesInfo(
                    fakeId = index.toLong(),
                    tripId = entity.tripId,
                    departureTime = parseGtfsTime(entity.departureTime!!),
                    arrivalTime = parseGtfsTime(entity.arrivalTime!!),
                    sequence = entity.stopSequence
                )

                val localBusStopInfo = gtfsDao.getStop(entity.stopId)!!.let {
                    BusStopInfo(
                        stopId = entity.stopId,
                        stopName = it.stopId,
                        stopLoc = LatLon(it.stopLat!!, it.stopLon!!),
                        wheelchairBoarding = it.wheelchairBoarding == 1
                    )
                }

                BusStopTimesRecord(
                    fakeId = index,
                    stopTimesInfo = busStopTimesInfo,
                    stopInfo = localBusStopInfo,
                    tripInfo = busStopInfo.tripInfo,
                    routeInfo = busStopInfo.routeInfo,
                    calendarInfo = busStopInfo.calendarInfo
                )
            }.sortedWith(compareBy { it.stopTimesInfo.sequence })
    }
}
