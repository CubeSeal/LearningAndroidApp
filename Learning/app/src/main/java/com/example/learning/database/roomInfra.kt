package com.example.learning.database

import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(
    tableName = "stop_times",
    // THIS IS THE SECRET SAUCE: Indexing stop_id makes queries take 5ms instead of 5000ms
    indices = [Index(value = ["stopId"])]
)
data class StopTimeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, // Internal DB ID
    val tripId: String,
    val arrivalTime: String,
    val departureTime: String,
    val stopId: String,
)

@Dao
interface StopTimesDao {
    // Determine if we need to import data
    @Query("SELECT COUNT(*) FROM stop_times")
    suspend fun getCount(): Int

    // Batch insert is 100x faster than single inserts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(stopTimes: List<StopTimeEntity>)

    // The fast query
    @Query("""
        SELECT
            stop_times.id
            , stop_times.tripId
            , stop_times.arrivalTime
            , stop_times.departureTime
            , stop_times.stopId
            , trips.routeId
            , trips.serviceId
            , trips.tripHeadsign
            , routes.routeShortName
            , calendar.startDate as calendarStartDate
            , calendar.endDate as calendarEndDate
            , calendar.monday as calendarMonday
            , calendar.tuesday as calendarTuesday
            , calendar.wednesday as calendarWednesday
            , calendar.thursday as calendarThursday
            , calendar.friday as calendarFriday
            , calendar.saturday as calendarSaturday
            , calendar.sunday as calendarSunday
        FROM stop_times
        LEFT JOIN trips ON stop_times.tripId = trips.tripId
        LEFT JOIN routes ON routes.routeId = trips.routeId
        LEFT JOIN calendar ON trips.serviceId = calendar.serviceId
        WHERE stopId = :stopId
        ORDER BY arrivalTime ASC
    """)
    suspend fun getTripsByStopId(stopId: String): List<RawQueryResultScheduledStopTimes>

    @Query("DELETE FROM stop_times")
    suspend fun deleteAll()
}

@Entity(
    tableName = "stops",
    indices = [Index(value = ["id"])]
)
@Immutable
data class BusStopInfoEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val latitude: String,
    val longitude: String
)

@Dao
interface StopsDao {
    @Query("SELECT COUNT(*) FROM stops")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(stopTimes: List<BusStopInfoEntity>)

    @Query(" SELECT * FROM stops")
    suspend fun getAll(): List<BusStopInfoEntity>

    @Query("DELETE FROM stops")
    suspend fun deleteAll()
}

@Entity(
    tableName = "trips",
    indices = [Index(value = ["tripId"])]
)
@Immutable
data class BusTripsEntity(
    val routeId: String,
    val serviceId: String,
    @PrimaryKey
    val tripId: String,
    val shapeId: String,
    val tripHeadsign: String
)

@Dao
interface TripsDao {
    @Query("SELECT COUNT(*) FROM trips")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(trips: List<BusTripsEntity>)

    @Query("SELECT * FROM trips")
    suspend fun getAll(): List<BusTripsEntity>

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}

@Entity(
    tableName = "routes",
    indices = [Index(value = ["routeId"])]
)
@Immutable
data class BusRoutesEntity(
    @PrimaryKey
    val routeId: String,
    val agencyId: String,
    val routeShortName: String,
)

@Dao
interface RoutesDao {
    @Query("SELECT COUNT(*) FROM routes")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(routes: List<BusRoutesEntity>)

    @Query("SELECT * FROM routes")
    suspend fun getAll(): List<BusRoutesEntity>

    @Query("DELETE FROM routes")
    suspend fun deleteAll()
}

// Probably don't need all this infrastructure for like a 200 line file, but it's also probably
// better to do all the file parsing the same way.
@Entity(
    tableName = "calendar",
    indices = [Index(value = ["serviceId"])]
)
@Immutable
data class BusCalendarEntity(
    @PrimaryKey
    val serviceId: String,
    val monday: String,
    val tuesday: String,
    val wednesday: String,
    val thursday: String,
    val friday: String,
    val saturday: String,
    val sunday: String,
    val startDate: String,
    val endDate: String,
)

@Dao
interface CalendarDao {
    @Query("SELECT COUNT(*) FROM routes")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(calendarEntities: List<BusCalendarEntity>)

    @Query("SELECT * FROM routes")
    suspend fun getAll(): List<BusRoutesEntity>

    @Query("DELETE FROM routes")
    suspend fun deleteAll()
}

@Database(entities = [
    StopTimeEntity::class,
    BusStopInfoEntity::class,
    BusTripsEntity::class,
    BusRoutesEntity::class,
    BusCalendarEntity::class,
], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stopTimesDao(): StopTimesDao
    abstract fun stopsDao(): StopsDao
    abstract fun tripsDao(): TripsDao
    abstract fun routesDao(): RoutesDao
    abstract fun calendarDao(): CalendarDao
}
