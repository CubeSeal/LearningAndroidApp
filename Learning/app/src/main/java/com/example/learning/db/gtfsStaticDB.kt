package com.example.learning.db

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

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

    @Query("""
    SELECT *, 
        ((stop_lat - :userLat) * (stop_lat - :userLat) + 
         (stop_lon - :userLon) * (stop_lon - :userLon)) AS distance_sq
    FROM stops
    ORDER BY distance_sq ASC
    LIMIT 10
""")
    suspend fun getNearestStops(userLat: Double, userLon: Double): List<StopEntity>

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

    @Query("SELECT * FROM calendar")
    suspend fun getAllCalendar(): List<CalendarEntity>

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
internal abstract class GtfsDatabase : RoomDatabase() {
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
        suspend fun replaceWith(context: Context, newDbFile: File) {
            val old: GtfsDatabase?
            synchronized(this) {
                // Stage the new file BEFORE touching the live instance —
                // if copy fails, the old DB stays up.
                val target = File(context.filesDir, "gtfs_downloaded.db")
                newDbFile.copyTo(target, overwrite = true)

                old = instance
                instance = null
                context.deleteDatabase(DB_NAME)
                // Next getInstance() rebuilds from the new gtfs_downloaded.db.
            }
            // Let in-flight suspend queries finish before pulling the rug.
            old?.let {
                delay(500)
                withContext(Dispatchers.IO) { it.close() }
            }
        }
    }
}
