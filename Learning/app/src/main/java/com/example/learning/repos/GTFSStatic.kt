package com.example.learning.repos

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import com.example.learning.BuildConfig
import com.example.learning.repos.BusStopTimesInfo.Companion.parseGtfsTime
import kotlinx.serialization.Serializable

@Serializable
data class LatLon(val latitude: Double, val longitude: Double)

@Serializable
@Immutable
data class BusCalenderInfo (
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
)

@Serializable
@Immutable
data class BusStopTimesRecord(
    val fakeId: Int, // A fake id not provided
    val stopTimesInfo: BusStopTimesInfo,
    val stopInfo: BusStopInfo,
    val tripInfo: BusTripInfo,
    val routeInfo: BusRouteInfo,
    val calendarInfo: BusCalenderInfo
)

@Serializable
@Immutable
data class BusRouteInfo (
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String
)

@Serializable
@Immutable
data class BusStopInfo(
    val stopId: String,
    val stopName: String,
    val stopLoc: LatLon,
    val wheelchairBoarding: Boolean
)

@Serializable
@Immutable
data class BusStopTimesInfo(
    val fakeId: Long, // Unique ID for Lazy columns.
    val tripId: String,
    // In seconds since beginning of day (for ordering and to handle after 24:00 time).
    val departureTime: Int,
    val arrivalTime: Int,
    val sequence: Int,
) {
    fun formatGtfsTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
    fun formatArrivalTime(): String = formatGtfsTime(arrivalTime)
    fun formatDepartureTime(): String = formatGtfsTime(departureTime)
    companion object {
        fun parseGtfsTime(time: String): Int {
            val parts = time.split(":")
            return parts[0].toInt() * 3600 +
                    parts[1].toInt() * 60 +
                    parts[2].toInt()
        }
    }
}

@Serializable
@Immutable
data class BusTripInfo(
    val routeId: String,
    val serviceId: String,
    val tripId: String,
    val tripHeadsign: String
)

