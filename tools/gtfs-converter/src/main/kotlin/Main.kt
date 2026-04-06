package com.example.gtfsconverter

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

// ═════════════════════════════════════════════════════════════════════
// GTFS → Room-compatible SQLite converter
//
// Usage (local):
//   cd tools/gtfs-converter && gradle run
//
// Usage (CI):
//   gradle run  (env vars TFNSW_API_KEY and GITHUB_TOKEN control behaviour)
//
// The script:
//   1. Downloads the GTFS static bundle from TfNSW
//   2. Extracts CSV files
//   3. Builds a Room-compatible SQLite database
//   4. Gzips the output
//   5. (CI only) Creates a GitHub Release via `gh` CLI
// ═════════════════════════════════════════════════════════════════════

fun main() {
    val apiKey = System.getenv("TFNSW_API_KEY")
        ?: error("Set TFNSW_API_KEY env var (get one from opendata.transport.nsw.gov.au)")

    val workDir = File("build/gtfs-work").apply { mkdirs() }
    val gtfsDir = File(workDir, "gtfs").apply { mkdirs() }
    val dbFile = File(workDir, "gtfs.db")
    val gzFile = File(workDir, "gtfs.db.gz")

    // ── 1. Download ──────────────────────────────────────────────────
    println("⬇ Downloading GTFS bundle from TfNSW...")
    val zipFile = File(workDir, "gtfs.zip")
    downloadFile(
        url = "https://api.transport.nsw.gov.au/v1/gtfs/schedule/buses",
        dest = zipFile,
        headers = mapOf("Authorization" to "apikey $apiKey"),
    )
    println("  Downloaded ${zipFile.length() / 1_048_576}MB")

    // ── 2. Extract ───────────────────────────────────────────────────
    println("📦 Extracting...")
    extractZip(zipFile, gtfsDir)
    println("  Files: ${gtfsDir.listFiles()?.map { it.name }}")

    // ── 3. Build SQLite ──────────────────────────────────────────────
    println("🗄  Building SQLite database...")
    if (dbFile.exists()) dbFile.delete()
    buildDatabase(gtfsDir, dbFile)

    // ── 4. Compress ──────────────────────────────────────────────────
    println("🗜  Compressing...")
    gzipFile(dbFile, gzFile)
    val sha256 = sha256(gzFile)
    println("  ${gzFile.name}: ${gzFile.length() / 1_048_576}MB (sha256: ${sha256.take(12)}…)")

    // ── 5. Upload as GitHub Release (CI only) ────────────────────────
    if (System.getenv("GITHUB_ACTIONS") == "true") {
        uploadGitHubRelease(gzFile, sha256)
    } else {
        println("✅ Done. Output: ${gzFile.absolutePath}")
    }
}

// ═════════════════════════════════════════════════════════════════════
// Download & extraction
// ═════════════════════════════════════════════════════════════════════

private fun downloadFile(url: String, dest: File, headers: Map<String, String> = emptyMap()) {
    val conn = URI(url).toURL().openConnection().apply {
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        connectTimeout = 30_000
        readTimeout = 300_000
    }
    conn.getInputStream().use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
}

private fun extractZip(zipFile: File, destDir: File) {
    ZipInputStream(zipFile.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val outFile = File(destDir, entry.name)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out -> zis.copyTo(out) }
            }
            entry = zis.nextEntry
        }
    }
}

private fun gzipFile(src: File, dest: File) {
    src.inputStream().use { input ->
        GZIPOutputStream(FileOutputStream(dest)).use { gzOut ->
            input.copyTo(gzOut, bufferSize = 65536)
        }
    }
}

private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        var n: Int
        while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// ═════════════════════════════════════════════════════════════════════
// GitHub Release upload via `gh` CLI (pre-installed on GH Actions)
// ═════════════════════════════════════════════════════════════════════

private fun uploadGitHubRelease(gzFile: File, sha256: String) {
    val date = java.time.LocalDate.now().toString()         // 2026-04-06
    val tag = "gtfs-$date"
    val title = "GTFS Database $date"
    val notes = "Auto-built GTFS static database.\n\nSHA-256: `$sha256`\nSize: ${gzFile.length()} bytes"

    println("🚀 Creating GitHub Release: $tag")

    // Delete existing release with same tag if re-running same day
    runCommand("gh", "release", "delete", tag, "--yes", "--cleanup-tag", allowFailure = true)

    runCommand(
        "gh", "release", "create", tag,
        gzFile.absolutePath,
        "--title", title,
        "--notes", notes,
        "--latest",
    )
    println("✅ Released as $tag")
}

