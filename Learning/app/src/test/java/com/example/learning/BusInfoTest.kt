package com.example.learning

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.example.learning.repos.BusStopRecord
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedBusStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.LocationSource
import com.example.learning.repos.RealtimeBusTripInfo
import com.example.learning.repos.RealtimeGtfsSource
import com.example.learning.repos.SettingsSource
import com.example.learning.repos.StaticGtfsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Behaviour of [BusInfo] driven entirely through state-based fakes. We assert on what the public
 * flows emit as location/settings change — never on which repository methods were called.
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

    private val townHall = GlobbedBusStopRecord(
        globbedStopId = "town_hall",
        globbedStopName = "Town Hall",
        busStopRecords = emptyList(),
    )

    private val sydney = LatLon(-33.8688, 151.2093)

    private val baseTime: LocalDateTime = LocalDateTime.of(2026, 6, 1, 12, 0)

    /** Builds a flat schedule record; every field has a default so a test sets only what it asserts. */
    private fun stopTime(
        tripId: String = "t1",
        stopId: String = "stop1",
        routeShortName: String = "370",
        tripHeadsign: String = "City",
        stopName: String = "Stand A",
        departure: LocalDateTime = baseTime,
        sequence: Int = 1,
        globbedStopId: String = "globbed1",
    ) = BusStopTimesRecord(
        tripId = tripId,
        departureTime = departure,
        arrivalTime = departure,
        sequence = sequence,
        routeId = "route_$routeShortName",
        serviceId = "svc1",
        tripHeadsign = tripHeadsign,
        routeShortName = routeShortName,
        routeLongName = "$routeShortName Long",
        globbedStopId = globbedStopId,
        globbedStopName = "Globbed Stop",
        stopId = stopId,
        stopName = stopName,
        stopLoc = sydney,
        wheelchairBoarding = false,
    )

    /**
     * Builds [BusInfo] on the test's [backgroundScope] (which hosts the `SharingStarted.Eagerly`
     * collectors and is auto-cancelled at test end). Each seam defaults to an empty fake; a test
     * passes only the ones it cares about.
     */
    private fun TestScope.busInfo(
        static: StaticGtfsSource = FakeStaticGtfsSource(),
        realtime: RealtimeGtfsSource = FakeRealtimeSource(),
        location: LocationSource = FakeLocationSource(),
        settings: SettingsSource = FakeSettingsSource(),
    ) = BusInfo(
        gtfsStaticRepository = static,
        gtfsRealtimeRepository = realtime,
        locationRepo = location,
        settingsRepo = settings,
        scope = backgroundScope,
    )

    /**
     * `associatedStopTimes` is a `stateIn` flow, so it starts at `emptyList` and conflates the
     * intermediate "trips resolved but realtime not joined yet" state. Skipping leading empties
     * lands on the settled list deterministically.
     */
    private suspend fun ReceiveTurbine<List<BusStopTimesRecordWithRealtime>>.awaitDepartures(): List<BusStopTimesRecordWithRealtime> {
        while (true) {
            val item = awaitItem()
            if (item.isNotEmpty()) return item
        }
    }

    @Test
    fun `closestBusStops is empty while the device location is unknown`() = runTest {
        // Stops exist in the schedule, but with no location fix there's nothing to anchor "nearest"
        // to — so the list stays empty regardless of what the static source could return.
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(closest = listOf(central)),
            location = FakeLocationSource(location = null),
        )

        busInfo.closestBusStops.test {
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closestBusStops emits the nearest stops once a location is available`() = runTest {
        val location = FakeLocationSource(location = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(closest = listOf(central, townHall)),
            location = location,
        )

        busInfo.closestBusStops.test {
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())   // no fix yet
            location.emit(sydney)
            assertEquals(listOf(central, townHall), awaitItem())           // fix arrives → nearest stops
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closestBusStops clears when the location becomes unavailable again`() = runTest {
        val location = FakeLocationSource(location = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(closest = listOf(central)),
            location = location,
        )

        busInfo.closestBusStops.test {
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())
            location.emit(sydney)
            assertEquals(listOf(central), awaitItem())
            location.emit(null)                                            // fix lost
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `savedStops emits the resolved globbed record after a stop is saved`() = runTest {
        val settings = FakeSettingsSource()
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(stopsById = mapOf("central_station" to central)),
            settings = settings,
        )

        busInfo.savedStops.test {
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())   // nothing saved yet
            settings.addSavedStop("central_station")
            assertEquals(listOf(central), awaitItem())                     // id resolved to its record
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `focusedBusStop is null until a home stop is set`() = runTest {
        val busInfo = busInfo(settings = FakeSettingsSource(homeStopId = null))

        busInfo.focusedBusStop.test {
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setting the focused stop round-trips through settings into focusedBusStop`() = runTest {
        // The home stop in settings is the source of truth: updating it is what drives focusedBusStop.
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(stopsById = mapOf("central_station" to central)),
            settings = FakeSettingsSource(homeStopId = null),
        )

        busInfo.focusedBusStop.test {
            assertEquals(null, awaitItem())
            busInfo.updateFocusedBusStop(central)
            assertEquals(central, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a home stop id that no longer resolves yields a null focused stop`() = runTest {
        // Schedule data is regenerated daily; a saved home id can disappear. The focused stop should
        // fall back to null rather than getting stuck on the stale record.
        val settings = FakeSettingsSource(homeStopId = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(stopsById = mapOf("central_station" to central)),
            settings = settings,
        )

        busInfo.focusedBusStop.test {
            assertEquals(null, awaitItem())
            settings.setHomeStopId("central_station")
            assertEquals(central, awaitItem())
            settings.setHomeStopId("ghost_stop")               // no longer in the schedule
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `associatedTrips reflects the focused stop's schedule`() = runTest {
        val trips = listOf(
            stopTime(tripId = "t1", routeShortName = "370"),
            stopTime(tripId = "t2", routeShortName = "412"),
        )
        val settings = FakeSettingsSource(homeStopId = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("central_station" to central),
                stopTimesByStop = mapOf("central_station" to trips),
            ),
            settings = settings,
        )

        busInfo.associatedTrips.test {
            assertEquals(emptyList<BusStopTimesRecord>(), awaitItem())   // nothing focused yet
            settings.setHomeStopId("central_station")
            assertEquals(trips, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filtersForBusStop offers a route and headsign chip per distinct service`() = runTest {
        val trips = listOf(
            stopTime(tripId = "t1", routeShortName = "370", tripHeadsign = "City"),
            stopTime(tripId = "t2", routeShortName = "412", tripHeadsign = "Uni"),
        )
        val settings = FakeSettingsSource(homeStopId = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("central_station" to central),     // no platforms → no stand chips
                stopTimesByStop = mapOf("central_station" to trips),
            ),
            settings = settings,
        )

        busInfo.filtersForBusStop.test {
            assertEquals(emptySet<BusFilterOptions>(), awaitItem())
            settings.setHomeStopId("central_station")
            assertEquals(
                setOf(
                    BusFilterOptions.RouteShortName("370"),
                    BusFilterOptions.TripHeadsign("City"),
                    BusFilterOptions.RouteShortName("412"),
                    BusFilterOptions.TripHeadsign("Uni"),
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `platform filters are absent when a globbed stop has two or fewer platforms`() = runTest {
        val twoPlatform = GlobbedBusStopRecord(
            globbedStopId = "twoplat",
            globbedStopName = "Two Platform",
            busStopRecords = listOf(
                BusStopRecord("p1", "Stand 1", sydney, false),
                BusStopRecord("p2", "Stand 2", sydney, false),
            ),
        )
        val settings = FakeSettingsSource(homeStopId = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("twoplat" to twoPlatform),
                stopTimesByStop = mapOf("twoplat" to listOf(stopTime(stopId = "p1"))),
            ),
            settings = settings,
        )

        busInfo.filtersForBusStop.test {
            assertEquals(emptySet<BusFilterOptions>(), awaitItem())
            settings.setHomeStopId("twoplat")
            assertEquals(
                setOf(BusFilterOptions.RouteShortName("370"), BusFilterOptions.TripHeadsign("City")),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `platform filters appear when a globbed stop has more than two platforms`() = runTest {
        val threePlatform = GlobbedBusStopRecord(
            globbedStopId = "threeplat",
            globbedStopName = "Three Platform",
            busStopRecords = listOf(
                BusStopRecord("p1", "Stand 1", sydney, false),
                BusStopRecord("p2", "Stand 2", sydney, false),
                BusStopRecord("p3", "Stand 3", sydney, false),
            ),
        )
        val settings = FakeSettingsSource(homeStopId = null)
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("threeplat" to threePlatform),
                // Trip departs from platform p1, so its stand (Stand 1) becomes a filter.
                stopTimesByStop = mapOf("threeplat" to listOf(stopTime(stopId = "p1"))),
            ),
            settings = settings,
        )

        busInfo.filtersForBusStop.test {
            assertEquals(emptySet<BusFilterOptions>(), awaitItem())
            settings.setHomeStopId("threeplat")
            assertEquals(
                setOf(
                    BusFilterOptions.RouteShortName("370"),
                    BusFilterOptions.TripHeadsign("City"),
                    BusFilterOptions.StopStand("Stand 1"),
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a scheduled departure with no matching realtime entry has null realtime info`() = runTest {
        // null realtime is a valid state — "no live data for this departure yet" — not an error.
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("central_station" to central),
                stopTimesByStop = mapOf("central_station" to listOf(stopTime(tripId = "t1", stopId = "s1"))),
            ),
            realtime = FakeRealtimeSource(),   // empty live feed
            settings = FakeSettingsSource(homeStopId = "central_station"),
        )

        busInfo.associatedStopTimes.test {
            val departures = awaitDepartures()
            assertEquals(1, departures.size)
            assertEquals(null, departures.single().realtimeBusStopTimesRecord)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a realtime delay is joined onto the matching trip and stop`() = runTest {
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("central_station" to central),
                stopTimesByStop = mapOf("central_station" to listOf(stopTime(tripId = "t1", stopId = "s1"))),
            ),
            realtime = FakeRealtimeSource(
                busData = listOf(
                    RealtimeBusTripInfo(
                        id = "e1",
                        tripId = "t1",
                        updatedAt = baseTime,
                        stopTimeDelays = listOf("s1" to 120),
                        vehicleLicencePlate = "ABC123",
                    )
                )
            ),
            settings = FakeSettingsSource(homeStopId = "central_station"),
        )

        busInfo.associatedStopTimes.test {
            val departure = awaitDepartures().single()
            assertEquals(Duration.ofSeconds(120), departure.realtimeBusStopTimesRecord?.stopTimeDelay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `realtime is matched per stop, not broadcast across the trip`() = runTest {
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(
                stopsById = mapOf("central_station" to central),
                stopTimesByStop = mapOf(
                    "central_station" to listOf(
                        stopTime(tripId = "t1", stopId = "s1", sequence = 1),
                        stopTime(tripId = "t1", stopId = "s2", sequence = 2),
                    )
                ),
            ),
            realtime = FakeRealtimeSource(
                busData = listOf(
                    RealtimeBusTripInfo(
                        id = "e1",
                        tripId = "t1",
                        updatedAt = baseTime,
                        stopTimeDelays = listOf("s1" to 120),   // live data for s1 only
                        vehicleLicencePlate = "ABC123",
                    )
                )
            ),
            settings = FakeSettingsSource(homeStopId = "central_station"),
        )

        busInfo.associatedStopTimes.test {
            val departures = awaitDepartures()
            val s1 = departures.single { it.busStopTimesRecord.stopId == "s1" }
            val s2 = departures.single { it.busStopTimesRecord.stopId == "s2" }
            assertEquals(Duration.ofSeconds(120), s1.realtimeBusStopTimesRecord?.stopTimeDelay)
            assertEquals(null, s2.realtimeBusStopTimesRecord)   // same trip, no live data → stays null
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing a saved stop drops it from savedStops`() = runTest {
        val settings = FakeSettingsSource()
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(stopsById = mapOf("central_station" to central)),
            settings = settings,
        )

        busInfo.savedStops.test {
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())
            settings.addSavedStop("central_station")
            assertEquals(listOf(central), awaitItem())
            busInfo.removeSavedStop(central)
            assertEquals(emptyList<GlobbedBusStopRecord>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a saved id that no longer resolves is omitted from savedStops`() = runTest {
        // Both ids are saved, but the schedule only knows central — the dangling id is dropped
        // (mapNotNull), not surfaced as a blank entry.
        val busInfo = busInfo(
            static = FakeStaticGtfsSource(stopsById = mapOf("central_station" to central)),
            settings = FakeSettingsSource(savedStops = setOf("central_station", "ghost_stop")),
        )

        busInfo.savedStops.test {
            var saved = awaitItem()
            while (saved.isEmpty()) saved = awaitItem()   // skip the stateIn's initial empty
            assertEquals(listOf(central), saved)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
