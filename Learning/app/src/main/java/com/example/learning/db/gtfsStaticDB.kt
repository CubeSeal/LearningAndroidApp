package com.example.learning.db

import android.content.Context
import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.learning.repos.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream

// ═════════════════════════════════════════════════════════════════════
// Entities
// ═════════════════════════════════════════════════════════════════════

@Entity(tableName = "agency")
data class AgencyEntity(
    @PrimaryKey @ColumnInfo(name = "agency_id") val agencyId: String,
    @ColumnInfo(name = "agency_name") val agencyName: String,
    @ColumnInfo(name = "agency_url") val agencyUrl: String,
    @ColumnInfo(name = "agency_timezone") val agencyTimezone: String,
    @ColumnInfo(name = "agency_lang") val agencyLang: String?,
    @ColumnInfo(name = "agency_phone") val agencyPhone: String?,
)

@Entity(
    tableName = "routes",
    indices = [Index("agency_id")],
    foreignKeys = [ForeignKey(entity = AgencyEntity::class, parentColumns = ["agency_id"], childColumns = ["agency_id"])],
)
data class RouteEntity(
    @PrimaryKey @ColumnInfo(name = "route_id") val routeId: String,
    @ColumnInfo(name = "agency_id") val agencyId: String?,
    @ColumnInfo(name = "route_short_name") val routeShortName: String?,
    @ColumnInfo(name = "route_long_name") val routeLongName: String?,
    @ColumnInfo(name = "route_desc") val routeDesc: String?,
    @ColumnInfo(name = "route_type") val routeType: Int,
    @ColumnInfo(name = "route_url") val routeUrl: String?,
    @ColumnInfo(name = "route_color") val routeColor: String?,
    @ColumnInfo(name = "route_text_color") val routeTextColor: String?,
)

@Entity(tableName = "calendar")
data class CalendarEntity(
    @PrimaryKey @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "monday") val monday: Int,
    @ColumnInfo(name = "tuesday") val tuesday: Int,
    @ColumnInfo(name = "wednesday") val wednesday: Int,
    @ColumnInfo(name = "thursday") val thursday: Int,
    @ColumnInfo(name = "friday") val friday: Int,
    @ColumnInfo(name = "saturday") val saturday: Int,
    @ColumnInfo(name = "sunday") val sunday: Int,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String,
)

@Entity(tableName = "calendar_dates", primaryKeys = ["service_id", "date"])
data class CalendarDateEntity(
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "exception_type") val exceptionType: Int,
)

@Entity(
    tableName = "trips",
    indices = [Index("route_id"), Index("service_id")],
    foreignKeys = [ForeignKey(entity = RouteEntity::class, parentColumns = ["route_id"], childColumns = ["route_id"])],
)
data class TripEntity(
    @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "route_id") val routeId: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "trip_headsign") val tripHeadsign: String?,
    @ColumnInfo(name = "trip_short_name") val tripShortName: String?,
    @ColumnInfo(name = "direction_id") val directionId: Int?,
    @ColumnInfo(name = "block_id") val blockId: String?,
    @ColumnInfo(name = "shape_id") val shapeId: String?,
    @ColumnInfo(name = "wheelchair_accessible") val wheelchairAccessible: Int?,
)

@Entity(tableName = "stops", indices = [Index("parent_station")])
data class StopEntity(
    @PrimaryKey @ColumnInfo(name = "stop_id") val stopId: String,
    @ColumnInfo(name = "stop_code") val stopCode: String?,
    @ColumnInfo(name = "stop_name") val stopName: String?,
    @ColumnInfo(name = "stop_desc") val stopDesc: String?,
    @ColumnInfo(name = "stop_lat") val stopLat: Double?,
    @ColumnInfo(name = "stop_lon") val stopLon: Double?,
    @ColumnInfo(name = "zone_id") val zoneId: String?,
    @ColumnInfo(name = "stop_url") val stopUrl: String?,
    @ColumnInfo(name = "location_type") val locationType: Int?,
    @ColumnInfo(name = "parent_station") val parentStation: String?,
    @ColumnInfo(name = "stop_timezone") val stopTimezone: String?,
    @ColumnInfo(name = "wheelchair_boarding") val wheelchairBoarding: Int?,
    @ColumnInfo(name = "platform_code") val platformCode: String?,
)

