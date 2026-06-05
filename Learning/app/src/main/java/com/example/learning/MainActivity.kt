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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeCompilerApi
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.learning.ui.FilterScreen
import com.example.learning.ui.HOMEScreen
import com.example.learning.ui.PickStopScreen
import com.example.learning.ui.TripsScreen
import com.example.learning.ui.WonderHeader
import com.example.learning.ui.theme.TfNSWTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Serializable
data object Home

@Serializable
data class Trips(val tripId: String, val stopId: String, val date: String)

@Serializable
data object PickStop

@Serializable
data object Filter

fun NavController.resetTo(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            inclusive = true
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}

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
        val app = application as LearningApplication

        // Request permissions on startup
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        enableEdgeToEdge()
        setContent {
            TfNSWTheme {
                val loadError by app.repos.loadError.collectAsStateWithLifecycle()
                if (loadError != null) {
                    LoadingScreen("Transit data unavailable.\n\nThe schedule database could not be loaded. Please reinstall the app or wait for an automatic data update.\n\nDetail: $loadError")
                    return@TfNSWTheme
                }

                val loaded by app.repos.loaded.collectAsStateWithLifecycle()
                if (!loaded) {
                    LoadingScreen("Loading...")
                    return@TfNSWTheme
                }

                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                Scaffold(
                    topBar = {WonderHeader()},
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, "Home") },
                                label = { Text("Home") },
                                selected = currentDestination?.hasRoute<Home>() == true,
                                onClick = { navController.resetTo(Home) }
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                            contentWindowInsets = WindowInsets.statusBars, // top only
                ) { padding ->
                    HomeNavHost(
                        navController = navController,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}


@Composable
fun HomeNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    Log.d("INIT", "Home screen loaded.")
    val slideDuration = 300
    val blinkDuration = 150
    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = modifier,
        enterTransition = { fadeIn(tween(blinkDuration)) },
//        exitTransition = { fadeOut(tween(blinkDuration)) },
//        popEnterTransition = { fadeIn(tween(blinkDuration)) },
//        popExitTransition = { fadeOut(tween(blinkDuration)) }
    ) {
        composable<Home>(
            // Match the Trips/PickStop slide-in duration so Home's fade-out ends exactly when the
            // incoming screen finishes sliding. Without this, Home inherits the NavHost-default
            // fadeOut(700ms), which lingers ~400ms after the 300ms slide has already settled.
            exitTransition = { fadeOut(tween(slideDuration)) },
            popEnterTransition = { fadeIn(tween(slideDuration)) },
        ) { HOMEScreen(navController) }

        composable<Trips>(
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(slideDuration)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(slideDuration)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(slideDuration)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(slideDuration)
                )
            }
        ) {
            TripsScreen(navController)
        }

        composable<PickStop>(
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(slideDuration)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(slideDuration)
                )
            }
        ) {
            PickStopScreen(navController)
        }

        composable<Filter>(
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(slideDuration)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(slideDuration)
                )
            }
        ) {
            FilterScreen(navController)
        }
    }

}

@Composable
fun LoadingScreen(loadingTxt: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
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

fun printTime(timeToFormat: LocalDateTime, currentTime: LocalDate = LocalDate.now()): String {
    val timeStr = timeToFormat.format(DateTimeFormatter.ofPattern("h:mm a"))

    return when {
        timeToFormat.toLocalDate() == currentTime -> timeStr
        timeToFormat.toLocalDate() == currentTime.plusDays(1) -> "Tomorrow $timeStr"
        timeToFormat.toLocalDate() < currentTime.plusWeeks(1) -> "${
            timeToFormat.dayOfWeek.getDisplayName(
                TextStyle.SHORT, Locale.getDefault()
            )
        } $timeStr"

        else -> "${timeToFormat.format(DateTimeFormatter.ofPattern("dd/MM"))} $timeStr"
    }
}

@Composable
fun BackHeader(
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(24.dp)
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground // or onBackground, onPrimary, etc.
            )
        }
    }
}
