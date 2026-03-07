package com.example.learning.database

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.learning.repos.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import com.example.learning.BuildConfig

@Immutable
data class WeeklySchedule (
    val time: LocalTime,
    val day: DayOfWeek,
    val startDate: LocalDate,
    val endDate: LocalDate
)

@Immutable
data class ScheduledStopTimesInfo(
    val id: String, // Unique ID for Lazy columns.
    val stopId: String,
    val tripId: String,
    val departureTime: WeeklySchedule,
    val arrivalTime: WeeklySchedule,
    val tripHeadsign: String,
    val routeShortName: String,
)

@Immutable
data class RawQueryResultScheduledStopTimes(
    val id: Long, // Unique ID for Lazy columns.
    val stopId: String,
    val tripId: String,
    val departureTime: String,
    val arrivalTime: String,
    val tripHeadsign: String,
    val routeShortName: String,
    val calendarStartDate: String,
    val calendarEndDate: String,
    val calendarMonday: String,
    val calendarTuesday: String,
    val calendarWednesday: String,
    val calendarThursday: String,
    val calendarFriday: String,
    val calendarSaturday: String,
    val calendarSunday: String,
)

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
    private val calendarDao = database.routesDao()

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
                            fileRepository.deleteFile(entry.name)
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

                Log.d("GTFS", "Stop times Count = $stopTimesCount")