private fun runCommand(vararg args: String, allowFailure: Boolean = false) {
    val proc = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val exit = proc.waitFor()
    if (exit != 0 && !allowFailure) {
        error("Command failed (exit $exit): ${args.joinToString(" ")}\n$output")
    }
    if (output.isNotBlank()) println("  $output")
}

// ═════════════════════════════════════════════════════════════════════
// SQLite database builder
// ═════════════════════════════════════════════════════════════════════

private fun buildDatabase(gtfsDir: File, dbFile: File) {
    DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
        // In buildDatabase(), change the opening to:
        conn.pragma("journal_mode = OFF")
        conn.pragma("synchronous = OFF")
        conn.pragma("cache_size = -64000")
        conn.pragma("page_size = 4096")
        conn.autoCommit = false

        createTables(conn)
        conn.commit()

        val tables = listOf(
            GtfsTable("agency", "agency.txt", ::agencyBinder, 6),
            GtfsTable("calendar", "calendar.txt", ::calendarBinder, 10),
            GtfsTable("calendar_dates", "calendar_dates.txt", ::calendarDatesBinder, 3),
            GtfsTable("routes", "routes.txt", ::routesBinder, 9),
            GtfsTable("stops", "stops.txt", ::stopsBinder, 13),
            GtfsTable("trips", "trips.txt", ::tripsBinder, 9),
            GtfsTable("stop_times", "stop_times.txt", ::stopTimesBinder, 10),
        )

        for (table in tables) {
            val file = File(gtfsDir, table.fileName)
            if (!file.exists()) {
                println("  ⏭  ${table.fileName} not found, skipping")
                continue
            }
            loadTable(conn, table, file)
        }

        println("  Creating indices...")
        createIndices(conn)
        conn.commit()

        conn.autoCommit = true
        conn.pragma("journal_mode = WAL")
        conn.pragma("wal_checkpoint(TRUNCATE)")

        println("  DB: ${dbFile.length() / 1_048_576}MB")
    }
}

// ── Schema DDL (must match Room entity definitions exactly) ──────────

private fun createTables(conn: Connection) {
    conn.exec("""
        CREATE TABLE IF NOT EXISTS agency (
            agency_id TEXT NOT NULL PRIMARY KEY,
            agency_name TEXT NOT NULL,
            agency_url TEXT NOT NULL,
            agency_timezone TEXT NOT NULL,
            agency_lang TEXT,
            agency_phone TEXT
        )
    """)
    conn.exec("""
        CREATE TABLE IF NOT EXISTS routes (
            route_id TEXT NOT NULL PRIMARY KEY,
            agency_id TEXT,
            route_short_name TEXT,
            route_long_name TEXT,
            route_desc TEXT,
            route_type INTEGER NOT NULL,
            route_url TEXT,
            route_color TEXT,
            route_text_color TEXT,
            FOREIGN KEY (agency_id) REFERENCES agency(agency_id)
        )
    """)
    conn.exec("""
        CREATE TABLE IF NOT EXISTS calendar (
            service_id TEXT NOT NULL PRIMARY KEY,
            monday INTEGER NOT NULL,
            tuesday INTEGER NOT NULL,
            wednesday INTEGER NOT NULL,
            thursday INTEGER NOT NULL,
            friday INTEGER NOT NULL,
            saturday INTEGER NOT NULL,
            sunday INTEGER NOT NULL,
            start_date TEXT NOT NULL,
            end_date TEXT NOT NULL
        )
    """)
    conn.exec("""
        CREATE TABLE IF NOT EXISTS calendar_dates (
            service_id TEXT NOT NULL,
            date TEXT NOT NULL,
            exception_type INTEGER NOT NULL,
            PRIMARY KEY (service_id, date)
        )
    """)
    conn.exec("""
        CREATE TABLE IF NOT EXISTS trips (
            trip_id TEXT NOT NULL PRIMARY KEY,
            route_id TEXT NOT NULL,
            service_id TEXT NOT NULL,
            trip_headsign TEXT,
            trip_short_name TEXT,
            direction_id INTEGER,
            block_id TEXT,
            shape_id TEXT,
            wheelchair_accessible INTEGER,
            FOREIGN KEY (route_id) REFERENCES routes(route_id)
        )
    """)
    conn.exec("""
        CREATE TABLE IF NOT EXISTS stops (
            stop_id TEXT NOT NULL PRIMARY KEY,
            stop_code TEXT,
            stop_name TEXT,
            stop_desc TEXT,
            stop_lat REAL,
            stop_lon REAL,
            zone_id TEXT,
            stop_url TEXT,
            location_type INTEGER,
            parent_station TEXT,
            stop_timezone TEXT,
            wheelchair_boarding INTEGER,
            platform_code TEXT
        )
    """)
    conn.exec("""
        CREATE TABLE IF NOT EXISTS stop_times (
            trip_id TEXT NOT NULL,
            arrival_time TEXT,
            departure_time TEXT,
            stop_id TEXT NOT NULL,
            stop_sequence INTEGER NOT NULL,
            stop_headsign TEXT,
            pickup_type INTEGER,
            drop_off_type INTEGER,
            shape_dist_traveled REAL,
            timepoint INTEGER,
            PRIMARY KEY (trip_id, stop_sequence),
            FOREIGN KEY (trip_id) REFERENCES trips(trip_id),
            FOREIGN KEY (stop_id) REFERENCES stops(stop_id)
        )
    """)
}

