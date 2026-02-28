package com.example.learning.repos

import android.content.Context
import androidx.room.Room
import com.example.learning.database.AppDatabase
import com.example.learning.database.GtfsStaticRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class ApplicationRepos(private val applicationContext: Context) {
    val database = Room.databaseBuilder(
        applicationContext,
        AppDatabase::class.java, "bus-stuff"
    ).build()
    val locationRepo = LocationRepository(applicationContext)
    val fileRepository = FileRepository(applicationContext, "busStops")
    val httpClient = OkHttpClient()
    val gtfsStaticRepository = GtfsStaticRepository(
        database = database,
        fileRepository = fileRepository,
        httpClient = httpClient
    )
    val isLoaded = MutableStateFlow(false)

    suspend fun initAll() {
        withContext(Dispatchers.Default) {
            val job1 = async { gtfsStaticRepository.updateBusStopData() }
//            val job2 = async { busResource.init(applicationContext) }

            job1.await()
//            job2.await()
            isLoaded.value = true
        }
    }
}
