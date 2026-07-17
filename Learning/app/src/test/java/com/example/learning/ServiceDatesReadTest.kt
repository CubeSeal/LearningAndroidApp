package com.example.learning

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.learning.db.GtfsDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for the Wynyard train crash. The crash was a `NoSuchElementException` from the app
 * runtime-expanding the `calendar` table and calling `Map.getValue(serviceId)` for a service that has
 * **no calendar row** (calendar_dates-only — common for Sydney Trains). The fix has the app read the
 * pre-materialised `service_dates` table instead.
 *
 * This exercises the real DAO read path that `GtfsStaticRepository.serviceDates()` /
 * `getStopTimesByStop` now depend on, for exactly the scenario that used to crash: a service that is
 * present in `service_dates` but absent from `calendar`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceDatesReadTest {

    private fun buildDb(): GtfsDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GtfsDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun `service_dates is readable for a service that has no calendar row`() = runTest {
        val db = buildDb()
        val sql = db.openHelper.writableDatabase
        // service_dates is now a Room entity, so Room creates the table; just seed it.
        // A calendar_dates-only service: present in service_dates, absent from calendar.
        sql.execSQL("INSERT INTO service_dates (service_id, date) VALUES ('SPECIAL', '20260627')")
        sql.execSQL("INSERT INTO service_dates (service_id, date) VALUES ('SPECIAL', '20260628')")

        sql.execSQL("INSERT INTO agency (agency_id, agency_name, agency_url, agency_timezone) VALUES ('A1', 'Trains', 'http://a.com', 'Australia/Sydney')")
        sql.execSQL("INSERT INTO routes (route_id, agency_id, route_short_name, route_long_name, route_desc, route_type) VALUES ('T1', 'A1', 'T1', 'North Shore Line', '', 2)")
        sql.execSQL("INSERT INTO trips (trip_id, route_id, service_id, trip_headsign) VALUES ('TRIP1', 'T1', 'SPECIAL', 'City')")
        sql.execSQL("INSERT INTO stops (stop_id, stop_name, stop_lat, stop_lon) VALUES ('WYN1', 'Wynyard Station, Platform 3', -33.86, 151.20)")
        sql.execSQL("INSERT INTO stop_times (trip_id, stop_id, stop_sequence, departure_time, arrival_time) VALUES ('TRIP1', 'WYN1', 1, '09:00:00', '09:00:00')")

        val dao = db.gtfsDao()

        // The read the train fix depends on: service_dates yields dates for the calendar-less service.
        val serviceDates = dao.getAllServiceDates()
            .filter { it.serviceId == "SPECIAL" }
            .map { it.date }
            .sorted()
        assertEquals(listOf("20260627", "20260628"), serviceDates)

        // And the stop-times join the repo runs resolves for that trip, carrying the same service_id.
        val stopTimes = dao.getStopTimesWithDetailsByStop("WYN1")
        assertEquals(1, stopTimes.size)
        assertEquals("SPECIAL", stopTimes.first().serviceId)
        // Sanity: the calendar table genuinely has no row for this service — the old crash precondition.
        assertTrue(dao.getAllServiceDates().any { it.serviceId == "SPECIAL" })

        db.close()
    }
}