private fun createIndices(conn: Connection) {
    conn.exec("CREATE INDEX IF NOT EXISTS index_routes_agency_id ON routes(agency_id)")
    conn.exec("CREATE INDEX IF NOT EXISTS index_trips_route_id ON trips(route_id)")
    conn.exec("CREATE INDEX IF NOT EXISTS index_trips_service_id ON trips(service_id)")
    conn.exec("CREATE INDEX IF NOT EXISTS index_stops_parent_station ON stops(parent_station)")
    conn.exec("CREATE INDEX IF NOT EXISTS index_stop_times_stop_id ON stop_times(stop_id)")
    conn.exec("CREATE INDEX IF NOT EXISTS index_stop_times_trip_id ON stop_times(trip_id)")
    conn.exec("CREATE INDEX IF NOT EXISTS index_stop_times_departure_time ON stop_times(departure_time)")
}

// ── CSV loading ──────────────────────────────────────────────────────

private data class GtfsTable(
    val tableName: String,
    val fileName: String,
    val binder: (PreparedStatement, Map<String, String>) -> Unit,
    val columnCount: Int,
)

private fun loadTable(conn: Connection, table: GtfsTable, file: File) {
    val placeholders = (1..table.columnCount).joinToString(",") { "?" }
    val sql = "INSERT OR REPLACE INTO ${table.tableName} VALUES ($placeholders)"
    val reader = csvReader { charset = "UTF-8" }
    var count = 0L

    conn.prepareStatement(sql).use { stmt ->
        reader.open(file) {
            val header = readNext()?.map { it.trimStart('\uFEFF').trim() } ?: return@open
            readAllAsSequence().forEach { row ->
                val r = header.zip(row).toMap()
                try {
                    stmt.clearParameters()
                    table.binder(stmt, r)
                    stmt.addBatch()
                    count++
                    if (count % 10_000 == 0L) {
                        stmt.executeBatch()
                        conn.commit()
                    }
                } catch (e: Exception) {
                    System.err.println("  ⚠ ${table.fileName} row $count: ${e.message}")
                }
            }
            stmt.executeBatch()
            conn.commit()
        }
    }
    println("  ${table.fileName}: $count rows")
}

// ── Column binders ───────────────────────────────────────────────────

private fun agencyBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["agency_id"] ?: "")
    s.setString(2, r["agency_name"] ?: "")
    s.setString(3, r["agency_url"] ?: "")
    s.setString(4, r["agency_timezone"] ?: "")
    s.setNullable(5, r["agency_lang"])
    s.setNullable(6, r["agency_phone"])
}

