package com.example.learning

import com.example.learning.repos.GtfsValidation
import com.example.learning.repos.bootstrapGtfs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Behaviour of [bootstrapGtfs] — the startup "provision then validate" decision that
 * [com.example.learning.ApplicationRepos.initAll] branches on. Tested at its own API (like
 * [GtfsValidationTest]) because it *is* a consumer boundary: the outcome decides whether the app
 * enters Home or shows the error screen.
 *
 * Both seams are plain lambdas, so this is a pure JVM test — no Android, no Robolectric, no
 * network. `validate` is scripted to return a sequence of outcomes (mimicking the DB before and
 * after a sync); `sync` just counts its invocations.
 */
class BootstrapGtfsTest {

    @Test
    fun `a valid DB needs no sync`() = runTest {
        var syncs = 0
        val result = bootstrapGtfs(
            validate = { GtfsValidation.Ok },
            sync = { syncs++ },
        )
        assertSame(GtfsValidation.Ok, result)
        assertEquals("sync must not run when the DB is already valid", 0, syncs)
    }

    @Test
    fun `an invalid DB is healed by a single sync`() = runTest {
        var syncs = 0
        // Invalid before the sync (fresh install: no service_dates), Ok after it downloads a DB.
        val outcomes = ArrayDeque(listOf(GtfsValidation.Invalid("missing"), GtfsValidation.Ok))
        val result = bootstrapGtfs(
            validate = { outcomes.removeFirst() },
            sync = { syncs++ },
        )
        assertSame(GtfsValidation.Ok, result)
        assertEquals("sync must run exactly once to provision the DB", 1, syncs)
    }

    @Test
    fun `a DB still invalid after a sync halts gracefully`() = runTest {
        var syncs = 0
        val result = bootstrapGtfs(
            validate = { GtfsValidation.Invalid("service_dates table missing") },
            sync = { syncs++ },
        )
        assertEquals(GtfsValidation.Invalid("service_dates table missing"), result)
        assertEquals("sync is attempted once, not retried in a loop", 1, syncs)
    }

    @Test
    fun `a sync failure folds into the re-validate instead of crashing`() = runTest {
        // Offline first launch: the download throws. bootstrap must swallow it and report the
        // (still invalid) DB rather than propagating the exception.
        val result = bootstrapGtfs(
            validate = { GtfsValidation.Invalid("no network") },
            sync = { throw java.io.IOException("offline") },
        )
        assertEquals(GtfsValidation.Invalid("no network"), result)
    }
}
