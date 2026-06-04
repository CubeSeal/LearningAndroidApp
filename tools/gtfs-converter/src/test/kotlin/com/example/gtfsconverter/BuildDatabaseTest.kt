package com.example.gtfsconverter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Behaviour of the converter's merge pipeline, [buildDatabaseInto].
 *
 * Fully hermetic: the input IO boundary is the [GtfsRowSource] interface, so tests seed an in-memory
 * [InMemoryGtfsRowSource] fake (no CSV files) and the build runs into an in-memory `:memory:` SQLite
 * connection the test then queries (no DB file). Nothing touches disk. The database is the real SQLite
 * engine — deterministic and in-memory — so we assert against the genuine merged output rather than a
 * mock of it. We only ever assert observable DB content ("feeds in → correct merged DB out"), never
 * which internal helper ran.
 *
 * This is the scaffold: [gtfsFeed] and the [Connection] query helpers are the reusable bits; copy a
 * @Test below as a template for new behaviour.
 */
class BuildDatabaseTest {

    @Test
    fun `it merges two feeds into a single database`() {
        withMergedDb(
            gtfsFeed(routeId = "B1", routeType = 3, stopId = "S100") to "",
            gtfsFeed(routeId = "R2", routeType = 2, stopId = "S200") to "T:",
        ) { db ->
            assertEquals(2, db.count("routes"))      // one from each feed
            assertEquals(2, db.count("stops"))
            assertEquals(2, db.count("trips"))
        }
    }

    @Test
    fun `it namespaces the prefixed feed so colliding ids don't overwrite each other`() {
        // Both feeds independently use route_id "R1" and service_id "S1" — only unique *within* a feed.
        // Without prefixing, INSERT OR REPLACE would let the second feed clobber the first; with the
        // train feed prefixed, both survive side by side.
        withMergedDb(
            gtfsFeed(routeId = "R1", serviceId = "S1", routeType = 3) to "",
            gtfsFeed(routeId = "R1", serviceId = "S1", routeType = 2) to "T:",
        ) { db ->
            assertEquals(setOf("R1", "T:R1"), db.ids("SELECT route_id FROM routes"))
            assertEquals(setOf("S1", "T:S1"), db.ids("SELECT service_id FROM calendar"))
        }
    }

    @Test
    fun `an unprefixed feed keeps its ids verbatim`() {
        withMergedDb(gtfsFeed(routeId = "370", stopId = "2000") to "") { db ->
            assertEquals(setOf("370"), db.ids("SELECT route_id FROM routes"))
            assertEquals(setOf("2000"), db.ids("SELECT stop_id FROM stops"))
        }
    }

    @Test
    fun `it globs platform stops that share a station name, across the prefix`() {
        val train = gtfsFeed(
            routeId = "T1", routeType = 2,
            stops = listOf(
                "CEN1" to "Central Station, Platform 1",
                "CEN2" to "Central Station, Platform 2",
            ),
        )

        withMergedDb(train to "T:") { db ->
            // Both platforms collapse under one logical stop id derived from the (unprefixed) station
            // name, while still pointing at the prefixed raw stop ids.
            val globbed = db.query("SELECT globbed_stop_id, stop_id FROM globbed_stops") {
                it.getString("globbed_stop_id") to it.getString("stop_id")
            }
            assertEquals(
                setOf("central_station" to "T:CEN1", "central_station" to "T:CEN2"),
                globbed.toSet(),
            )
        }
    }

    @Test
    fun `joins resolve through the prefixed foreign keys`() {
        // stop_times.trip_id → trips.trip_id → trips.route_id → routes.route_id are all prefixed in
        // lockstep, so a join from a train stop back to its route still lands.
        withMergedDb(gtfsFeed(routeId = "R1", routeShortName = "T1", routeType = 2, stopId = "S1") to "T:") { db ->
            val routeNames = db.query(
                """
                SELECT r.route_short_name AS name
                FROM stop_times st
                JOIN trips t ON st.trip_id = t.trip_id
                JOIN routes r ON t.route_id = r.route_id
                WHERE st.stop_id = 'T:S1'
                """,
            ) { it.getString("name") }

            assertEquals(listOf("T1"), routeNames)
        }
    }

    // ── Hermetic build + assertion helpers ───────────────────────────────────────────────────────

    /**
     * Builds the given (feed, id prefix) pairs into a fresh in-memory SQLite connection and hands it to
     * [assertions]. The connection is closed afterwards, so the whole DB lives and dies in memory.
     */
    private fun withMergedDb(
        vararg feeds: Pair<GtfsRowSource, String>,
        assertions: (Connection) -> Unit,
    ) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            buildDatabaseInto(conn, feeds.toList())
            assertions(conn)
        }
    }

    private fun Connection.count(table: String): Int =
        query("SELECT COUNT(*) AS c FROM $table") { it.getInt("c") }.single()

    private fun Connection.ids(sql: String): Set<String> =
        query(sql) { it.getString(1) }.toSet()

    private fun <T> Connection.query(sql: String, row: (ResultSet) -> T): List<T> =
        createStatement().use { st ->
            st.executeQuery(sql.trimIndent()).use { rs ->
                buildList { while (rs.next()) add(row(rs)) }
            }
        }

    /**
     * A minimal but internally-consistent one-route/one-trip GTFS feed as an in-memory [GtfsRowSource]
     * (`tableFile -> rows`). Because rows are maps, not CSV text, station names with commas just work.
     */
    private fun gtfsFeed(
        routeId: String = "R1",
        routeShortName: String = "370",
        routeType: Int = 3,
        serviceId: String = "S1",
        tripId: String = "t1",
        stopId: String = "S1",
        stops: List<Pair<String, String>> = listOf(stopId to "Main St"),
    ): GtfsRowSource = InMemoryGtfsRowSource(
        mapOf(
            "agency.txt" to listOf(
                mapOf(
                    "agency_id" to "A1", "agency_name" to "Test Agency",
                    "agency_url" to "http://example.test", "agency_timezone" to "Australia/Sydney",
                ),
            ),
            "routes.txt" to listOf(
                mapOf(
                    "route_id" to routeId, "agency_id" to "A1",
                    "route_short_name" to routeShortName, "route_long_name" to "$routeShortName Long",
                    "route_type" to routeType.toString(),
                ),
            ),
            "calendar.txt" to listOf(
                mapOf(
                    "service_id" to serviceId,
                    "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                    "friday" to "1", "saturday" to "1", "sunday" to "1",
                    "start_date" to "20260101", "end_date" to "20261231",
                ),
            ),
            "trips.txt" to listOf(
                mapOf(
                    "trip_id" to tripId, "route_id" to routeId,
                    "service_id" to serviceId, "trip_headsign" to "City",
                ),
            ),
            "stops.txt" to stops.map { (id, name) ->
                mapOf("stop_id" to id, "stop_name" to name, "stop_lat" to "-33.8", "stop_lon" to "151.2")
            },
            "stop_times.txt" to stops.mapIndexed { i, (id, _) ->
                val seq = i + 1
                mapOf(
                    "trip_id" to tripId, "arrival_time" to "08:0$seq:00",
                    "departure_time" to "08:0$seq:00", "stop_id" to id, "stop_sequence" to "$seq",
                )
            },
        ),
    )
}
