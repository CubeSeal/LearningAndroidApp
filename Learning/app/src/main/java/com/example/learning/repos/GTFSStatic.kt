package com.example.learning.repos

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.learning.db.GtfsDatabase
import com.example.learning.db.StopEntity
import com.example.learning.db.StopTimeWithDetails
import com.example.learning.db.StopWithGlobbedInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotlin.collections.first
import kotlin.collections.map

data class LatLon(val latitude: Double, val longitude: Double)

@Immutable
// Keep these as flat parsed versions of room data structures.
// Dealing with joins and filters in the SQL is a lot easier than trying to mangle it with nested structures here.
// TODO: the "Bus" prefix is no longer representative now that trains share this type (see routeType) —
//  rename the Bus* domain to mode-neutral names (e.g. DepartureRecord) in a later commit.
data class BusStopTimesRecord(
    val tripId: String,
    // In seconds since beginning of day (for ordering and to handle after 24:00 time).
    val departureTime: LocalDateTime,
    val arrivalTime: LocalDateTime,
    val sequence: Int,
    val routeId: String,
    val serviceId: String,
    val tripHeadsign: String,
    val routeShortName: String,
    val routeLongName: String,
    // GTFS route_type (2 = rail, 3 = bus, plus TfNSW extended ranges). Carries the mode so the
    // app can distinguish trains from buses now that the schedule DB merges both feeds.
    val routeType: Int,
    val globbedStopId: String,
    val globbedStopName: String,
    val stopId: String,
    val stopName: String,
    val stopLoc: LatLon,
    val wheelchairBoarding: Boolean
)

/**
 * The transport mode a departure belongs to, derived from GTFS `route_type`. The schedule DB merges
 * the TfNSW bus and train feeds, so this is how the app tells trains and buses apart.
 */
enum class TransitMode(val label: String) {
    BUS("Bus"),
    TRAIN("Train"),
    OTHER("Other"),
}

/**
 * Maps a GTFS `route_type` to a [TransitMode]. Handles both the standard values (2 = rail, 3 = bus)
 * and TfNSW's extended ranges (4xx = rail, 7xx = bus); anything else (tram/light rail, ferry, …) is
 * [TransitMode.OTHER] until those modes are modelled.
 */
fun transitModeOf(routeType: Int): TransitMode = when (routeType) {
    2, in 400..499 -> TransitMode.TRAIN
    3, in 700..799 -> TransitMode.BUS
    else -> TransitMode.OTHER
}

@Immutable
data class GlobbedBusStopRecord(
    val globbedStopId: String,
    val globbedStopName: String,
    val busStopRecords: List<BusStopRecord>
)

