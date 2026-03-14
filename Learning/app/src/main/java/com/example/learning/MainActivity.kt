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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.learning.database.BusStopInfoEntity
import com.example.learning.database.ScheduledStopTimesInfo
import com.example.learning.ui.theme.LearningTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.format.TextStyle
import java.util.Locale

@Serializable
data object Home

@Serializable
data class Trips(val tripId: String)

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
            LearningTheme(dynamicColor = false) {
                val app = application as LearningApplication
                val isAppReady by app.repos
                    .isLoaded
                    .collectAsStateWithLifecycle()
                val navController = rememberNavController()

                if (!isAppReady){
                    LoadingScreen()
                } else {
                    Log.d("INIT", "Home screen loaded.")
                    NavHost(
                        navController = navController,
                        startDestination = Home,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
                        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
                        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) }
                    ) {
                        composable<Home> {
                            HOMEScreen(navController)
                        }
                        composable<Trips> { backStackEntry ->
                            val route: Trips = backStackEntry.toRoute()
                            TripsScreen(navController, route.tripId)
                        }
                    }
                }
            }
        }


    }
}

@Composable
fun LoadingScreen() {
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
                text = "Downloading Transport Data...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun TripsScreen(
    navController: NavController,
    tripId: String,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(tripId)
    }
}

@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val closestBusStops by viewModel.closestBusStops.collectAsStateWithLifecycle()
    val focusedBusStop by viewModel.focusedBusStop.collectAsStateWithLifecycle()
    val associatedBusStopTimes by viewModel.associatedStopTimes.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            CardHeader(
                focusedBusStop,
                closestBusStops,
                viewModel::updateFocusedBusStop
            )
            ArrivalsTable(associatedBusStopTimes, navController)
        }
    }
}

@Composable
fun CardHeader(
    closestBusStop: BusStopInfoEntity?,
    allBusStops: List<BusStopInfoEntity>,
    stopChangeCallback: (BusStopInfoEntity) -> Unit
) {
    var expanded by remember {mutableStateOf(false)}

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = Modifier
            .height(50.dp)
            .fillMaxWidth()
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxSize()
        ) {
            Text(
                text = closestBusStop?.name ?: "Loading local stop...",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allBusStops.take(10).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = { expanded = false; stopChangeCallback(option) }
                )
            }
        }
    }
}

@Composable
fun ArrivalsTable(
    associatedBusStopTimes: List<ScheduledStopTimesInfo>,
    navController: NavController
) {
    val listState = rememberLazyListState()

    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
    ) {
        if (associatedBusStopTimes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Loading trips...", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                stickyHeader() {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp)
                    ) {
                        Text("Time", style = MaterialTheme.typography.titleSmall)
                        Text("Bus", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(
                    items = associatedBusStopTimes,
                    key = { it.id }
                ) { item ->
                    BusCard(navController, item)
                }
            }
        }
    }
}

@Composable
fun BusCard(
    navController: NavController,
    item: ScheduledStopTimesInfo
) {
    val dayTime: String = item.departureTime.time.toString() +
        "-" +
        item.departureTime.day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val busId: String = item.routeShortName + "-" +item.tripHeadsign

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navController.navigate(Trips(item.tripId))}
    ) {
        Text(dayTime, style = MaterialTheme.typography.bodySmall)
        Text(busId, style = MaterialTheme.typography.bodySmall)
    }
}