//                if (stopTimesCount < 4_000_000 && stopTimesCount > 3_000_000) {
//                    upsertFromFile(
//                        file = stopTimesFile,
//                        columns = stopTimeColumns
//                    )
//                } else {
//                    fastLoadFromScratch(
//                        file = stopTimesFile,
//                        columns = stopTimeColumns
//                    )
//                }
//
//                // Load new Stops data
//                val stopsCount = stopsDao.getCount()
//                val stopsFile = File(fileRepository.directory, "stops.txt")
//                val stopsColumns = listOf(
//                    "id",
//                    "name",
//                    "latitude",
//                    "longitude"
//                )
//                if (stopsCount < 40_000 && stopsCount > 30_000) {
////                if (false) {
//                    upsertFromFile(
//                        file = stopsFile,
//                        columns = stopsColumns
//                    )
//                } else {
//                    fastLoadFromScratch(
//                        file = stopsFile,
//                        columns = stopsColumns
//                    )
//                }
//
//                // Load new Trips data
//                val tripsCount = tripsDao.getCount()
//                val tripsFile = File(fileRepository.directory, "trips.txt")
//                val tripsColumns = listOf(
//                    "routeId",
//                    "serviceId",
//                    "tripId",
//                    "shapeId",
//                    "tripHeadsign"
//                )
//                if (tripsCount < 100_000 && tripsCount > 90_000) {
//                    upsertFromFile(
//                        file = tripsFile,
//                        columns = tripsColumns
//                    )
//                } else {
//                    fastLoadFromScratch(
//                        file = tripsFile,
//                        columns = tripsColumns
//                    )
//                }
//
//                // Load new routes data
//                val routesCount = routesDao.getCount()
//                val routesFile = File(fileRepository.directory, "routes.txt")
//                val routesColumns = listOf(
//                    "routeId",
//                    "agencyId",
//                    "routeShortName"
//                )
//                if (routesCount < 6500 && routesCount > 5500) {
//                    upsertFromFile(
//                        file = routesFile,
//                        columns = routesColumns
//                    )
//                } else {
//                    fastLoadFromScratch(
//                        file = routesFile,
//                        columns = routesColumns
//                    )
//                }
//
//                // Load new calendar data
//                val calendarFile = File(fileRepository.directory, "calendar.txt")
//                val calendarColumns = listOf(
//                    "serviceId",
//                    "monday",
//                    "tuesday",
//                    "wednesday",
//                    "thursday",
//                    "friday",
//                    "saturday",
//                    "sunday",
//                    "startDate",
//                    "endDate",
//                )
//
//                fastLoadFromScratch(
//                    file = calendarFile,
//                    columns = calendarColumns
//                )
//
//                // Write timestamp after all done.
//                fileRepository.writeFile("timestamp", newTimestamp.toString())

            } catch (e: Exception) {
                throw IOException("Error unzipping stream", e)
            } finally {
                // Ensure the connection is released back to the pool
                response.close()
            }

        }

        Log.d("GTFS", "Finished updating.")
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
            // Safe check: Are we at the end? OR is the next char a comma?
            val isAtEnd = i == line.length - 1
            val isNextComma = !isAtEnd && line[i + 1] == ','

            if (line[i] == '"' && (isAtEnd || isNextComma)) {
                val parsedString = line.substring(start, i + 1).removeSurrounding("\"")
                result[col++] = parsedString

                // Move start past the comma (i + 2), or just finish if at end
                start = i + 2

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

    suspend fun getStops(): List<BusStopInfoEntity> {
        val fileStr: String = fileRepository.readFile("stops.txt") ?: return emptyList()

        return fileStr.trimEnd().lines().drop(1).map {
            val lineArray: Array<String> = parseCols(it, 4)

            val busStopInfoEntity = BusStopInfoEntity(
                id = lineArray[0] ,
                name = lineArray[1] ,
                latitude = lineArray[2] ,
                longitude = lineArray[3] ,
            )

            Log.d("GTFS", busStopInfoEntity.toString())

            return@map busStopInfoEntity
        }
    }

    fun createWeeklySchedule(
        startDay: DayOfWeek,
        rawTime: String,
        startDate: String,
        endDate: String
    ): WeeklySchedule {
        // 1. Split "26:30" into hours and minutes
        val parts = rawTime.split(":")
        val rawHours = parts[0].toLong()
        val rawMinutes = parts[1].toLong()

        // 2. Convert everything to total minutes to handle cases like "23:90"
        val totalMinutes = (rawHours * 60) + rawMinutes

        // 3. Calculate how many days to add
        val daysToAdd = totalMinutes / (24 * 60)

        // 4. Calculate the time remaining in the final day
        val minutesIntoDay = totalMinutes % (24 * 60)

        // 5. Shift the day (DayOfWeek handles the Mon-Sun wrapping automatically)
        val newDay = startDay.plus(daysToAdd)

        // 6. Create the normalized time
        val newTime = LocalTime.of((minutesIntoDay / 60).toInt(), (minutesIntoDay % 60).toInt())

        return WeeklySchedule(
            newTime,
            newDay,
            LocalDate.parse(startDate, DateTimeFormatter.BASIC_ISO_DATE),
            LocalDate.parse(endDate, DateTimeFormatter.BASIC_ISO_DATE)
        )
    }

    private fun convertRawQueryToScheduledStopTimesInfo(rawquerylist: List<RawQueryResultScheduledStopTimes>): List<ScheduledStopTimesInfo> {
       return rawquerylist.flatMap {
                buildList {
                    if (it.calendarMonday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Monday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.MONDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.MONDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )

                    if (it.calendarTuesday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Tuesday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.TUESDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.TUESDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )

                    if (it.calendarWednesday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Wednesday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.WEDNESDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.WEDNESDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )

                    if (it.calendarThursday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Thursday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.THURSDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.THURSDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )

                    if (it.calendarFriday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Friday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.FRIDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.FRIDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )

                    if (it.calendarSaturday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Saturday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.SATURDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.SATURDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )

                    if (it.calendarSunday != "") add(
                        ScheduledStopTimesInfo(
                            id = "${it.id}-Sunday",
                            stopId = it.stopId,
                            tripId =  it.tripId,
                            tripHeadsign = it.tripHeadsign,
                            routeShortName = it.routeShortName,
                            departureTime = createWeeklySchedule(DayOfWeek.SUNDAY, it.departureTime, it.calendarStartDate, it.calendarEndDate),
                            arrivalTime = createWeeklySchedule(DayOfWeek.SUNDAY, it.arrivalTime, it.calendarStartDate, it.calendarEndDate),
                        )
                    )
                }
           }
    }

    // This is now lightning fast
    suspend fun getAssociatedTrips(stopId: String, time: LocalDateTime): Pair<List<ScheduledStopTimesInfo>, Int> {
        Log.d("GTFS", "Started getAssociatedTrips")
        val nowDay = time.dayOfWeek
        val nowTime = time.toLocalTime()
        val absPath = fileRepository.directory.absolutePath
        val fileStr: String = fileRepository.runShellCommand(
            "sh",
            "-c",
            """grep "\"$stopId\"" $absPath/stop_times.txt | awk -F, '$4 == "\"$stopId\""'"""
            )
        val routesCache = ConcurrentHashMap<String, String>()
        val serviceCache = ConcurrentHashMap<String, String>()

        val entities: List<RawQueryResultScheduledStopTimes> = coroutineScope {
            fileStr.trimEnd().lines().mapIndexed { i, it ->
                async {
                    val stopLineArray: Array<String> = parseCols(it, 4)
                    val tripId: String = stopLineArray[0]
                    val tripFileStr = fileRepository.runShellCommand(
                        "sh",
                        "-c",
                        """grep \""$tripId\"" $absPath/trips.txt | awk -F, '$3 == "\"$tripId\""'"""
                    )

                    return@async tripFileStr.trimEnd().lines().map {
                        val tripLineArray: Array<String> = parseCols(tripFileStr, 5)
                        val routeId: String = tripLineArray[0]

                        // These should be one line :)

                        val routeFileStrDeferred = async {
                            routesCache.getOrPut(routeId) {
                                fileRepository.runShellCommand(
                                    "sh",
                                    "-c",
                                    """grep \""$routeId\"" $absPath/routes.txt | awk -F, '$1 == "\"$routeId\""'"""
                                )
                            }
                        }

                        val serviceId: String = tripLineArray[1]
                        val serviceIdStrDeferred = async {
                            serviceCache.getOrPut(serviceId) {
                                fileRepository.runShellCommand(
                                    "sh",
                                    "-c",
                                    """grep \""$serviceId\"" $absPath/calendar.txt | awk -F, '$1 == "\"$serviceId\""'"""
                                )
                            }
                        }

                        val routeFileStr = routeFileStrDeferred.await()
                        val serviceIdStr = serviceIdStrDeferred.await()
                        val routeLineArray: Array<String> = parseCols(routeFileStr.trimEnd(), 3)
                        val serviceLineArray: Array<String> = parseCols(serviceIdStr.trimEnd(), 10)

                        val result = RawQueryResultScheduledStopTimes(
                            id = i.toLong(),
                            stopId = stopLineArray[3],
                            tripId = stopLineArray[0],
                            departureTime = stopLineArray[1],
                            arrivalTime = stopLineArray[2],
                            tripHeadsign = tripLineArray[4],
                            routeShortName = routeLineArray[2],
                            calendarStartDate = serviceLineArray[8],
                            calendarEndDate = serviceLineArray[9],
                            calendarMonday = serviceLineArray[1],
                            calendarTuesday = serviceLineArray[2],
                            calendarWednesday = serviceLineArray[3],
                            calendarThursday = serviceLineArray[4],
                            calendarFriday = serviceLineArray[5],
                            calendarSaturday = serviceLineArray[6],
                            calendarSunday = serviceLineArray[7]
                        )

                        return@map result
                    }
                }
            }.awaitAll().flatten()
        }

        val sortedList = convertRawQueryToScheduledStopTimesInfo(entities).sortedWith(
            compareBy<ScheduledStopTimesInfo> {it.arrivalTime.day}.thenBy {it.arrivalTime.time}
        )
        val prefix = sortedList.indexOfFirst { it.arrivalTime.time > nowTime && it.arrivalTime.day >= nowDay }

        return Pair(sortedList, if (prefix == -1) 0 else prefix)
    }
}
