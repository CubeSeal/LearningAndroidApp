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
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Immutable
data class RealtimeBusTripInfo(
    val id: String,
    val tripId: String,
    val updatedAt: LocalDateTime,
    val stopTimeDelays: List<Pair<String, Int?>>,
    val vehicleLicencePlate: String,
)

/**
 * Prefixes this record's `tripId` and every stop id with [idPrefix], so realtime ids from a namespaced
 * feed (e.g. Sydney Trains, "T:") line up with the prefixed static ids they're joined against by
 * `(tripId, stopId)` in [com.example.learning.BusInfo]. Buses use an empty prefix and pass through
 * unchanged. The entity `id` is left alone — it isn't part of the join key.
 */
fun RealtimeBusTripInfo.withIdPrefix(idPrefix: String): RealtimeBusTripInfo =
    if (idPrefix.isEmpty()) this
    else copy(
        tripId = idPrefix + tripId,
        stopTimeDelays = stopTimeDelays.map { (stopId, delay) -> (idPrefix + stopId) to delay },
    )

/**
 * Live GTFS-Realtime feed, as consumed by [com.example.learning.BusInfo].
 * Plain interface so tests can substitute a state-based fake (see `src/test`).
 */
interface RealtimeGtfsSource {
    suspend fun getBusData(): List<RealtimeBusTripInfo>
}

class GtfsRealtimeRepository(
    private val httpClient: OkHttpClient
) : RealtimeGtfsSource {
    private val apiKey = BuildConfig.TRANSPORT_NSW_API_KEY

    /** A live feed and the id prefix its records carry (matching the static DB's namespacing). */
    private data class RealtimeFeed(val url: String, val idPrefix: String)

    // Buses are still GTFS-R v1 and unprefixed; Sydney Trains uses the v2 feed and the "T:" prefix so
    // its ids match the prefixed train rows in the merged static DB. (The API key must have the train
    // realtime API product enabled.)
    private val feeds = listOf(
        RealtimeFeed("https://api.transport.nsw.gov.au/v1/gtfs/realtime/buses", idPrefix = ""),
        RealtimeFeed("https://api.transport.nsw.gov.au/v2/gtfs/realtime/sydneytrains", idPrefix = "T:"),
    )

    override suspend fun getBusData(): List<RealtimeBusTripInfo> = withContext(Dispatchers.IO) {
        Log.d("GTFS-Realtime", "Starting getBusData.")
        val merged = feeds.flatMap { fetchFeed(it) }
        Log.d("GTFS-Realtime", "Finished getBusData (${merged.size} trip updates).")
        merged
    }

    /**
     * Fetch and decode a single feed, namespacing its ids. A single feed outage (e.g. trains down)
     * must not wipe out realtime for the others, so failures are logged and yield an empty list.
     */
    private fun fetchFeed(feed: RealtimeFeed): List<RealtimeBusTripInfo> = try {
        val request = Request.Builder().url(feed.url).header("Authorization", "apikey $apiKey").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed: $response")
            val stream = response.body?.byteStream()
                ?: throw IOException("Could not get bytestream for body: $response")
            decodeFeed(stream).map { it.withIdPrefix(feed.idPrefix) }
        }
    } catch (e: Exception) {
        Log.w("GTFS-Realtime", "Realtime feed ${feed.url} failed: ${e.message}")
        emptyList()
    }

    private fun decodeFeed(stream: InputStream): List<RealtimeBusTripInfo> {
        val tripInfos = mutableListOf<RealtimeBusTripInfo>()
        val cis = CodedInputStream.newInstance(stream)

        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            if (fieldNumber == 2) {
                val length = cis.readRawVarint32()
                val oldLimit = cis.pushLimit(length)
                tripInfos.add(convertToBusInfo(FeedEntity.parseFrom(cis)))
                cis.popLimit(oldLimit)
            } else {
                cis.skipField(tag)
            }
        }
        return tripInfos
    }

    private fun convertToBusInfo(entity: FeedEntity): RealtimeBusTripInfo {
        val tripUpdate = entity.tripUpdate

        return RealtimeBusTripInfo(
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
