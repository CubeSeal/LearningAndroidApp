package com.example.learning

import GtfsStaticRepository
import android.Manifest
import android.content.Context
import android.location.Location
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Immutable
import com.example.learning.repos.LocationRepository
import com.google.protobuf.CodedInputStream
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalTime
import java.util.TreeSet

@Immutable
data class BusInfo(
    val id: String,
    val vehicleId: String,
    val licencePlate: String,
    val routeId: String,
    val distance: Double,
    val timestamp: Long
)


class BusResource(
    private val locationRepo: LocationRepository,
    private val httpClient: OkHttpClient
) {
    private val gtfsUrl = "https://api.transport.nsw.gov.au/v1/gtfs/vehiclepos/buses"
    private val apiKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiJPOExUTzJkbGJhTmZiZkpDR2VUTDlyMDd3Yk9WVE9LbFFiclN0eDdFUnA4IiwiaWF0IjoxNzY2ODM1ODU1fQ.cdCzNDKLb5eoA1r57iuUAx-aNVSXAdlXS1rIuTm0Q2I"
    private val request =
        Request.Builder().url(gtfsUrl).header("Authorization", value = "apikey $apiKey").build()
    var location: Location? = null

    // TreeSet automatically maintains sorted order!
    private val closestBuses = TreeSet<BusInfo>(
        compareBy<BusInfo> { it.distance }  // Sort by distance
            .thenBy { it.id }                // Break ties with unique ID
    )
    private val maxSize = 100

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun init(context: Context) {
        location = locationRepo.getLastLocation()
        getBusData()
    }

    suspend fun getBusData(): Unit = withContext(Dispatchers.IO) {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed: $response")
        val stream = response.body?.byteStream()
            ?: throw IOException("Could not get bytestream for body: $response")

        val cis = CodedInputStream.newInstance(stream)

        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break

            val fieldNumber = tag ushr 3

            if (fieldNumber == 2) {
                val length = cis.readRawVarint32()
                val oldLimit = cis.pushLimit(length)
                val entity = FeedEntity.parseFrom(cis)
                val bus = convertToBusInfo(entity) ?: continue

                // Keep only closest 100
                closestBuses.add(bus)
                if (closestBuses.size > maxSize) {
                    closestBuses.pollLast()
                }

                cis.popLimit(oldLimit)
            } else {
                cis.skipField(tag)
            }
        }
        response.close()
    }

    private fun convertToBusInfo(entity: FeedEntity): BusInfo? {
        val vehicle = entity.vehicle
        val position = vehicle.position ?: return null
        val vehicleId = vehicle.vehicle?.id ?: return null
        val licencePlate = vehicle.vehicle?.licensePlate?: return null
        val routeId = vehicle.trip?.routeId ?: return null
        val distance = Location("bus")
            .apply {
                latitude = position.latitude.toDouble()
                longitude = position.longitude.toDouble()
            }
            .distanceTo(location!!).toDouble()

        return BusInfo(
            id = entity.id,
            vehicleId = vehicleId,
            licencePlate = licencePlate,
            routeId = routeId,
            distance = distance,
            timestamp = vehicle.timestamp
        )
    }
}

@Immutable
data class BusStopInfo(
    val id: String,
    val name: String,
    val location: Location,
    val wheelchairBoarding: Boolean
) {
    fun getDistance(currentLocation: Location): Float { return location.distanceTo(currentLocation) }
}

@Immutable
data class ScheduledStopTimesInfo(
    val id: Long, // Unique ID for Lazy columns.
    val stopId: String,
    val tripId: String,
    val departureTime: String,
    val arrivalTime: String,
    val tripHeadsign: String,
    val routeShortName: String,
)

class BusStopsResource(
    private val gtfsStaticRepository: GtfsStaticRepository
) {
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])

    suspend fun getAssociatedTrips(stopId: String): List<ScheduledStopTimesInfo> = withContext(Dispatchers.Default) {
        gtfsStaticRepository.getAssociatedTrips(stopId)
    }

    suspend fun getStops(): List<BusStopInfo> {
        return gtfsStaticRepository.getStops()
    }
}
