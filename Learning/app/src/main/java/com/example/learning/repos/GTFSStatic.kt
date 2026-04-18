package com.example.learning.repos

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.db.GtfsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

data class LatLon(val latitude: Double, val longitude: Double)

@Immutable
data class BusStopTimesRecord(
    val fakeId: Int, // A fake id not provided
    val stopTimesInfo: BusStopTimesInfo,
    val stopInfo: BusStopInfo,
    val tripInfo: BusTripInfo,
    val routeInfo: BusRouteInfo,
)

@Immutable
data class BusRouteInfo (
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String
)

@Immutable
data class BusStopInfo(
    val stopId: String,
    val stopName: String,
    val stopLoc: LatLon,
    val wheelchairBoarding: Boolean
)

@Immutable
data class BusStopTimesInfo(
    val fakeId: Long, // Unique ID for Lazy columns.
    val tripId: String,
    // In seconds since beginning of day (for ordering and to handle after 24:00 time).
    val departureTime: LocalDateTime,
    val arrivalTime: LocalDateTime,
    val sequence: Int,
)

@Immutable
data class BusTripInfo(
    val routeId: String,
    val serviceId: String,
    val tripId: String,
    val tripHeadsign: String
)

fun parseGtfsDateTime(date: LocalDate, time: String): LocalDateTime {
    val parts = time.trim().split(":")
    val hours = parts[0].toInt()
    val mins = parts[1].toInt()
    val secs = parts[2].toInt()
    return date.atStartOfDay()
        .plusHours(hours.toLong())
        .plusMinutes(mins.toLong())
        .plusSeconds(secs.toLong())
}