@Immutable
data class BusStopRecord(
    val stopId: String,
    val stopName: String,
    val stopLoc: LatLon,
    val wheelchairBoarding: Boolean
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

/**
 * Read side of the static GTFS schedule, as consumed by [com.example.learning.BusInfo].
 * Kept as a plain interface so tests can swap in a state-based fake (see `src/test`).
 */
interface StaticGtfsSource {
    val isUpToDate: StateFlow<Boolean>

    suspend fun getGlobbedStopById(globbedStopId: String): GlobbedBusStopRecord?
    suspend fun getGlobbedStopsByName(globbedStopName: String): List<GlobbedBusStopRecord>
    suspend fun getNClosestStops(location: LatLon, length: Int): List<GlobbedBusStopRecord>
    suspend fun getStopTimesByStop(globbedBusStopRecord: GlobbedBusStopRecord): List<BusStopTimesRecord>
    suspend fun getStopTimesByTripId(tripId: String, date: LocalDate): List<BusStopTimesRecord>
    suspend fun syncGtfsDatabase(
        ghOwner: String,
        ghRepo: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    )
}

class GtfsStaticRepository(
    private val context: Context,
    private val fileRepository: FileRepository,
    private val httpClient: OkHttpClient
) : StaticGtfsSource {
    private val db get() = GtfsDatabase.getInstance(context)
    private val gtfsDao get() = db.gtfsDao()
    private var _calendarSequences: Map<String, Sequence<LocalDate>>? = null
    private val calMutex = Mutex()
    override val isUpToDate = MutableStateFlow(false)

    private suspend fun calendarSequences(): Map<String, Sequence<LocalDate>> =
        _calendarSequences ?: calMutex.withLock {
            _calendarSequences ?: preloadCalendarDates().also { _calendarSequences = it }
        }

    private fun List<StopWithGlobbedInfo>.collateToGlobbedBusStopRecord(): List<GlobbedBusStopRecord> {
        if (this.isEmpty()) return emptyList()

        return this.groupBy { it.globbedStopId }.map {
            val globbedStopId = it.value.first().globbedStopId
            val globbedStopName = it.value.first().globbedStopName
            val mappedBusStopRecords = it.value.map { record ->
                BusStopRecord(
                    stopId = record.stopId,
                    stopName = record.stopName ?: return emptyList(),
                    stopLoc = LatLon(
                        latitude = record.stopLat ?: return emptyList(),
                        longitude = record.stopLon ?: return emptyList()
                    ),
                    wheelchairBoarding = record.wheelchairBoarding == 1
                )
            }

            return@map GlobbedBusStopRecord(
                globbedStopId = globbedStopId,
                globbedStopName = globbedStopName,
                busStopRecords = mappedBusStopRecords
            )
        }
    }

    private fun StopTimeWithDetails.toBusStopTimesRecord(date: LocalDate): BusStopTimesRecord {
        return BusStopTimesRecord(
            tripId = this.tripId,
            departureTime = parseGtfsDateTime(date, this.departureTime),
            arrivalTime = parseGtfsDateTime(date, this.arrivalTime),
            sequence = this.stopSequence,
            routeId = this.routeId,
            serviceId = this.serviceId,
            tripHeadsign = this.tripHeadsign,
            routeShortName = this.routeShortName,
            routeLongName = this.routeLongName,
            routeType = this.routeType,
            globbedStopId = this.globbedStopId,
            globbedStopName = this.globbedStopName,
            stopId = this.stopId,
            stopName = this.stopName,
            stopLoc = LatLon(this.stopLat, this.stopLon),
            wheelchairBoarding = this.wheelchairBoarding == 1
        )
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

    override suspend fun getGlobbedStopById(globbedStopId: String): GlobbedBusStopRecord? {
        return gtfsDao.getStopsWithGlobbedStops(globbedStopId).collateToGlobbedBusStopRecord().firstOrNull()
    }

    override suspend fun getGlobbedStopsByName(globbedStopName: String): List<GlobbedBusStopRecord> {
        return gtfsDao.getStopsByNameWithGlobbedStops(globbedStopName).collateToGlobbedBusStopRecord()
    }

    override suspend fun getNClosestStops(location: LatLon, length: Int): List<GlobbedBusStopRecord> {
        // TODO: Since there's no globbed level stop lat lon yet the length only works at the non-globbed level. Thus it
        //  is very likely that queries near a train station will get globbed together and you'll get less than the
        //  arg length.
        return gtfsDao
            .getNearestStopsWithGlobbedStops(location.latitude, location.longitude, length)
            .collateToGlobbedBusStopRecord()
    }

    override suspend fun getStopTimesByStop(globbedBusStopRecord: GlobbedBusStopRecord): List<BusStopTimesRecord> {
        return globbedBusStopRecord.busStopRecords
            .flatMap { gtfsDao.getStopTimesWithDetailsByStop(it.stopId) }
            .flatMap { row ->
                calendarSequences().getValue(row.serviceId).map { date ->
                    row.toBusStopTimesRecord( date)
                }
            }
            .sortedBy { it.arrivalTime }
    }

    override suspend fun getStopTimesByTripId(tripId: String, date: LocalDate): List<BusStopTimesRecord> {
        return gtfsDao.getStopTimesWithDetailsByTrip(tripId).map { it.toBusStopTimesRecord(date) }
    }

    /**
     * Check for a newer GTFS database on GitHub Releases and download it if available.
     *
     * Returns `true` if a new DB was downloaded and applied, `false` if already up to date,
     * and throws on network/IO errors.
     */
    override suspend fun syncGtfsDatabase(
        ghOwner: String,
        ghRepo: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
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
