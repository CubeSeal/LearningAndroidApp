package com.example.learning

// The main navigation components

// Helper for finding the start destination in the graph

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.learning.ui.HOMEScreen
import com.example.learning.ui.PickStopScreen
import com.example.learning.ui.TripsScreen
import com.example.learning.ui.theme.TfNSWTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object Home

@Serializable
data object Trips

@Serializable
data object PickStop

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                println("Location permission granted")
                // NOW initialize repos after permission is granted
                lifecycleScope.launch {
                    val app = application as LearningApplication
                    app.repos.initAll()
                }
            }
            else -> {
                println("Location permission denied")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions on startup
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        enableEdgeToEdge()
        setContent {
            TfNSWTheme() {
                val app = application as LearningApplication
                val isAppReady by app.repos
                    .isLoaded
                    .collectAsStateWithLifecycle()
                val navController = rememberNavController()

                if (!isAppReady){
                    LoadingScreen("Downloading Transport Data...")
                } else {
                    Log.d("INIT", "Home screen loaded.")
                    val sharedViewModel: SharedViewModel = viewModel()
                    NavHost(
                        navController = navController,
                        startDestination = Home,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
                        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
                        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) }
                    ) {
                        composable<Home> {
                            HOMEScreen(navController, sharedViewModel = sharedViewModel)
                        }
                        composable<Trips> {
                            TripsScreen(navController, sharedViewModel.selectedRecord!!)
                        }
                        composable<PickStop> {
                            PickStopScreen(navController)
                        }
                    }
                }
            }
        }

    }
}

@Composable
fun LoadingScreen(loadingTxt: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = loadingTxt,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}


