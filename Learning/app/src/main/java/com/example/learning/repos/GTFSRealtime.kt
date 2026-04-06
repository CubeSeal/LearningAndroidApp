package com.example.learning.repos

import android.location.Location
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.BuildConfig
import com.google.protobuf.CodedInputStream
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.TreeSet

@Serializable
@Immutable
data class RealtimeBusInfo(
    val id: String,
    val tripId: String,
    val vehicleId: String,
    val licencePlate: String,
    val routeId: String,
    val distance: Double,
    val timestamp: Long,
)

class GtfsRealtimeRepository(
    private val httpClient: OkHttpClient
) {
    private val gtfsUrl = "https://api.transport.nsw.gov.au/v1/gtfs/vehiclepos/buses"
    private val apiKey = BuildConfig.TRANSPORT_NSW_API_KEY
    private val request = Request.Builder().url(gtfsUrl).header("Authorization", value = "apikey $apiKey").build()

    suspend fun getBusData(location: Location): List<RealtimeBusInfo> = withContext(Dispatchers.IO) {
        Log.d("GTFS-Realtime", "Starting getBusData." )
        val closestBuses = TreeSet(
            compareBy<RealtimeBusInfo> { it.distance }  // Sort by distance
                .thenBy { it.id }                // Break ties with unique ID
        )
        val maxSize = 1000
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
                val bus = convertToBusInfo(location, entity) ?: break

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

        Log.d("GTFS-Realtime", "Finished getBusData.")
        return@withContext closestBuses.toList()
    }

    private fun convertToBusInfo(location: Location, entity: FeedEntity): RealtimeBusInfo? {
        val vehicle = entity.vehicle
        val position = vehicle.position ?: return null
        val tripId = vehicle.trip?.tripId ?: return null
        val vehicleId = vehicle.vehicle?.id ?: return null
        val licencePlate = vehicle.vehicle?.licensePlate?: return null
        val routeId = vehicle.trip?.routeId ?: return null
        val distance = Location("bus")
            .apply {
                latitude = position.latitude.toDouble()
                longitude = position.longitude.toDouble()
            }
            .distanceTo(location).toDouble()

        return RealtimeBusInfo(
            id = entity.id,
            tripId = tripId,
            vehicleId = vehicleId,
            licencePlate = licencePlate,
            routeId = routeId,
            distance = distance,
            timestamp = vehicle.timestamp
        )
    }
}
