package com.example.learning.repos

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.BuildConfig
import com.google.protobuf.CodedInputStream
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Immutable
data class RealtimeBusInfo(
    val id: String,
    val tripId: String,
    val updatedAt: LocalDateTime,
    val stopTimeDelays: List<Pair<String, Int?>>,
    val vehicleLicencePlate: String,
)

class GtfsRealtimeRepository(
    private val httpClient: OkHttpClient
) {
    private val gtfsUrl = "https://api.transport.nsw.gov.au/v1/gtfs/realtime/buses"
    private val apiKey = BuildConfig.TRANSPORT_NSW_API_KEY
    private val request = Request.Builder().url(gtfsUrl).header("Authorization", value = "apikey $apiKey").build()

    suspend fun getBusData(): List<RealtimeBusInfo> = withContext(Dispatchers.IO) {
        Log.d("GTFS-Realtime", "Starting getBusData." )
        val closestBuses = mutableListOf<RealtimeBusInfo>()

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
                val bus = convertToBusInfo(entity)
                closestBuses.add(bus)
                cis.popLimit(oldLimit)
            } else {
                cis.skipField(tag)
            }
        }
        response.close()

        Log.d("GTFS-Realtime", "Finished getBusData.")
        return@withContext closestBuses
    }

    private fun convertToBusInfo(entity: FeedEntity): RealtimeBusInfo {
        val tripUpdate = entity.tripUpdate

        return RealtimeBusInfo(
            id = entity.id,
            tripId = tripUpdate.trip.tripId,
            updatedAt = LocalDateTime.ofInstant(
               Instant.ofEpochSecond(tripUpdate.timestamp),
               ZoneId.systemDefault()
            ),
            stopTimeDelays = tripUpdate.stopTimeUpdateList.map {
                if (it.scheduleRelationship == ScheduleRelationship.SCHEDULED) {
                    it.stopId to it.departure.delay
                } else {
                    it.stopId to null
                }
            },
            vehicleLicencePlate = tripUpdate.vehicle.licensePlate
        )
    }
}
