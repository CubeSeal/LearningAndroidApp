package com.example.learning

// The main navigation components

// Helper for finding the start destination in the graph

import android.Manifest
import android.net.http.SslCertificate.saveState
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.learning.ui.HOMEScreen
import com.example.learning.ui.PickStopScreen
import com.example.learning.ui.SavedStopsScreen
import com.example.learning.ui.TripsScreen
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
data object SavedStops

@Serializable
data class Trips(val tripId: String, val stopId: String, val date: String)

@Serializable
data object PickStop

private fun NavController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
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
                val loaded by app.repos.loaded.collectAsStateWithLifecycle()
                if (!loaded) {
                    LoadingScreen("Loading...")
                    return@TfNSWTheme
                }

                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                val isHome = currentDestination?.hasRoute<Home>() == true
                val isSaved = currentDestination?.hasRoute<SavedStops>() == true
                val showSuite = isHome || isSaved

                Scaffold(
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showSuite,
                            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
                            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
                        ) {
                            NavigationBar {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, "Home") },
                                    label = { Text("Home") },
                                    selected = isHome,
                                    onClick = { navController.navigateTopLevel(Home) }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Bookmark, "Saved") },
                                    label = { Text("Saved") },
                                    selected = isSaved,
                                    onClick = { navController.navigateTopLevel(SavedStops) }
                                )
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
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
    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                tween(300)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                tween(300)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(300)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(300)
            )
        }
    ) {
        composable<Home> { HOMEScreen(navController) }
        composable<SavedStops> { SavedStopsScreen(navController) }
        composable<Trips> { TripsScreen(navController) }
        composable<PickStop> { PickStopScreen(navController) }
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

