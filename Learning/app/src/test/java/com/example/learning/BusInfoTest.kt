package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedBusStopRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour of [BusInfo] driven entirely through state-based fakes. We assert on what the public
 * `savedStops` flow emits as settings change — never on which repository methods were called.
 *
 * `runTest` gives virtual time; `backgroundScope` hosts BusInfo's `SharingStarted.Eagerly`
 * collectors and is auto-cancelled when the test ends. Turbine asserts each emission in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusInfoTest {

    private val central = GlobbedBusStopRecord(
        globbedStopId = "central_station",
        globbedStopName = "Central Station",
        busStopRecords = emptyList(),
    )

    @Test
    fun `savedStops emits the resolved globbed record after a stop is saved`() = runTest {
        val settings = FakeSettingsSource()
        val static = FakeStaticGtfsSource(stopsById = mapOf("central_station" to central))

        val busInfo = BusInfo(
            gtfsStaticRepository = static,
            gtfsRealtimeRepository = FakeRealtimeSource(),
            locationRepo = FakeLocationSource(),
            settingsRepo = settings,
            scope = backgroundScope,
        )

        busInfo.savedStops.test {
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())   // nothing saved yet
            settings.addSavedStop("central_station")
            assertEquals(listOf(central), awaitItem())                     // id resolved to its record
            cancelAndIgnoreRemainingEvents()
        }
    }
}
