
import android.location.Location
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.learning.BusStopInfo
import com.example.learning.ScheduledStopTimesInfo
import com.example.learning.database.AppDatabase
import com.example.learning.repos.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import com.example.learning.BuildConfig


class GtfsStaticRepository(
    val database: AppDatabase,
    private val fileRepository: FileRepository,
    private val httpClient: OkHttpClient
) {
    private val gtfsUrl = "https://api.transport.nsw.gov.au/v1/gtfs/schedule/buses"
    private val apiKey = BuildConfig.TRANSPORT_NSW_API_KEY
    private val request = Request
        .Builder()
        .url(gtfsUrl)
        .header("Authorization", value = "apikey $apiKey")
        .build()
    private val stopTimesDao = database.stopTimesDao()
    private val stopsDao = database.stopsDao()
    private val tripsDao = database.tripsDao()
    private val routesDao = database.routesDao()

    /**
     * Call this when the app starts. It checks if DB is empty.
     * If empty, it parses the TXT file and saves to DB.
     */
    suspend fun updateBusStopData(): Boolean {
        val timestampFileStr = "timestamp"
        val timestamp = fileRepository.readFile(timestampFileStr)?.let {
            Instant.parse(it)
        }
        val parseHTTPDate: (String) -> Instant = {
            ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }

        Log.d("GTFS", "Updating Bus Stop Data with timestamp $timestamp")

        withContext(Dispatchers.IO) {

            // Return early if serverTimestamp is older or the same.
            if (timestamp != null) {
                val headRequest = request.newBuilder().head().build()

                httpClient.newCall(headRequest).execute().use { headResponse ->
                    if (!headResponse.isSuccessful) {
                        throw IOException("HEAD request failed: ${headResponse.code}")
                    }

                    val serverTimestamp = headResponse.header("last-modified")?.let {
                        parseHTTPDate(it)
                    }

                    Log.d("GTFS", "Checking $timestamp vs. $serverTimestamp")
                    // If server timestamp is older or equal to what we have and there's data, STOP.
                    if (
                        serverTimestamp != null
                        && timestamp >= serverTimestamp
                        && stopTimesDao.getCount() != 0
                        && stopsDao.getCount() != 0
                    ) {
                        Log.d("GTFS", "Early return")
                        return@withContext null
                    }
                }
            }

            // Download the data if not returned early.
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                throw IOException("GET request failed: ${response.code}")
            }

            // Capture the new timestamp to return at the end
            val newTimestamp = response.header("last-modified")?.let {
                parseHTTPDate(it)
            }

            // 3. STREAMING & UNZIPPING
            try {
                val rawStream = response.body?.byteStream() ?: throw IOException("No body")

                rawStream.buffered().use { bufferedBody ->
                    ZipInputStream(bufferedBody).use { zipStream ->
                        var entry = zipStream.nextEntry

                        while (entry != null) {
                            Log.d("GTFS", "Inspecting ${entry.name}")
                            fileRepository.writeFileStream(entry.name, zipStream)

                            entry = zipStream.nextEntry
                        }
                    }
                }

                // Load new Stop Times data.
                val stopTimesCount = stopTimesDao.getCount()
                val stopTimesFile = File(fileRepository.directory, "stop_times.txt")
                val stopTimeColumns = listOf(
                    "tripId",
                    "arrivalTime",
                    "departureTime",
                    "stopId"
                )
                if (stopTimesCount < 4_000_000 && stopTimesCount > 3_000_000) {
                    upsertFromFile(
                        file = stopTimesFile,
                        columns = stopTimeColumns
                    )
                } else {
                    fastLoadFromScratch(
                        file = stopTimesFile,
                        columns = stopTimeColumns
                    )
                }

                // Load new Stops data
                val stopsCount = stopsDao.getCount()
                val stopsFile = File(fileRepository.directory, "stops.txt")
                val stopsColumns = listOf(
                    "id",
                    "name",
                    "latitude",
                    "longitude"
                )
                if (stopsCount < 40_000 && stopsCount > 30_000) {
                    upsertFromFile(
                        file = stopsFile,
                        columns = stopsColumns
                    )
                } else {
                    fastLoadFromScratch(
                        file = stopsFile,
                        columns = stopsColumns
                    )
                }

                // Load new Trips data
                val tripsCount = tripsDao.getCount()
                val tripsFile = File(fileRepository.directory, "trips.txt")
                val tripsColumns = listOf(
                    "routeId",
                    "serviceId",
                    "tripId",
                    "shapeId",
                    "tripHeadsign"
                )
                if (tripsCount < 100_000 && tripsCount > 90_000) {
                    upsertFromFile(
                        file = tripsFile,
                        columns = tripsColumns
                    )
                } else {
                    fastLoadFromScratch(
                        file = tripsFile,
                        columns = tripsColumns
                    )
                }

                // Load new routes data
                val routesCount = routesDao.getCount()
                val routesFile = File(fileRepository.directory, "routes.txt")
                val routesColumns = listOf(
                    "routeId",
                    "agencyId",
                    "routeShortName"
                )
                if (routesCount < 6500 && routesCount > 5500) {
                    upsertFromFile(
                        file = routesFile,
                        columns = routesColumns
                    )
                } else {
                    fastLoadFromScratch(
                        file = routesFile,
                        columns = routesColumns
                    )
                }

                // Write timestamp after all done.
                fileRepository.writeFile("timestamp", newTimestamp.toString())

            } catch (e: Exception) {
                throw IOException("Error unzipping stream", e)
            } finally {
                // Ensure the connection is released back to the pool
                response.close()
            }

        }

        return true
    }

    private fun insertBatch(
        db: SupportSQLiteDatabase,
        tableName: String,
        columns: List<String>,
        lines: List<String>
    ) {
        val colCount = columns.count()
        val sql = buildString {
            append("""
                INSERT INTO ${tableName}(
            """.trimIndent())

            columns.forEachIndexed { i, column ->
                if (i > 0) append(',')
                append(column)
            }

            append("""
                ) VALUES
            """.trimIndent())

            lines.indices.forEach { i ->
                if (i > 0) append(',')
                append('(')
                repeat(colCount) {
                    if (it > 0) append(',')
                    append('?')
                }
                append(')')
            }
        }

        val stmt = db.compileStatement(sql)

        var bindIndex = 1
        for (line in lines) {
            val cols = parseCols(line, colCount)
            repeat (colCount) { stmt.bindString(bindIndex++, cols[it]) }
        }

        stmt.executeUpdateDelete()
    }

    inline fun parseCols(line: String, colCount: Int): Array<String> {
        val result = Array(colCount) { "" }
        var col = 0
        var start = 0

        for (i in line.indices) {
            if (line[i] == ',' && line[i-1] == '"') {
                result[col++] = line.substring(start, i).removeSurrounding("\"")
                start = i + 1
                if (col == colCount) break
            }
        }
        return result
    }

    fun fastLoadFromScratch(file: File, columns: List<String>) {
        val BATCH = 500
        val db = database.openHelper.writableDatabase

        // Keeping WAL mode so I can read at the same time.
//        db.query("PRAGMA journal_mode=OFF").use {}
        db.execSQL("PRAGMA synchronous=OFF")
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.execSQL("DELETE FROM ${file.nameWithoutExtension}")

        db.beginTransaction()
        try {
            file
                .bufferedReader()
                .useLines { lines ->

                    val iter = lines.drop(1).iterator()
                    var rowCount = 0

                    while (iter.hasNext()) {
                        val rows = ArrayList<String>(BATCH)

                        repeat(BATCH) {
                            if (!iter.hasNext()) return@repeat
                            rows.add(iter.next())
                        }

                        if (rows.isEmpty()) break

                        insertBatch(
                            db = db,
                            tableName = file.nameWithoutExtension,
                            columns = columns,
                            lines = rows,
                        )
                        rowCount += rows.size

                        if (rowCount % 50_000 == 0) {
                            Log.d("GTFS", "Inserted $rowCount rows")
                        }
                    }
                }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
//            db.query("PRAGMA journal_mode=WAL").use {}
            db.execSQL("PRAGMA synchronous=NORMAL")
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }
    private fun upsertFromFile(
        file: File,
        columns: List<String>
    ) {
        val BATCH = 500
        val db = database.openHelper.writableDatabase

        // Lighter pragmas - we're keeping data
        db.execSQL("PRAGMA synchronous=OFF")
        db.execSQL("PRAGMA cache_size=-262144")

        db.beginTransaction()
        try {
            file.bufferedReader().useLines { lines ->
                val iter = lines.drop(1).iterator()
                var rowCount = 0

                while (iter.hasNext()) {
                    val rows = ArrayList<String>(BATCH)
                    repeat(BATCH) {
                        if (!iter.hasNext()) return@repeat
                        rows.add(iter.next())
                    }

                    if (rows.isEmpty()) break

                    upsertBatch(db, file.nameWithoutExtension, columns, rows)
                    rowCount += rows.size

                    if (rowCount % 50_000 == 0) {
                        Log.d("GTFS", "Upserted $rowCount rows")
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.execSQL("PRAGMA synchronous=NORMAL")
        }
    }

    private fun upsertBatch(
        db: SupportSQLiteDatabase,
        tableName: String,
        columns: List<String>,
        lines: List<String>
    ) {
        val colCount = columns.count()

        // Use INSERT OR REPLACE (SQLite's UPSERT)
        val sql = buildString {
            append("INSERT OR REPLACE INTO $tableName(")
            columns.forEachIndexed { i, column ->
                if (i > 0) append(',')
                append(column)
            }
            append(") VALUES")

            lines.indices.forEach { i ->
                if (i > 0) append(',')
                append('(')
                repeat(colCount) {
                    if (it > 0) append(',')
                    append('?')
                }
                append(')')
            }
        }

        val stmt = db.compileStatement(sql)

        var bindIndex = 1
        for (line in lines) {
            val cols = parseCols(line, colCount)
            repeat(colCount) { stmt.bindString(bindIndex++, cols[it]) }
        }

        stmt.executeUpdateDelete()
    }

    suspend fun getStops(): List<BusStopInfo> {
        return stopsDao.getAll().map {
            BusStopInfo(
                id = it.id,
                name = it.name,
                wheelchairBoarding = false,
                location = Location("bus")
                    .apply {
                        try {
                            latitude = it.latitude.toDouble()
                            longitude = it.longitude.toDouble()
                        } catch (e: Exception) {
                            throw IOException("Some bullshit on ${it.id} or ${it.latitude} or ${it.longitude}", e)
                        }
                    }

            )
        }
    }

    // This is now lightning fast
    suspend fun getAssociatedTrips(stopId: String): List<ScheduledStopTimesInfo> {
        val entities = stopTimesDao.getTripsByStopId(stopId)

        Log.d("VM", entities.toString())

        return entities
    }
}
