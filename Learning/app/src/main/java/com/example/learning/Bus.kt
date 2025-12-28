package com.example.learning

import android.Manifest
import android.content.Context
import android.location.Location
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.example.learning.repos.FileRepository
import com.example.learning.repos.LocationRepository
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.google.protobuf.CodedInputStream
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeSet
import java.util.zip.ZipInputStream

@Immutable
data class BusInfo(
    val id: String,
    val vehicleId: String,
    val licencePlate: String,
    val routeId: String,
    val distance: Double,
    val timestamp: Long
)


class BusResource(
    private val locationRepo: LocationRepository,
    private val httpClient: OkHttpClient
) {
    private val gtfsUrl = "https://api.transport.nsw.gov.au/v1/gtfs/vehiclepos/buses"
    private val apiKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiJPOExUTzJkbGJhTmZiZkpDR2VUTDlyMDd3Yk9WVE9LbFFiclN0eDdFUnA4IiwiaWF0IjoxNzY2ODM1ODU1fQ.cdCzNDKLb5eoA1r57iuUAx-aNVSXAdlXS1rIuTm0Q2I"
    private val request =
        Request.Builder().url(gtfsUrl).header("Authorization", value = "apikey $apiKey").build()
    var location: Location? = null

    // TreeSet automatically maintains sorted order!
    private val closestBuses = TreeSet<BusInfo>(
        compareBy<BusInfo> { it.distance }  // Sort by distance
            .thenBy { it.id }                // Break ties with unique ID
    )
    private val maxSize = 100

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun init(context: Context) {
        location = locationRepo.getLastLocation()
        getBusData()
    }

    suspend fun getBusData(): Unit = withContext(Dispatchers.IO) {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed: $response")
        val stream = response.body?.byteStream()
            ?: throw IOException("Could not get bytestream for body: $response")

        val cis = CodedInputStream.newInstance(stream)

        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            if (tag == 0) break

            val fieldNumber = tag ushr 3

            if (fieldNumber == 2) {
                val length = cis.readRawVarint32()
                val oldLimit = cis.pushLimit(length)
                val entity = FeedEntity.parseFrom(cis)
                val bus = convertToBusInfo(entity) ?: break

                // Keep only closest 100
                closestBuses.add(bus)
                if (closestBuses.size > maxSize) {
                    closestBuses.pollLast()
                }

                cis.popLimit(oldLimit)
            } else {
                cis.skipField(tag)
            }
        }
        response.close()
    }

    private fun convertToBusInfo(entity: FeedEntity): BusInfo? {
        val vehicle = entity.vehicle
        val position = vehicle.position ?: return null
        val vehicleId = vehicle.vehicle?.id ?: return null
        val licencePlate = vehicle.vehicle?.licensePlate?: return null
        val routeId = vehicle.trip?.routeId ?: return null
        val distance = Location("bus")
            .apply {
                latitude = position.latitude.toDouble()
                longitude = position.longitude.toDouble()
            }
            .distanceTo(location!!).toDouble()

        return BusInfo(
            id = entity.id,
            vehicleId = vehicleId,
            licencePlate = licencePlate,
            routeId = routeId,
            distance = distance,
            timestamp = vehicle.timestamp
        )
    }
}

@Immutable
data class BusStopInfo(
    val id: String,
    val name: String,
    val location: Location,
    val wheelchairBoarding: Boolean
) {
    fun getDistance(currentLocation: Location): Float { return location.distanceTo(currentLocation) }
}

@Immutable
data class StopTimesInfo(
    val stopId: String,
    val tripId: String,
    val departureTime: LocalTime,
    val arrivalTime: LocalTime
)

