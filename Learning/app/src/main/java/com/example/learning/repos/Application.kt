package com.example.learning.repos

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.example.learning.BusResource
import com.example.learning.BusStopsResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class ApplicationRepos(private val applicationContext: Context) {
    val locationRepo = LocationRepository(applicationContext)
    val fileRepository = FileRepository(applicationContext, "busStops")
    val httpClient = OkHttpClient()
    val busStopsResource by lazy {BusStopsResource(locationRepo, fileRepository, httpClient)}
    val busResource by lazy {BusResource(locationRepo, httpClient)}
    val isLoaded = MutableStateFlow(false)

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun initAll() {
        withContext(Dispatchers.Default) {
            val job1 = async { busStopsResource.init() }
            val job2 = async { busResource.init(applicationContext) }

            job1.await()
            job2.await()
            isLoaded.value = true
        }
    }
}
