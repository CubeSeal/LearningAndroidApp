package com.example.learning

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.learning.db.GtfsDatabase
import com.example.learning.repos.GtfsValidation
import com.example.learning.repos.validateGtfsDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GtfsValidationTest {

    private fun buildDb(): GtfsDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GtfsDatabase::class.java
    ).allowMainThreadQueries().build()

    private fun populateRequiredTables(db: GtfsDatabase) {
        val sql = db.openHelper.writableDatabase
        // service_dates is a Room entity now, so Room creates the table; just seed the rows.
        // Insert one row into each table validateGtfsDb checks: stops, routes, stop_times, service_dates, globbed_stops.
        sql.execSQL("INSERT INTO agency (agency_id, agency_name, agency_url, agency_timezone) VALUES ('A1', 'Agency', 'http://a.com', 'Australia/Sydney')")
        sql.execSQL("INSERT INTO routes (route_id, agency_id, route_short_name, route_long_name, route_desc, route_type) VALUES ('R1', 'A1', '1', 'Route', '', 3)")
        sql.execSQL("INSERT INTO stops (stop_id, stop_name, stop_lat, stop_lon) VALUES ('S1', 'Stop 1', -33.87, 151.21)")
        sql.execSQL("INSERT INTO trips (trip_id, route_id, service_id) VALUES ('T1', 'R1', 'SVC1')")
        sql.execSQL("INSERT INTO stop_times (trip_id, stop_id, stop_sequence, departure_time, arrival_time) VALUES ('T1', 'S1', 1, '09:00:00', '09:00:00')")
        sql.execSQL("INSERT INTO globbed_stops (globbed_stop_id, globbed_stop_name, stop_id) VALUES ('stop_1', 'Stop 1', 'S1')")
        sql.execSQL("INSERT INTO service_dates (service_id, date) VALUES ('SVC1', '20260627')")
    }

    @Test
    fun `returns Ok when all required tables are present and non-empty`() {
        val db = buildDb()
        populateRequiredTables(db)
        assertEquals(GtfsValidation.Ok, validateGtfsDb(db))
        db.close()
    }

    @Test
    fun `returns Invalid when service_dates table is missing`() {
        val db = buildDb()
        // Room creates service_dates (it's an entity now); drop it to exercise the existence check
        // that guards against a DB from an old converter build predating service_dates.
        db.openHelper.writableDatabase.execSQL("DROP TABLE service_dates")
        val result = validateGtfsDb(db)
        assertTrue(result is GtfsValidation.Invalid)
        assertEquals(
            "service_dates table missing — DB is from an old converter build",
            (result as GtfsValidation.Invalid).reason,
        )
        db.close()
    }

    @Test
    fun `returns Invalid when a required table is empty`() {
        val db = buildDb()
        // Room creates all entity tables (incl. service_dates) but leaves them empty.
        val result = validateGtfsDb(db)
        assertTrue("must be Invalid when tables are empty", result is GtfsValidation.Invalid)
        db.close()
    }
}
