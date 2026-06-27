package com.example.learning

import com.example.learning.repos.GtfsValidation
import com.example.learning.repos.bootstrapGtfs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapGtfsTest {

    @Test
    fun `already valid db skips sync and returns Ok`() = runTest {
        var syncCalled = false
        val result = bootstrapGtfs(
            validate = { GtfsValidation.Ok },
            sync = { syncCalled = true },
        )
        assertEquals(GtfsValidation.Ok, result)
        assertTrue("sync must not be called when DB is already valid", !syncCalled)
    }

    @Test
    fun `invalid db triggers sync then returns Ok when re-validation passes`() = runTest {
        var syncCalled = false
        var validateCallCount = 0
        val result = bootstrapGtfs(
            validate = {
                validateCallCount++
                if (validateCallCount == 1) GtfsValidation.Invalid("empty") else GtfsValidation.Ok
            },
            sync = { syncCalled = true },
        )
        assertEquals(GtfsValidation.Ok, result)
        assertTrue("sync must be called on invalid DB", syncCalled)
        assertEquals("validate must be called exactly twice", 2, validateCallCount)
    }

    @Test
    fun `invalid db that stays invalid after sync returns Invalid`() = runTest {
        var syncCalled = false
        val result = bootstrapGtfs(
            validate = { GtfsValidation.Invalid("still broken") },
            sync = { syncCalled = true },
        )
        assertTrue("result must be Invalid when re-validation fails", result is GtfsValidation.Invalid)
        assertTrue("sync must be called", syncCalled)
    }

    @Test
    fun `sync failure does not crash — re-validation result is returned`() = runTest {
        val result = bootstrapGtfs(
            validate = { GtfsValidation.Invalid("empty") },
            sync = { error("network offline") },
        )
        assertTrue("result must be Invalid when sync throws and re-validation fails", result is GtfsValidation.Invalid)
    }
}