@Entity(
    tableName = "stop_times",
    primaryKeys = ["trip_id", "stop_sequence"],
    indices = [Index("stop_id"), Index("trip_id"), Index("departure_time")],
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["trip_id"], childColumns = ["trip_id"]),
        ForeignKey(entity = StopEntity::class, parentColumns = ["stop_id"], childColumns = ["stop_id"]),
    ],
)
data class StopTimeEntity(
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "arrival_time") val arrivalTime: String?,
    @ColumnInfo(name = "departure_time") val departureTime: String?,
    @ColumnInfo(name = "stop_id") val stopId: String,
    @ColumnInfo(name = "stop_sequence") val stopSequence: Int,
    @ColumnInfo(name = "stop_headsign") val stopHeadsign: String?,
    @ColumnInfo(name = "pickup_type") val pickupType: Int?,
    @ColumnInfo(name = "drop_off_type") val dropOffType: Int?,
    @ColumnInfo(name = "shape_dist_traveled") val shapeDistTraveled: Double?,
    @ColumnInfo(name = "timepoint") val timepoint: Int?,
)

// ═════════════════════════════════════════════════════════════════════
// DAO
// ═════════════════════════════════════════════════════════════════════

@Dao
interface GtfsDao {

    // ── Stops ──────────────────────────────────────────────

    @Query("SELECT * FROM stops WHERE stop_id = :stopId")
    suspend fun getStop(stopId: String): StopEntity?