class GtfsStaticRepository(
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
                    if (serverTimestamp != null && timestamp >= serverTimestamp) {
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

                // Write timestamp after all done.
                fileRepository.writeFile("timestamp", newTimestamp.toString())

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

    fun parseCols(line: String, colCount: Int): Array<String> {
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


    suspend fun getStops(): List<BusStopInfo> {
        val fileStr: String = fileRepository.readFile("stops.txt") ?: return emptyList()

        return fileStr.trimEnd().lines().drop(1).map {
            val lineArray: Array<String> = parseCols(it, 7)
            val busStopInfo = BusStopInfo(
                stopId = lineArray[0] ,
                stopName = lineArray[1] ,
                stopLoc = LatLon(lineArray[2].toDouble(), lineArray[3].toDouble()),
                wheelchairBoarding = when(lineArray[6]) {
                    "1" -> true
                    else -> false
                }
            )

            return@map busStopInfo
        }
    }

    // This is now lightning fast
    suspend fun getAssociatedTrips(busStopInfo: BusStopInfo, time: LocalDateTime): Pair<List<BusStopTimesRecord>, Int> {
        Log.d("GTFS", "Started getAssociatedTrips")
        val nowDay = time.dayOfWeek
        val nowTime = time.toLocalTime()
        val absPath = fileRepository.directory.absolutePath
        val fileStr: String = fileRepository.runShellCommand(
            "sh",
            "-c",
            """grep "\"${busStopInfo.stopId}\"" $absPath/stop_times.txt | awk -F, '$4 == "\"${busStopInfo.stopId}\""'"""
            )
        val routesCache = ConcurrentHashMap<String, String>()
        val serviceCache = ConcurrentHashMap<String, String>()

        val entities: List<BusStopTimesRecord> = coroutineScope {
            fileStr.trimEnd().lines().mapIndexed { i, it ->
                async {
                    val stopLineArray: Array<String> = parseCols(it, 5)
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
                        val routeLineArray: Array<String> = parseCols(routeFileStr.trimEnd(), 4)
                        val serviceLineArray: Array<String> = parseCols(serviceIdStr.trimEnd(), 10)

                        val result = BusStopTimesRecord(
                           fakeId = i,
                           stopTimesInfo = BusStopTimesInfo(
                               fakeId = i.toLong(),
                               tripId = stopLineArray[0],
                               departureTime = parseGtfsTime(stopLineArray[1]),
                               arrivalTime = parseGtfsTime(stopLineArray[2]),
                               sequence = stopLineArray[4].toInt()
                           ),
                           stopInfo = busStopInfo,
                           tripInfo = BusTripInfo(
                               routeId = tripLineArray[0],
                               serviceId = tripLineArray[1],
                               tripId = tripLineArray[2],
                               tripHeadsign = tripLineArray[4]
                           ),
                           routeInfo = BusRouteInfo(
                               routeId = routeLineArray[0],
                               routeShortName = routeLineArray[2],
                               routeLongName = routeLineArray[3]
                           ),
                           calendarInfo = BusCalenderInfo(
                               monday = serviceLineArray[0] != "",
                               tuesday = serviceLineArray[1] != "",
                               wednesday = serviceLineArray[2] != "",
                               thursday = serviceLineArray[3] != "",
                               friday = serviceLineArray[4] != "",
                               saturday = serviceLineArray[5] != "",
                               sunday = serviceLineArray[6] != ""
                           )
                        )

                        return@map result
                    }
                }
            }.awaitAll().flatten()
        }
        .filter {
            when(nowDay) {
                DayOfWeek.MONDAY -> it.calendarInfo.monday
                DayOfWeek.TUESDAY -> it.calendarInfo.tuesday
                DayOfWeek.WEDNESDAY -> it.calendarInfo.wednesday
                DayOfWeek.THURSDAY -> it.calendarInfo.thursday
                DayOfWeek.FRIDAY -> it.calendarInfo.friday
                DayOfWeek.SATURDAY -> it.calendarInfo.saturday
                DayOfWeek.SUNDAY -> it.calendarInfo.sunday
                else -> false
            }
        }

        val sortedList = entities.sortedWith(compareBy {it.stopTimesInfo.arrivalTime})
        val prefix = sortedList.indexOfFirst { it.stopTimesInfo.arrivalTime > nowTime.toSecondOfDay()}

        return Pair(sortedList, if (prefix == -1) 0 else prefix)
    }

    suspend fun getByTrip(busStopInfo: BusStopTimesRecord): List<BusStopTimesRecord> = withContext(Dispatchers.IO) {
        Log.d("GTFS", "Started getByTrip")
        val absPath = fileRepository.directory.absolutePath
        val tripId = busStopInfo.tripInfo.tripId
        val stopTimesFileStrDeferred = async {
            fileRepository.runShellCommand(
                "sh",
                "-c",
                """grep "\"$tripId\"" $absPath/stop_times.txt | awk -F, '$1 == "\"$tripId\""'"""
            )
        }

        return@withContext stopTimesFileStrDeferred
            .await()
            .trimEnd()
            .lines()
            .mapIndexed { i, it ->
                async {
                    val stopTimesArray = parseCols(it, 5)
                    val stopId = stopTimesArray[3]

                    Log.d("GTFS", it)
                    val stopFileStrDeferred = async {
                        fileRepository.runShellCommand(
                            "sh",
                            "-c",
                            """grep "\"$stopId\"" $absPath/stops.txt | awk -F, '$1 == "\"$stopId\""'"""
                        )
                    }
                    val stopLineArray = parseCols(stopFileStrDeferred.await(), 7)

                    return@async BusStopTimesRecord(
                        fakeId = i,
                        stopTimesInfo = BusStopTimesInfo(
                            fakeId = i.toLong(),
                            tripId = busStopInfo.tripInfo.tripId,
                            departureTime = parseGtfsTime(stopTimesArray[2]),
                            arrivalTime = parseGtfsTime(stopTimesArray[1]),
                            sequence = stopTimesArray[4].toInt()
                        ),
                        stopInfo = BusStopInfo(
                            stopId = stopLineArray[0],
                            stopName = stopLineArray[1],
                            stopLoc = LatLon(stopLineArray[2].toDouble(), stopLineArray[3].toDouble()),
                            wheelchairBoarding = when(stopLineArray[6]) {
                                "1" -> true
                                else -> false
                            }
                        ),
                        tripInfo = busStopInfo.tripInfo,
                        routeInfo = busStopInfo.routeInfo,
                        calendarInfo = busStopInfo.calendarInfo
                    )
                }
            }
        .awaitAll()
        .sortedBy { it.stopTimesInfo.sequence }
    }
}