class BusStopsResource(
    private val locationRepo: LocationRepository,
    private val fileRepository: FileRepository,
    private val httpClient: OkHttpClient
) {
    private val gtfsUrl = "https://api.transport.nsw.gov.au/v1/gtfs/schedule/buses"
    private val apiKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiJPOExUTzJkbGJhTmZiZkpDR2VUTDlyMDd3Yk9WVE9LbFFiclN0eDdFUnA4IiwiaWF0IjoxNzY2ODM1ODU1fQ.cdCzNDKLb5eoA1r57iuUAx-aNVSXAdlXS1rIuTm0Q2I"
    private val request = Request
        .Builder()
        .url(gtfsUrl)
        .header("Authorization", value = "apikey $apiKey")
        .build()
    var busStopInfo: List<BusStopInfo>? = null

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun init() {
        updateBusStopData()
        busStopInfo = getStops()
    }

    suspend fun getStops(): List<BusStopInfo> {
        return withContext(Dispatchers.IO) {
            val fileData = fileRepository.readFile("stops.txt")!!

            csvReader()
                .readAllWithHeader(fileData)
                .mapNotNull { convertToBusStopInfo(it) }
        }
    }

    private suspend fun updateBusStopData(): Boolean {
        val listOfFiles = fileRepository.listFiles()
        val requiredFiles = listOf(
            "agency.txt",
            "calendar_dates.txt",
            "calendar.txt",
            "notes.txt",
            "routes.txt",
            "shapes.txt",
            "stops.txt",
            "stop_times.txt",
            "trips.txt"
        )

        val justWipeEverythingAndStartAgain = suspend {
                listOfFiles.map{fileRepository.deleteFile(it)}

                // If this returns null then everything is already up to date so whatever.
                downloadBusStopData(null)?.let {
                    fileRepository.writeFile("timestamp", it.toString())
                }
        }

        if (requiredFiles.any {!fileRepository.fileExists(it)}) {
            justWipeEverythingAndStartAgain
        } else if (!fileRepository.fileExists("timestamp")) {
            justWipeEverythingAndStartAgain
        } else {
            val timeStampFile = fileRepository.readFile("timestamp") ?: throw IOException("Could not read timestamp file.")
            val timestamp = Instant.parse(timeStampFile)

            // If this returns null then everything is already up to date so whatever.
            downloadBusStopData(timestamp)?.let {
                fileRepository.writeFile("timestamp", it.toString())
            }
        }

        return true
    }

    private fun parseHttpDate(dateString: String): Instant {
        return ZonedDateTime.parse(dateString, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }

    private suspend fun downloadBusStopData(checkHeaderTimestamp: Instant?): Instant? {
        return withContext(Dispatchers.IO) {

            // 1. THE CHEAP CHECK (HEAD)
            // Only perform this if we have a previous timestamp.
            if (checkHeaderTimestamp != null) {
                val headRequest = request.newBuilder().head().build()

                httpClient.newCall(headRequest).execute().use { headResponse ->
                    if (!headResponse.isSuccessful) {
                        // Note: Some APIs return 404 or 405 for HEAD.
                        // If so, you might want to catch this and proceed to GET anyway.
                        throw IOException("HEAD request failed: ${headResponse.code}")
                    }

                    val lastModifiedHeader = headResponse.header("last-modified")
                    val serverTimestamp = lastModifiedHeader?.let {
                        parseHttpDate(it)
                    }

                    // If server timestamp is older or equal to what we have, STOP.
                    if (serverTimestamp != null && checkHeaderTimestamp >= serverTimestamp) {
                        return@withContext null
                    }
                }
            }

            // 2. THE HEAVY DOWNLOAD (GET)
            // We only get here if data is new or it's the first run.
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                throw IOException("GET request failed: ${response.code}")
            }

            // Capture the new timestamp to return at the end
            val newTimestamp = response.header("last-modified")?.let {
                parseHttpDate(it)
            }

            // 3. STREAMING & UNZIPPING
            try {
                val rawStream = response.body?.byteStream() ?: throw IOException("No body")

                // Critical Optimization: wrap in .buffered()
                // This reduces system calls significantly.
                rawStream.buffered().use { bufferedBody ->
                    ZipInputStream(bufferedBody).use { zipStream ->

                        var entry = zipStream.nextEntry

                        while (entry != null) {
                            if (!entry.isDirectory) {
                                // Logic to save the individual file
                                fileRepository.writeFileStream(entry.name, zipStream)
                            }
                            entry = zipStream.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                throw IOException("Error unzipping stream", e)
            } finally {
                // Ensure the connection is released back to the pool
                response.close()
            }

            return@withContext newTimestamp
        }
    }

    fun convertToBusStopInfo(row: Map<String, String>): BusStopInfo? {
        val id = row["stop_id"] ?: return null
        val name = row["stop_name"] ?: return null
        val location = Location("bus")
            .apply {
                latitude = row["stop_lat"]?.toDoubleOrNull() ?: return null
                longitude = row["stop_lon"]?.toDoubleOrNull() ?: return null
            }
        val wheelchairBoarding = when (row["wheelchair_boarding"]) {
            "1" -> true
            else -> false
        }

        return BusStopInfo(
            id = id,
            name = name,
            location = location,
            wheelchairBoarding = wheelchairBoarding
        )
    }
}
