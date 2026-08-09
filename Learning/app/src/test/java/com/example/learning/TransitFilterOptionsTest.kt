package com.example.learning

import com.example.learning.repos.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TransitFilterOptions] is [Comparable], so `sorted()` is the single ordering rule behind both the
 * Home filter row and the FilterPage's chips. Asserted here at its own API.
 */
class TransitFilterOptionsTest {
    @Test
    fun `sorts by type tier, then train before bus, then label`() {
        val busRoute370 = TransitFilterOptions.RouteShortName("370", TransitMode.BUS)
        val trainLineT1 = TransitFilterOptions.RouteShortName("T1", TransitMode.TRAIN)
        val busStand = TransitFilterOptions.StopStand("Stand A", TransitMode.BUS)
        val trainPlatform = TransitFilterOptions.StopStand("Platform 1", TransitMode.TRAIN)
        val busMode = TransitFilterOptions.TransportMode(TransitMode.BUS)
        val trainMode = TransitFilterOptions.TransportMode(TransitMode.TRAIN)
        val headsign = TransitFilterOptions.TripHeadsign("Central")

        assertEquals(
            // mode -> stand/platform -> route/line -> destination, trains ahead of buses in each tier
            listOf(trainMode, busMode, trainPlatform, busStand, trainLineT1, busRoute370, headsign),
            listOf(headsign, busRoute370, busStand, trainMode, busMode, trainLineT1, trainPlatform).sorted(),
        )
    }

    @Test
    fun `sorts labels numerically, with non-numeric labels after the numbers`() {
        val routes = listOf("370", "L90", "9", "100")
            .map { TransitFilterOptions.RouteShortName(it, TransitMode.BUS) }

        assertEquals(
            listOf("9", "100", "370", "L90"),
            routes.sorted().map { it.filterLabel() },
        )
    }
}
