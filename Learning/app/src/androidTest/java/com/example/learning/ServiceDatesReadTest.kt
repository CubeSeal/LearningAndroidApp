package com.example.learning

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.learning.db.GtfsDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the Wynyard train crash. The crash was a `NoSuchElementException` from
 * the app runtime-expanding the `calendar` table and calling `Map.getValue(serviceId)` for a
 * service that has no calendar row (calendar_dates-only — common for Sydney Trains). The fix has
 * the app read the pre-materialised `service_dates` table instead.
 */
@RunWith(AndroidJUnit4::class)
class ServiceDatesReadTest {

    private fun buildDb(): GtfsDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GtfsDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun serviceDates_isReadableForServiceWithNoCalendarRow() = runTest {
        val db = buildDb()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO service_dates (service_id, date) VALUES ('SPECIAL', '20260627')")
        sql.execSQL("INSERT INTO service_dates (service_id, date) VALUES ('SPECIAL', '20260628')")

        sql.execSQL("INSERT INTO agency (agency_id, agency_name, agency_url, agency_timezone) VALUES ('A1', 'Trains', 'http://a.com', 'Australia/Sydney')")
        sql.execSQL("INSERT INTO routes (route_id, agency_id, route_short_name, route_long_name, route_desc, route_type) VALUES ('T1', 'A1', 'T1', 'North Shore Line', '', 2)")
        sql.execSQL("INSERT INTO trips (trip_id, route_id, service_id, trip_headsign) VALUES ('TRIP1', 'T1', 'SPECIAL', 'City')")
        sql.execSQL("INSERT INTO stops (stop_id, stop_name, stop_lat, stop_lon) VALUES ('WYN1', 'Wynyard Station, Platform 3', -33.86, 151.20)")
        sql.execSQL("INSERT INTO stop_times (trip_id, stop_id, stop_sequence, departure_time, arrival_time) VALUES ('TRIP1', 'WYN1', 1, '09:00:00', '09:00:00')")

        val dao = db.gtfsDao()

        val serviceDates = dao.getAllServiceDates()
            .filter { it.serviceId == "SPECIAL" }
            .map { it.date }
            .sorted()
        assertEquals(listOf("20260627", "20260628"), serviceDates)

        val stopTimes = dao.getStopTimesWithDetailsByStop("WYN1")
        assertEquals(1, stopTimes.size)
        assertEquals("SPECIAL", stopTimes.first().serviceId)
        assertTrue(dao.getAllServiceDates().any { it.serviceId == "SPECIAL" })

        db.close()
    }
}