    @Query("SELECT * FROM stops WHERE stop_name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchStops(query: String, limit: Int = 30): List<StopEntity>

    @Query("""
        SELECT * FROM stops
        WHERE stop_lat BETWEEN :minLat AND :maxLat
          AND stop_lon BETWEEN :minLon AND :maxLon
          AND (location_type IS NULL OR location_type = 0)
    """)
    suspend fun getStopsInBounds(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
    ): List<StopEntity>

    @Query("SELECT * FROM stops WHERE parent_station = :parentId")
    suspend fun getChildStops(parentId: String): List<StopEntity>

    @Query("SELECT * FROM stops")
    suspend fun getAllStops(): List<StopEntity>

    // ── Departures ─────────────────────────────────────────

    @Query("""
        SELECT st.* FROM stop_times st
        INNER JOIN trips t ON st.trip_id = t.trip_id
        WHERE st.stop_id = :stopId
          AND st.departure_time >= :afterTime
          AND st.departure_time < :beforeTime
        ORDER BY st.departure_time ASC
        LIMIT :limit
    """)
    suspend fun getDepartures(
        stopId: String, afterTime: String,
        beforeTime: String = "28:00:00", limit: Int = 50,
    ): List<StopTimeEntity>

    // ── Routes ─────────────────────────────────────────────

    @Query("SELECT * FROM routes WHERE route_id = :routeId")
    suspend fun getRoute(routeId: String): RouteEntity?

    @Query("""
        SELECT DISTINCT r.* FROM routes r
        INNER JOIN trips t ON r.route_id = t.route_id
        INNER JOIN stop_times st ON t.trip_id = st.trip_id
        WHERE st.stop_id = :stopId
    """)
    suspend fun getRoutesForStop(stopId: String): List<RouteEntity>

    // ── Trips ──────────────────────────────────────────────

    @Query("SELECT * FROM trips WHERE trip_id = :tripId")
    suspend fun getTrip(tripId: String): TripEntity?

    @Query("SELECT * FROM stop_times WHERE trip_id = :tripId ORDER BY stop_sequence ASC")
    suspend fun getStopTimesForTrip(tripId: String): List<StopTimeEntity>

    @Query("SELECT * FROM stop_times WHERE stop_id = :stopId ORDER BY stop_sequence ASC")
    suspend fun getStopTimesForStop(stopId: String): List<StopTimeEntity>

    // ── Calendar ───────────────────────────────────────────

    @Query("SELECT * FROM calendar WHERE service_id = :serviceId")
    suspend fun getCalendar(serviceId: String): CalendarEntity?

    @Query("SELECT * FROM calendar_dates WHERE service_id = :serviceId")
    suspend fun getCalendarDates(serviceId: String): List<CalendarDateEntity>

    // ── Metadata ───────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun countStops(): Int

    @Query("SELECT COUNT(*) FROM stop_times")
    suspend fun countStopTimes(): Int
}

// ═════════════════════════════════════════════════════════════════════
// Database
// ═════════════════════════════════════════════════════════════════════

@Database(
    entities = [
        AgencyEntity::class, RouteEntity::class, CalendarEntity::class,
        CalendarDateEntity::class, TripEntity::class, StopEntity::class,
        StopTimeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GtfsDatabase : RoomDatabase() {
    abstract fun gtfsDao(): GtfsDao

    companion object {
        private const val DB_NAME = "gtfs.db"

        @Volatile private var instance: GtfsDatabase? = null

        fun getInstance(context: Context): GtfsDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): GtfsDatabase {
            val downloaded = File(context.filesDir, "gtfs_downloaded.db")
            val builder = if (downloaded.exists()) {
                Room.databaseBuilder(context, GtfsDatabase::class.java, DB_NAME)
                    .createFromFile(downloaded)
            } else {
                Room.databaseBuilder(context, GtfsDatabase::class.java, DB_NAME)
            }
            return builder.fallbackToDestructiveMigration().build()
        }

        /** Close current instance and swap in a new DB file. */
        fun replaceWith(context: Context, newDbFile: File) {
            synchronized(this) {
                instance?.close()
                instance = null
                val target = File(context.filesDir, "gtfs_downloaded.db")
                newDbFile.copyTo(target, overwrite = true)
                context.deleteDatabase(DB_NAME)
            }
        }
    }
}


/**
 * Check for a newer GTFS database on GitHub Releases and download it if available.
 *
 * Returns `true` if a new DB was downloaded and applied, `false` if already up to date,
 * and throws on network/IO errors.
 */
suspend fun syncGtfsDatabase(
    fileRepository: FileRepository,
    httpClient: OkHttpClient,
    ghOwner: String,
    ghRepo: String,
    onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
): Boolean = withContext(Dispatchers.IO) {
    val TAG = "GtfsSync"
    val TIMESTAMP_FILE = "timestamp"
    val json = Json { ignoreUnknownKeys = true }
    val localTimestamp = fileRepository.readFile(TIMESTAMP_FILE)?.let {
        try { Instant.parse(it) } catch (e: Exception) {
            Log.w(TAG, "Could not parse saved timestamp, treating as stale", e)
            null
        }
    }

    Log.d(TAG, "Checking for GTFS update (local timestamp: $localTimestamp)")

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
            Log.d(TAG, "Up to date ($localTimestamp >= $publishedAt)")
            return@withContext false
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

    Log.d(TAG, "New version available (published: $remoteTimestamp), downloading...")

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
        GtfsDatabase.replaceWith(fileRepository.context, dbFile)
        fileRepository.deleteFile(tempDbName)

        // ── Write timestamp only after everything succeeded ──────
        fileRepository.writeFile(TIMESTAMP_FILE, remoteTimestamp.toString())
        Log.i(TAG, "GTFS DB updated (published: $remoteTimestamp)")
        true
    } catch (e: Exception) {
        fileRepository.deleteFile(tempGzName)
        fileRepository.deleteFile(tempDbName)
        throw e
    }
}
