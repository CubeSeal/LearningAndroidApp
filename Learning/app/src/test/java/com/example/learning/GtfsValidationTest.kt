package com.example.learning

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

/**
 * Behaviour of [validateGtfsDb] — the startup contract check on the opened DB.
 *
 * We use Robolectric's in-memory Room builder so tests run in the JVM inner loop with no real DB
 * file. Each test seeds only the invariant it wants to exercise, so failures are specific.
 *
 * The validator is tested at its own public API ([GtfsValidation]) because it *is* the boundary:
 * consumers (MainActivity) branch on Ok vs Invalid to decide whether to show the app or an error
 * screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GtfsValidationTest {

    private fun buildDb(): GtfsDatabase =
        androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GtfsDatabase::class.java,
        ).allowMainThreadQueries().build()

    @Test
    fun `a freshly opened empty DB is Invalid (service_dates absent)`() {
        val db = buildDb()
        val result = validateGtfsDb(db)
        assertTrue("Expected Invalid but got $result", result is GtfsValidation.Invalid)
    }
}