class GtfsStaticRepository(
    private val context: Context,
    private val fileRepository: FileRepository,
    private val httpClient: OkHttpClient
) {
    private val db get() = GtfsDatabase.getInstance(context)
    private val gtfsDao get() = db.gtfsDao()
    private var _calendarSequences: Map<String, Sequence<LocalDate>>? = null
    private val calMutex = Mutex()

    val isUpToDate = MutableStateFlow(false)

    private suspend fun calendarSequences(): Map<String, Sequence<LocalDate>> =
        _calendarSequences ?: calMutex.withLock {
            _calendarSequences ?: preloadCalendarDates().also { _calendarSequences = it }
        }

    private suspend fun preloadCalendarDates(): Map<String, Sequence<LocalDate>> {
        return gtfsDao.getAllCalendar().associate { calendarEntity ->
            val startDate = LocalDate.parse(calendarEntity.startDate,DateTimeFormatter.ofPattern("yyyyMMdd"))
            val endDate = LocalDate.parse(calendarEntity.endDate,DateTimeFormatter.ofPattern("yyyyMMdd"))
            val values = generateSequence(startDate) {
                it.plusDays(1)
            }.takeWhile {
                !it.isAfter(endDate) &&
                        !it.isAfter(LocalDate.now().plusWeeks(2))
            }.filter { date ->
                when (date.dayOfWeek) {
                    DayOfWeek.MONDAY -> calendarEntity.monday == 1
                    DayOfWeek.TUESDAY -> calendarEntity.tuesday == 1
                    DayOfWeek.WEDNESDAY -> calendarEntity.wednesday == 1
                    DayOfWeek.THURSDAY -> calendarEntity.thursday == 1
                    DayOfWeek.FRIDAY -> calendarEntity.friday == 1
                    DayOfWeek.SATURDAY -> calendarEntity.saturday == 1
                    DayOfWeek.SUNDAY -> calendarEntity.sunday == 1
                }
            }
            calendarEntity.serviceId to values
        }.also {
            it.forEach { (id, sequence) ->
                Log.d("GTFS", "Preloaded calendar details: $id, ${sequence.joinToString()}")
            }
        }
    }

    suspend fun getStops(): List<BusStopInfo> {
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

        val busStopTimesInfo = coroutineScope {
            gtfsDao.getStopTimesForStop(busStopInfo.stopId)
                .map { entity ->
                    async {
                        val busTripInfo = gtfsDao.getTrip(entity.tripId)!!.let {
                            BusTripInfo(
                                routeId = it.routeId,
                                serviceId = it.serviceId,
                                tripId = entity.tripId,
                                tripHeadsign = it.tripHeadsign!!
                            )
                        }

                        val busRouteInfo = gtfsDao.getRoute(busTripInfo.routeId)!!.let {
                            BusRouteInfo(
                                routeId = busTripInfo.routeId,
                                routeShortName = it.routeShortName!!,
                                routeLongName = it.routeLongName!!,
                            )
                        }

                        return@async calendarSequences().getValue(busTripInfo.serviceId).map {
                            BusStopTimesRecord(
                                fakeId = 0,
                                stopTimesInfo = BusStopTimesInfo(
                                    fakeId = 0.toLong(),
                                    tripId = entity.tripId,
                                    departureTime = parseGtfsDateTime(it, entity.departureTime!!),
                                    arrivalTime = parseGtfsDateTime(it, entity.arrivalTime!!),
                                    sequence = entity.stopSequence
                                ),
                                stopInfo = busStopInfo,
                                tripInfo = busTripInfo,
                                routeInfo = busRouteInfo,
                            )
                        }.toList()
                    }
                }.awaitAll().flatten().mapIndexed {i, it ->
                    it.copy(
                        fakeId = i,
                        stopTimesInfo = it.stopTimesInfo.copy(fakeId = i.toLong())
                    )
                }
        }.sortedWith(compareBy { it.stopTimesInfo.arrivalTime } )
        Log.d("GTFS", "Finished StopTimes Parsing.")

        val prefix = busStopTimesInfo.indexOfFirst { it.stopTimesInfo.departureTime.isAfter(time) }

        return Pair(busStopTimesInfo, if (prefix == -1) 0 else prefix)
    }

    suspend fun getByTrip(busStopInfo: BusStopTimesRecord): List<BusStopTimesRecord> = withContext(Dispatchers.IO) {
        Log.d("GTFS", "Started getByTrip")
        val date = busStopInfo.stopTimesInfo.departureTime.toLocalDate()

        return@withContext gtfsDao.getStopTimesForTrip(busStopInfo.tripInfo.tripId)
            .mapIndexed { index, entity ->

                val busStopTimesInfo = BusStopTimesInfo(
                    fakeId = index.toLong(),
                    tripId = entity.tripId,
                    departureTime = parseGtfsDateTime(date, entity.departureTime!!),
                    arrivalTime = parseGtfsDateTime(date, entity.arrivalTime!!),
                    sequence = entity.stopSequence
                )

                val localBusStopInfo = gtfsDao.getStop(entity.stopId)!!.let {
                    BusStopInfo(
                        stopId = entity.stopId,
                        stopName = it.stopName ?: "Missing stop name.",
                        stopLoc = LatLon(it.stopLat!!, it.stopLon!!),
                        wheelchairBoarding = it.wheelchairBoarding == 1
                    )
                }

                BusStopTimesRecord(
                    fakeId = index,
                    stopTimesInfo = busStopTimesInfo,
                    stopInfo = localBusStopInfo,
                    tripInfo = busStopInfo.tripInfo,
                    routeInfo = busStopInfo.routeInfo
                )
            }.sortedWith(compareBy { it.stopTimesInfo.sequence })
    }


    /**
     * Check for a newer GTFS database on GitHub Releases and download it if available.
     *
     * Returns `true` if a new DB was downloaded and applied, `false` if already up to date,
     * and throws on network/IO errors.
     */
    suspend fun syncGtfsDatabase(
        ghOwner: String,
        ghRepo: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val tag = "GtfsSync"
        val timestampFile = "timestamp"
        val json = Json { ignoreUnknownKeys = true }
        val localTimestamp = fileRepository.readFile(timestampFile)?.let {
            try { Instant.parse(it) } catch (e: Exception) {
                Log.w(tag, "Could not parse saved timestamp, treating as stale", e)
                null
            }
        }

        Log.d(tag, "Checking for GTFS update (local timestamp: $localTimestamp)")

        // ── Fetch latest release metadata ────────────────────────────
        val request = Request.Builder()
            .url("https://api.github.com/repos/$ghOwner/$ghRepo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        val (downloadUrl, remoteTimestamp, sha256) = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub API request failed: ${response.code}")
            val body = response.body?.string() ?: error("Empty response from GitHub API")
            val obj = json.parseToJsonElement(body).jsonObject

            val publishedAt = obj["published_at"]?.jsonPrimitive?.content
                ?.let { Instant.parse(it) }
                ?: error("Release missing published_at")

            if (localTimestamp != null && localTimestamp >= publishedAt) {
                Log.d(tag, "Up to date ($localTimestamp >= $publishedAt)")

                isUpToDate.update { true }
                return@withContext
            }

            val assets = obj["assets"]?.jsonArray ?: error("Release has no assets")
            val dbAsset = assets
                .map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".db.gz") == true }
                ?: error("No .db.gz asset found in release")

            val url = dbAsset["browser_download_url"]?.jsonPrimitive?.content
                ?: error("Asset missing download URL")

            val releaseBody = obj["body"]?.jsonPrimitive?.content ?: ""
            val hash = Regex("""SHA-256:\s*`?([a-f0-9]{64})`?""")
                .find(releaseBody)?.groupValues?.get(1)

            Triple(url, publishedAt, hash)
        }

        Log.d(tag, "New version available (published: $remoteTimestamp), downloading...")

        // ── Download ─────────────────────────────────────────────────
        val tempGzName = "gtfs_download.db.gz"
        val tempDbName = "gtfs_download.db"

        try {
            val dlRequest = Request.Builder().url(downloadUrl).build()
            httpClient.newCall(dlRequest).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: ${response.code}")
                val responseBody = response.body ?: error("Empty download body")
                val total = responseBody.contentLength()

                responseBody.byteStream().use { input ->
                    File(fileRepository.directory, tempGzName).outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var bytesRead = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            bytesRead += n
                            onProgress(bytesRead, total)
                        }
                    }
                }
            }

            // ── Decompress ───────────────────────────────────────────
            val gzFile = File(fileRepository.directory, tempGzName)
            val dbFile = File(fileRepository.directory, tempDbName)
            GZIPInputStream(gzFile.inputStream()).use { gzIn ->
                dbFile.outputStream().use { out -> gzIn.copyTo(out, 65536) }
            }
            fileRepository.deleteFile(tempGzName)

            // ── Verify SHA-256 ───────────────────────────────────────
            if (sha256 != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                dbFile.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != sha256) {
                    error("SHA-256 mismatch: expected $sha256, got $actual")
                }
            }

            // ── Swap into Room ───────────────────────────────────────
            GtfsDatabase.replaceWith(context, dbFile)
            fileRepository.deleteFile(tempDbName)

            // Invalidate calendar cache for getAssociatedTrips
            calMutex.withLock { _calendarSequences = null }

            // ── Write timestamp only after everything succeeded ──────
            fileRepository.writeFile(timestampFile, remoteTimestamp.toString())
            Log.i(tag, "GTFS DB updated (published: $remoteTimestamp)")
            isUpToDate.update { true }
        } catch (e: Exception) {
            fileRepository.deleteFile(tempGzName)
            fileRepository.deleteFile(tempDbName)
            throw e
        }

    }
}
