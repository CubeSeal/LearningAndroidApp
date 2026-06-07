package com.example.learning

import android.Manifest
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.learning.db.GtfsDatabase
import com.example.learning.repos.FakeFileRepository
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.FileRepository
import com.example.learning.repos.FileSource
import com.example.learning.repos.GTFS_GH_OWNER
import com.example.learning.repos.GTFS_GH_REPO
import com.example.learning.repos.GtfsRealtimeRepository
import com.example.learning.repos.GtfsStaticRepository
import com.example.learning.repos.GtfsValidation
import com.example.learning.repos.LocationRepository
import com.example.learning.repos.LocationSource
import com.example.learning.repos.RealtimeGtfsSource
import com.example.learning.repos.SettingsRepository
import com.example.learning.repos.SettingsSource
import com.example.learning.repos.StaticGtfsSource
import com.example.learning.repos.bootstrapGtfs
import com.example.learning.repos.validateGtfsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

// Basic Interface Container for Application Repos
interface AppContainer {
    val fileRepository: FileSource
    val locationRepo: LocationSource
    val settingsRepo: SettingsSource
    val gtfsRealtimeRepository: RealtimeGtfsSource
    val gtfsStaticRepository: StaticGtfsSource
    val busInfo: BusInfo
    val loaded: StateFlow<Boolean>
    val loadError: StateFlow<String?>
    suspend fun initAll()
}

class ApplicationRepos(private val applicationContext: Context) : AppContainer {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = OkHttpClient()

    override val locationRepo = LocationRepository(applicationContext, applicationScope)
    override val fileRepository = FileRepository(applicationContext, "busStops")
    override val settingsRepo = SettingsRepository(applicationContext)
    override val gtfsRealtimeRepository = GtfsRealtimeRepository(httpClient = httpClient)
    override val gtfsStaticRepository =  GtfsStaticRepository(applicationContext, fileRepository, httpClient)

    override val loaded = MutableStateFlow(false)
    override val loadError = MutableStateFlow<String?>(null)


    override val busInfo by lazy {
        BusInfo(
            gtfsStaticRepository = gtfsStaticRepository,
            gtfsRealtimeRepository = gtfsRealtimeRepository,
            locationRepo = locationRepo,
            settingsRepo = settingsRepo,
            scope = applicationScope
        )
    }

    override suspend fun initAll() {
        withContext(Dispatchers.Default) {
            Log.d("INIT", "Start loading...")

            locationRepo.onPermissionGranted()

            // Provision then validate: the DB is never bundled, so on a missing/invalid DB we
            // force-download the latest release before validating. Validating first would deadlock
            // the cold-start bootstrap (an empty DB can never become valid without a sync).
            val ctx = applicationContext
            val outcome = bootstrapGtfs(
                validate = { validateGtfsDb(GtfsDatabase.getInstance(ctx)) },
                sync = {
                    gtfsStaticRepository.syncGtfsDatabase(
                        ghOwner = GTFS_GH_OWNER,
                        ghRepo = GTFS_GH_REPO,
                        force = true,
                    )
                },
            )
            if (outcome is GtfsValidation.Invalid) {
                Log.e("INIT", "DB bootstrap failed: ${outcome.reason}")
                loadError.update { outcome.reason }
                return@withContext
            }

            Log.d("INIT", "Finished loading.")
        }

        if (loadError.value == null) loaded.update { true }
    }
}

enum class LoadState {
    StartLoaded,
    DelayedLoad,
    NeverLoad
}

class FakeAppContainer(
    override val gtfsStaticRepository: StaticGtfsSource = FakeStaticGtfsSource(),
    override val locationRepo: LocationSource = FakeLocationSource(),
    override val gtfsRealtimeRepository: RealtimeGtfsSource = FakeRealtimeSource(),
    override val settingsRepo: SettingsSource = FakeSettingsSource(),
    override val fileRepository: FileSource = FakeFileRepository(emptyMap(), "fileDirectory"),
    val loadBehaviour: LoadState = LoadState.StartLoaded
) : AppContainer {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val loaded = MutableStateFlow(loadBehaviour == LoadState.StartLoaded)
    override val loadError = MutableStateFlow<String?>(null)
    override val busInfo by lazy {
        BusInfo(
            gtfsStaticRepository,
            gtfsRealtimeRepository,
            locationRepo,
            settingsRepo,
            applicationScope
        )
    }

    override suspend fun initAll() {
        if (loadError.value == null && loadBehaviour != LoadState.NeverLoad) loaded.update { true }
    }
}

open class LearningApplication : Application() {
    // This holds the data layer
    lateinit var repos: AppContainer

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()
        // Initialise the App repos with the production instance.
        repos = ApplicationRepos(this)
    }
}