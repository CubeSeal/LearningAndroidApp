package com.example.learning

import app.cash.turbine.test
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedBusStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.RealtimeBusTripInfo
import com.example.learning.repos.TransitMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/**
 * Behaviour of [HomeViewModel.associatedStopTimes] — the user-facing departure list. Built on top of
 * [BusInfo] driven by state-based fakes; we assert on what the ViewModel exposes (order, membership,
 * the "upcoming" flag), never on internals.
 *
 * The VM auto-focuses the nearest stop, so each test seeds a location + a closest stop, which drives
 * the focused stop and the whole derived pipeline. `now`-relative departure times keep the 2-minute
 * past-buffer assertions deterministic (BusInfo.currentMinute reads the real wall clock).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sydney = LatLon(-33.8688, 151.2093)
    private val now: LocalDateTime = LocalDateTime.now()

    private val stopA = GlobbedBusStopRecord("stopA", "Stop A", emptyList())

    private fun stopTime(
        tripId: String,
        departure: LocalDateTime,
        routeShortName: String = "370",
        tripHeadsign: String = "City",
        stopId: String = "s1",
        routeType: Int = 3,   // GTFS 3 = bus, the default mode for these fixtures
    ) = BusStopTimesRecord(
        tripId = tripId,
        departureTime = departure,
        arrivalTime = departure,
        sequence = 1,
        routeId = "route_$routeShortName",
        serviceId = "svc1",
        tripHeadsign = tripHeadsign,
        routeShortName = routeShortName,
        routeLongName = "$routeShortName Long",
        routeType = routeType,
        globbedStopId = "stopA",
        globbedStopName = "Stop A",
        stopId = stopId,
        stopName = "Stand A",
        stopLoc = sydney,
        wheelchairBoarding = false,
    )

    private fun TestScope.homeViewModel(
        trips: List<BusStopTimesRecord>,
        realtime: List<RealtimeBusTripInfo> = emptyList(),
    ): HomeViewModel {
        val busInfo = BusInfo(
            gtfsStaticRepository = FakeStaticGtfsSource(
                stopsById = mapOf("stopA" to stopA),
                closest = listOf(stopA),
                stopTimesByStop = mapOf("stopA" to trips),
            ),
            gtfsRealtimeRepository = FakeRealtimeSource(realtime),
            locationRepo = FakeLocationSource(location = sydney),
            settingsRepo = FakeSettingsSource(),
            scope = backgroundScope,
        )
        return HomeViewModel(busInfo)
    }

    private fun List<Pair<Boolean, com.example.learning.BusStopTimesRecordWithRealtime>>.tripIds() =
        map { it.second.busStopTimesRecord.tripId }

    @Test
    fun `departures older than the two-minute past buffer are dropped`() = runTest {
        val vm = homeViewModel(
            trips = listOf(
                stopTime(tripId = "past", departure = now.minusMinutes(5)),
                stopTime(tripId = "recent", departure = now.minusMinutes(1)),
                stopTime(tripId = "future", departure = now.plusMinutes(10)),
            )
        )

        vm.associatedStopTimes.test {
            var items = awaitItem()
            while (items.isEmpty()) items = awaitItem()
            // "past" is >2 min old so it's gone; the rest stay, ordered by time.
            assertEquals(listOf("recent", "future"), items.tripIds())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `only not-yet-departed buses are flagged as upcoming`() = runTest {
        val vm = homeViewModel(
            trips = listOf(
                stopTime(tripId = "recent", departure = now.minusMinutes(1)),
                stopTime(tripId = "future", departure = now.plusMinutes(10)),
            )
        )

        vm.associatedStopTimes.test {
            var items = awaitItem()
            while (items.isEmpty()) items = awaitItem()
            val upcoming = items.associate { it.second.busStopTimesRecord.tripId to it.first }
            assertEquals(false, upcoming["recent"])   // departed <2 min ago, still listed but not upcoming
            assertEquals(true, upcoming["future"])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `departures are ordered by realtime-adjusted time`() = runTest {
        val vm = homeViewModel(
            trips = listOf(
                stopTime(tripId = "A", departure = now.plusMinutes(5), stopId = "s1"),
                stopTime(tripId = "B", departure = now.plusMinutes(8), stopId = "s2"),
            ),
            realtime = listOf(
                RealtimeBusTripInfo(
                    id = "e1",
                    tripId = "A",
                    updatedAt = now,
                    stopTimeDelays = listOf("s1" to 600),   // A runs 10 minutes late
                    vehicleLicencePlate = "ABC123",
                )
            ),
        )

        vm.associatedStopTimes.test {
            // Wait for the state where A's delay has been joined; only then is the order meaningful.
            var items = awaitItem()
            while (items.none { it.second.busStopTimesRecord.tripId == "A" && it.second.realtimeBusStopTimesRecord != null }) {
                items = awaitItem()
            }
            // A is scheduled earlier (5 vs 8) but its delay pushes it after B (15 vs 8).
            assertEquals(listOf("B", "A"), items.tripIds())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting a route filter narrows departures and clearing restores them`() = runTest {
        val vm = homeViewModel(
            trips = listOf(
                stopTime(tripId = "t370", routeShortName = "370", departure = now.plusMinutes(5)),
                stopTime(tripId = "t412", routeShortName = "412", departure = now.plusMinutes(8)),
            )
        )

        vm.associatedStopTimes.test {
            var items = awaitItem()
            while (items.isEmpty()) items = awaitItem()
            assertEquals(listOf("t370", "t412"), items.tripIds())   // no filter → both

            vm.toggleFilterForBusStops(BusFilterOptions.RouteShortName("370"))
            assertEquals(listOf("t370"), awaitItem().tripIds())     // narrowed to the 370 service

            vm.toggleFilterForBusStops(BusFilterOptions.RouteShortName("370"))
            assertEquals(listOf("t370", "t412"), awaitItem().tripIds())   // cleared → both again
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting a mode filter narrows departures to that mode`() = runTest {
        val vm = homeViewModel(
            trips = listOf(
                stopTime(tripId = "bus", routeShortName = "370", routeType = 3, departure = now.plusMinutes(5)),
                stopTime(tripId = "train", routeShortName = "T1", routeType = 2, departure = now.plusMinutes(8)),
            )
        )

        vm.associatedStopTimes.test {
            var items = awaitItem()
            while (items.isEmpty()) items = awaitItem()
            assertEquals(listOf("bus", "train"), items.tripIds())   // no filter → both modes

            vm.toggleFilterForBusStops(BusFilterOptions.TransportMode(TransitMode.TRAIN))
            assertEquals(listOf("train"), awaitItem().tripIds())    // narrowed to the train service
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Twelve distinct routes (one shared headsign) yields >10 BusFilterOptions, so the Home row's
    // base cap and the FilterPage overflow path both come into play.
    private fun manyRouteTrips() = (0..11).map {
        stopTime(tripId = "t$it", routeShortName = "R%02d".format(it), departure = now.plusMinutes(it.toLong() + 1))
    }

    @Test
    fun `home row caps at ten filters and flags that more exist`() = runTest {
        val vm = homeViewModel(trips = manyRouteTrips())

        vm.rowFilters.test {
            var rows = awaitItem()
            while (rows.size < 10) rows = awaitItem()
            assertEquals(10, rows.size)                        // capped to the base 10
            assertTrue(vm.availableFiltersForBusStop.value.size > 10)
            assertEquals(true, vm.hasMoreFilters.value)        // chevron should show
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `applying an out-of-row filter pins it into the row and selects it`() = runTest {
        val vm = homeViewModel(trips = manyRouteTrips())

        vm.rowFilters.test {
            var rows = awaitItem()
            while (rows.size < 10) rows = awaitItem()
            val extra = vm.availableFiltersForBusStop.value.first { it !in rows.toSet() }

            vm.applyFilters(setOf(extra))

            while (extra !in rows) rows = awaitItem()
            assertTrue(extra in rows)                                  // pinned into the row
            assertEquals(setOf(extra), vm.selectedFiltersForBusStop.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a pinned filter stays in the row after being toggled off`() = runTest {
        val vm = homeViewModel(trips = manyRouteTrips())

        vm.rowFilters.test {
            var rows = awaitItem()
            while (rows.size < 10) rows = awaitItem()
            val extra = vm.availableFiltersForBusStop.value.first { it !in rows.toSet() }

            vm.applyFilters(setOf(extra))
            while (extra !in rows) rows = awaitItem()

            vm.toggleFilterForBusStops(extra)                         // toggle it off
            assertTrue(extra !in vm.selectedFiltersForBusStop.value)  // no longer selected
            assertTrue(extra in vm.rowFilters.value)                  // but stays in the row (accumulates)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh resets the row to the base ten and clears the selection`() = runTest {
        val vm = homeViewModel(trips = manyRouteTrips())

        vm.rowFilters.test {
            var rows = awaitItem()
            while (rows.size < 10) rows = awaitItem()
            val extra = vm.availableFiltersForBusStop.value.first { it !in rows.toSet() }

            vm.applyFilters(setOf(extra))
            while (extra !in rows) rows = awaitItem()

            vm.refresh()

            while (extra in rows) rows = awaitItem()
            assertEquals(10, rows.size)                                       // back to the base 10
            assertTrue(extra !in rows)
            assertEquals(emptySet<BusFilterOptions>(), vm.selectedFiltersForBusStop.value)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
