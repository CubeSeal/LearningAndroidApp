package com.example.gtfsconverter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDate

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

    // ── service_dates tests ───────────────────────────────────────────────────────────────────────

    @Test
    fun `it expands a weekly calendar into service_dates within the horizon`() {
        // Mon–Fri service running 2026-06-01 to 2026-06-07. Within a ±28d horizon anchored at
        // 2026-06-05, the weekdays Mon 01, Tue 02, Wed 03, Thu 04, Fri 05 should all appear.
        val feed = InMemoryGtfsRowSource(mapOf(
            "agency.txt" to emptyList(), "routes.txt" to emptyList(), "trips.txt" to emptyList(),
            "stops.txt" to emptyList(), "stop_times.txt" to emptyList(),
            "calendar.txt" to listOf(mapOf(
                "service_id" to "WD",
                "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                "friday" to "1", "saturday" to "0", "sunday" to "0",
                "start_date" to "20260601", "end_date" to "20260607",
            )),
            "calendar_dates.txt" to emptyList(),
        ))

        withMergedDb(feed to "", horizon = LocalDate.of(2026, 5, 28)) { db ->
            val dates = db.query("SELECT date FROM service_dates WHERE service_id = 'WD' ORDER BY date") {
                it.getString("date")
            }
            assertEquals(listOf("20260601", "20260602", "20260603", "20260604", "20260605"), dates)
        }
    }

    @Test
    fun `a REMOVED exception drops a date from the calendar expansion`() {
        val feed = InMemoryGtfsRowSource(mapOf(
            "agency.txt" to emptyList(), "routes.txt" to emptyList(), "trips.txt" to emptyList(),
            "stops.txt" to emptyList(), "stop_times.txt" to emptyList(),
            "calendar.txt" to listOf(mapOf(
                "service_id" to "WD",
                "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                "friday" to "1", "saturday" to "0", "sunday" to "0",
                "start_date" to "20260601", "end_date" to "20260607",
            )),
            "calendar_dates.txt" to listOf(mapOf(
                "service_id" to "WD", "date" to "20260603", "exception_type" to "2",  // REMOVED
            )),
        ))

        withMergedDb(feed to "", horizon = LocalDate.of(2026, 5, 28)) { db ->
            val dates = db.query("SELECT date FROM service_dates WHERE service_id = 'WD' ORDER BY date") {
                it.getString("date")
            }
            assertEquals(listOf("20260601", "20260602", "20260604", "20260605"), dates)
        }
    }

    @Test
    fun `an ADDED exception adds a date outside the normal calendar pattern`() {
        val feed = InMemoryGtfsRowSource(mapOf(
            "agency.txt" to emptyList(), "routes.txt" to emptyList(), "trips.txt" to emptyList(),
            "stops.txt" to emptyList(), "stop_times.txt" to emptyList(),
            "calendar.txt" to listOf(mapOf(
                "service_id" to "WD",
                "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                "friday" to "1", "saturday" to "0", "sunday" to "0",
                "start_date" to "20260601", "end_date" to "20260607",
            )),
            "calendar_dates.txt" to listOf(mapOf(
                "service_id" to "WD", "date" to "20260607", "exception_type" to "1",  // ADDED (Sunday)
            )),
        ))

        withMergedDb(feed to "", horizon = LocalDate.of(2026, 5, 28)) { db ->
            val dates = db.query("SELECT date FROM service_dates WHERE service_id = 'WD' ORDER BY date") {
                it.getString("date")
            }
            assertEquals(listOf("20260601", "20260602", "20260603", "20260604", "20260605", "20260607"), dates)
        }
    }

    @Test
    fun `a calendar_dates-only service (no calendar row) yields its added dates`() {
        // Train services sometimes have no calendar row at all, only calendar_dates exceptions.
        // This is the root cause of the Wynyard crash — the producer must handle it.
        val feed = InMemoryGtfsRowSource(mapOf(
            "agency.txt" to emptyList(), "routes.txt" to emptyList(), "trips.txt" to emptyList(),
            "stops.txt" to emptyList(), "stop_times.txt" to emptyList(),
            "calendar.txt" to emptyList(),
            "calendar_dates.txt" to listOf(
                mapOf("service_id" to "SPECIAL", "date" to "20260605", "exception_type" to "1"),
                mapOf("service_id" to "SPECIAL", "date" to "20260606", "exception_type" to "1"),
            ),
        ))

        withMergedDb(feed to "", horizon = LocalDate.of(2026, 5, 28)) { db ->
            val dates = db.query("SELECT date FROM service_dates WHERE service_id = 'SPECIAL' ORDER BY date") {
                it.getString("date")
            }
            assertEquals(listOf("20260605", "20260606"), dates)
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
    fun `it globs platform stops under their parent station via parent_station`() {
        // Seed a parent station (location_type=1) + two platform children with parent_station set.
        // The existing name-parse path would miss these because platform names don't end in "Station".
        // The parent_station path should fold all three (parent + children) under one globbed id,
        // deduped, using the parent's name.
        val feed = gtfsFeedWithParents(
            parent = Triple("PAR", "Wynyard Station", 1),
            children = listOf(
                "PLT1" to "Wynyard Station, Platform 3",
                "PLT2" to "Wynyard Station, Platform 4",
            ),
        )

        withMergedDb(feed to "T:") { db ->
            val globbed = db.query("SELECT globbed_stop_id, stop_id FROM globbed_stops ORDER BY stop_id") {
                it.getString("globbed_stop_id") to it.getString("stop_id")
            }
            // All three stops (parent + both platforms) glob under one id, no duplicates.
            assertEquals(
                setOf(
                    "wynyard_station" to "T:PAR",
                    "wynyard_station" to "T:PLT1",
                    "wynyard_station" to "T:PLT2",
                ),
                globbed.toSet(),
            )
        }
    }

    @Test
    fun `name-parse globbing still works for bus stops without parent_station`() {
        // Regression guard: the existing name-parse path must keep working after the parent_station
        // extension is added.
        val feed = gtfsFeed(
            stops = listOf(
                "B1" to "Central Station, Bay 1",
                "B2" to "Central Station, Bay 2",
            ),
        )

        withMergedDb(feed to "") { db ->
            val ids = db.query("SELECT DISTINCT globbed_stop_id FROM globbed_stops") { it.getString(1) }
            assertEquals(listOf("central_station"), ids)
        }
    }

    @Test
    fun `it globs stops that share an identical non-station full name`() {
        // "M2 Motorway, Oakes Rd" appears under two stop_ids: no parent_station, not a station,
        // pre-comma "M2 Motorway" is not "% Station". Strategy 3 (identical full name) merges them.
        val feed = gtfsFeed(
            stops = listOf(
                "M1" to "M2 Motorway, Oakes Rd",
                "M2" to "M2 Motorway, Oakes Rd",
            ),
        )

        withMergedDb(feed to "") { db ->
            val globbed = db.query("SELECT globbed_stop_id, stop_id FROM globbed_stops") {
                it.getString("globbed_stop_id") to it.getString("stop_id")
            }
            assertEquals(
                setOf(
                    "m2_motorway,_oakes_rd" to "M1",
                    "m2_motorway,_oakes_rd" to "M2",
                ),
                globbed.toSet(),
            )
        }
    }

    @Test
    fun `it does not glob unique-named non-station stops`() {
        // Distinct non-station names shared by nobody stay ungloBBed (app falls back to stop_id).
        // Guards the HAVING COUNT(*) >= 2 lower bound.
        val feed = gtfsFeed(
            stops = listOf(
                "U1" to "M2 Motorway, Oakes Rd",
                "U2" to "M7 Motorway, Abbott Rd",
            ),
        )

        withMergedDb(feed to "") { db ->
            assertEquals(emptySet<String>(), db.ids("SELECT globbed_stop_id FROM globbed_stops"))
        }
    }

    @Test
    fun `identical-name globbing is capped at the group-size limit`() {
        // A 4-way group still merges; a 5-way group exceeds the cap and stays ungloBBed. Bounds the
        // per-stop query fan-out and coincidental over-merging.
        val four = gtfsFeed(stops = (1..4).map { "F$it" to "M2 Motorway, Oakes Rd" })
        withMergedDb(four to "") { db ->
            assertEquals(
                setOf("m2_motorway,_oakes_rd"),
                db.ids("SELECT DISTINCT globbed_stop_id FROM globbed_stops"),
            )
        }

        val five = gtfsFeed(stops = (1..5).map { "F$it" to "M2 Motorway, Oakes Rd" })
        withMergedDb(five to "") { db ->
            assertEquals(emptySet<String>(), db.ids("SELECT globbed_stop_id FROM globbed_stops"))
        }
    }

    @Test
    fun `no stop_id is globbed under more than one globbed_stop_id`() {
        // Mixed feed exercising all strategies at once; guards the app's LEFT JOIN multiplication
        // risk — every stop_id must belong to exactly one globbed_stop_id.
        val parents = gtfsFeedWithParents(
            parent = Triple("PAR", "Wynyard Station", 1),
            children = listOf("PLT1" to "Wynyard Station, Platform 3"),
        )
        val buses = gtfsFeed(
            stops = listOf(
                "B1" to "Central Station, Bay 1",   // Strategy 2
                "B2" to "Central Station, Bay 2",   // Strategy 2
                "M1" to "M2 Motorway, Oakes Rd",    // Strategy 3
                "M2" to "M2 Motorway, Oakes Rd",    // Strategy 3
                "X1" to "Lonely Rd",                // unique -> ungloBBed
            ),
        )

        withMergedDb(parents to "T:", buses to "") { db ->
            val offenders = db.ids(
                "SELECT stop_id FROM globbed_stops GROUP BY stop_id HAVING COUNT(DISTINCT globbed_stop_id) > 1"
            )
            assertEquals(emptySet<String>(), offenders)
        }
    }

    @Test
    fun `it drops Out Of Service and Non Revenue trips and their stop_times`() {
        // These TfNSW-specific non-revenue route types have no boardable destination and often
        // have null trip_headsign. They must be removed before the DB reaches the app.
        val feed = InMemoryGtfsRowSource(mapOf(
            "agency.txt" to listOf(mapOf(
                "agency_id" to "A1", "agency_name" to "Test Agency",
                "agency_url" to "http://example.test", "agency_timezone" to "Australia/Sydney",
            )),
            "routes.txt" to listOf(
                mapOf("route_id" to "R1", "agency_id" to "A1", "route_short_name" to "T1",
                      "route_long_name" to "Normal Route", "route_type" to "2"),
                mapOf("route_id" to "OOS", "agency_id" to "A1", "route_short_name" to "",
                      "route_long_name" to "Out Of Service", "route_type" to "2"),
                mapOf("route_id" to "NR", "agency_id" to "A1", "route_short_name" to "",
                      "route_long_name" to "Non Revenue", "route_type" to "2"),
            ),
            "calendar.txt" to listOf(mapOf(
                "service_id" to "S1",
                "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                "friday" to "1", "saturday" to "1", "sunday" to "1",
                "start_date" to "20260101", "end_date" to "20261231",
            )),
            "trips.txt" to listOf(
                mapOf("trip_id" to "T1", "route_id" to "R1", "service_id" to "S1", "trip_headsign" to "City"),
                mapOf("trip_id" to "T2", "route_id" to "OOS", "service_id" to "S1", "trip_headsign" to ""),
                mapOf("trip_id" to "T3", "route_id" to "NR",  "service_id" to "S1", "trip_headsign" to "Depot"),
            ),
            "stops.txt" to listOf(mapOf(
                "stop_id" to "S1", "stop_name" to "Stop 1",
                "stop_lat" to "-33.8", "stop_lon" to "151.2", "location_type" to "", "parent_station" to "",
            )),
            "stop_times.txt" to listOf(
                mapOf("trip_id" to "T1", "arrival_time" to "08:00:00", "departure_time" to "08:00:00", "stop_id" to "S1", "stop_sequence" to "1"),
                mapOf("trip_id" to "T2", "arrival_time" to "08:01:00", "departure_time" to "08:01:00", "stop_id" to "S1", "stop_sequence" to "1"),
                mapOf("trip_id" to "T3", "arrival_time" to "08:02:00", "departure_time" to "08:02:00", "stop_id" to "S1", "stop_sequence" to "1"),
            ),
            "calendar_dates.txt" to emptyList(),
        ))

        withMergedDb(feed to "") { db ->
            assertEquals(setOf("T1"), db.ids("SELECT trip_id FROM trips"))
            assertEquals(1, db.count("stop_times"))
            assertEquals(1, db.count("routes"))
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
        horizon: LocalDate = LocalDate.now(),
        assertions: (Connection) -> Unit,
    ) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            buildDatabaseInto(conn, feeds.toList(), horizon)
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
                mapOf("stop_id" to id, "stop_name" to name, "stop_lat" to "-33.8", "stop_lon" to "151.2",
                      "location_type" to "", "parent_station" to "")
            },
            "stop_times.txt" to stops.mapIndexed { i, (id, _) ->
                val seq = i + 1
                mapOf(
                    "trip_id" to tripId, "arrival_time" to "08:0$seq:00",
                    "departure_time" to "08:0$seq:00", "stop_id" to id, "stop_sequence" to "$seq",
                )
            },
            "calendar_dates.txt" to emptyList(),
        ),
    )

    /**
     * A feed where [parent] is a parent station (location_type=1) and [children] are platform stops
     * referencing it via parent_station. Used to test parent_station-based globbing.
     * [parent] is a Triple(stopId, stopName, locationType).
     */
    private fun gtfsFeedWithParents(
        parent: Triple<String, String, Int>,
        children: List<Pair<String, String>>,
        routeId: String = "R1",
        serviceId: String = "S1",
        tripId: String = "t1",
    ): GtfsRowSource {
        val (parentId, parentName, parentLocType) = parent
        val allStops = buildList {
            add(mapOf(
                "stop_id" to parentId, "stop_name" to parentName,
                "stop_lat" to "-33.8", "stop_lon" to "151.2",
                "location_type" to parentLocType.toString(), "parent_station" to "",
            ))
            children.forEach { (id, name) ->
                add(mapOf(
                    "stop_id" to id, "stop_name" to name,
                    "stop_lat" to "-33.8", "stop_lon" to "151.2",
                    "location_type" to "0", "parent_station" to parentId,
                ))
            }
        }
        val stopIds = children.map { it.first }
        return InMemoryGtfsRowSource(mapOf(
            "agency.txt" to listOf(mapOf(
                "agency_id" to "A1", "agency_name" to "Test Agency",
                "agency_url" to "http://example.test", "agency_timezone" to "Australia/Sydney",
            )),
            "routes.txt" to listOf(mapOf(
                "route_id" to routeId, "agency_id" to "A1",
                "route_short_name" to "T1", "route_long_name" to "T1 Long", "route_type" to "2",
            )),
            "calendar.txt" to listOf(mapOf(
                "service_id" to serviceId,
                "monday" to "1", "tuesday" to "1", "wednesday" to "1", "thursday" to "1",
                "friday" to "1", "saturday" to "1", "sunday" to "1",
                "start_date" to "20260101", "end_date" to "20261231",
            )),
            "trips.txt" to listOf(mapOf(
                "trip_id" to tripId, "route_id" to routeId,
                "service_id" to serviceId, "trip_headsign" to "City",
            )),
            "stops.txt" to allStops,
            "stop_times.txt" to stopIds.mapIndexed { i, id ->
                val seq = i + 1
                mapOf(
                    "trip_id" to tripId, "arrival_time" to "08:0$seq:00",
                    "departure_time" to "08:0$seq:00", "stop_id" to id, "stop_sequence" to "$seq",
                )
            },
            "calendar_dates.txt" to emptyList(),
        ))
    }
}