private fun routesBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["route_id"] ?: "")
    s.setNullable(2, r["agency_id"])
    s.setNullable(3, r["route_short_name"])
    s.setNullable(4, r["route_long_name"])
    s.setNullable(5, r["route_desc"])
    s.setInt(6, r["route_type"]?.toIntOrNull() ?: 0)
    s.setNullable(7, r["route_url"])
    s.setNullable(8, r["route_color"])
    s.setNullable(9, r["route_text_color"])
}

private fun calendarBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["service_id"] ?: "")
    s.setInt(2, r["monday"]?.toIntOrNull() ?: 0)
    s.setInt(3, r["tuesday"]?.toIntOrNull() ?: 0)
    s.setInt(4, r["wednesday"]?.toIntOrNull() ?: 0)
    s.setInt(5, r["thursday"]?.toIntOrNull() ?: 0)
    s.setInt(6, r["friday"]?.toIntOrNull() ?: 0)
    s.setInt(7, r["saturday"]?.toIntOrNull() ?: 0)
    s.setInt(8, r["sunday"]?.toIntOrNull() ?: 0)
    s.setString(9, r["start_date"] ?: "")
    s.setString(10, r["end_date"] ?: "")
}

private fun calendarDatesBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["service_id"] ?: "")
    s.setString(2, r["date"] ?: "")
    s.setInt(3, r["exception_type"]?.toIntOrNull() ?: 0)
}

private fun tripsBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["trip_id"] ?: "")
    s.setString(2, r["route_id"] ?: "")
    s.setString(3, r["service_id"] ?: "")
    s.setNullable(4, r["trip_headsign"])
    s.setNullable(5, r["trip_short_name"])
    s.setNullableInt(6, r["direction_id"]?.toIntOrNull())
    s.setNullable(7, r["block_id"])
    s.setNullable(8, r["shape_id"])
    s.setNullableInt(9, r["wheelchair_accessible"]?.toIntOrNull())
}

private fun stopsBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["stop_id"] ?: "")
    s.setNullable(2, r["stop_code"])
    s.setNullable(3, r["stop_name"])
    s.setNullable(4, r["stop_desc"])
    s.setNullableDouble(5, r["stop_lat"]?.toDoubleOrNull())
    s.setNullableDouble(6, r["stop_lon"]?.toDoubleOrNull())
    s.setNullable(7, r["zone_id"])
    s.setNullable(8, r["stop_url"])
    s.setNullableInt(9, r["location_type"]?.toIntOrNull())
    s.setNullable(10, r["parent_station"])
    s.setNullable(11, r["stop_timezone"])
    s.setNullableInt(12, r["wheelchair_boarding"]?.toIntOrNull())
    s.setNullable(13, r["platform_code"])
}

private fun stopTimesBinder(s: PreparedStatement, r: Map<String, String>) {
    s.setString(1, r["trip_id"] ?: "")
    s.setNullable(2, r["arrival_time"])
    s.setNullable(3, r["departure_time"])
    s.setString(4, r["stop_id"] ?: "")
    s.setInt(5, r["stop_sequence"]?.toIntOrNull() ?: 0)
    s.setNullable(6, r["stop_headsign"])
    s.setNullableInt(7, r["pickup_type"]?.toIntOrNull())
    s.setNullableInt(8, r["drop_off_type"]?.toIntOrNull())
    s.setNullableDouble(9, r["shape_dist_traveled"]?.toDoubleOrNull())
    s.setNullableInt(10, r["timepoint"]?.toIntOrNull())
}

// ── Helpers ──────────────────────────────────────────────────────────

private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql.trimIndent()) }
private fun Connection.pragma(sql: String) = exec("PRAGMA $sql")

private fun PreparedStatement.setNullable(i: Int, v: String?) {
    if (v.isNullOrBlank()) setNull(i, java.sql.Types.VARCHAR) else setString(i, v)
}
private fun PreparedStatement.setNullableInt(i: Int, v: Int?) {
    if (v == null) setNull(i, java.sql.Types.INTEGER) else setInt(i, v)
}
private fun PreparedStatement.setNullableDouble(i: Int, v: Double?) {
    if (v == null) setNull(i, java.sql.Types.REAL) else setDouble(i, v)
}

