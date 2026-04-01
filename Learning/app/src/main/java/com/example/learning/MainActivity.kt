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
import com.example.learning.database.BusStopInfo
import com.example.learning.database.BusStopTimesRecord
import com.example.learning.ui.theme.LearningTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.collections.take

@Serializable
data object Home

@Serializable
data object Trips

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

@Composable
fun TripsScreen(
    navController: NavController,
    busStopTimesRecord: BusStopTimesRecord,
    viewModel: TripsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val stopTimesByTrip by viewModel.busStopTimesRecord.collectAsStateWithLifecycle()
    viewModel.updateBusStopTimesRecord(busStopTimesRecord)

    if (stopTimesByTrip.isEmpty()) {
        LoadingScreen("Loading trip information...")
    } else {
        // Just get this info from the first since it should be the same for all.
        val routeShortName = stopTimesByTrip[0].routeInfo.routeShortName
        val routeLongName = stopTimesByTrip[0].routeInfo.routeLongName

        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { padding ->
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .height(200.dp)
                        .fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(routeShortName, modifier = Modifier.align(Alignment.TopStart))
                        Text(routeLongName, modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    items(
                        items = stopTimesByTrip,
                        key = { it.stopTimesInfo.sequence }
                    ) { item ->
                        Card(
                           modifier = Modifier.fillMaxSize().height(100.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                Text(item.stopTimesInfo.sequence.toString(), modifier = Modifier.align(Alignment.TopStart))
                                Text(item.stopInfo.stopName, modifier = Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.bodyMedium )
                                Text(item.stopTimesInfo.formatDepartureTime(), modifier = Modifier.align(Alignment.BottomStart))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    sharedViewModel: SharedViewModel,
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
                .padding(vertical = 16.dp)
        ) {
            CardHeader(
                focusedBusStop,
                closestBusStops,
                viewModel::updateFocusedBusStop
            )
            ArrivalsTable(sharedViewModel, associatedBusStopTimes, navController)
        }
    }
}

@Composable
fun CardHeader(
    closestBusStop: BusStopInfo?,
    allBusStops: List<BusStopInfo>,
    stopChangeCallback: (BusStopInfo) -> Unit
) {
    var expanded by remember {mutableStateOf(false)}

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
//            .background(MaterialTheme.colorScheme.primaryContainer)
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxSize()
        ) {
            Text(
                text = closestBusStop?.stopName ?: "Loading\nlocal\nstop...",
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.displayMedium,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allBusStops.take(10).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.stopName) },
                    onClick = { expanded = false; stopChangeCallback(option) }
                )
            }
        }
    }
}

@Composable
fun ArrivalsTable(
    sharedViewModel: SharedViewModel,
    associatedBusStopTimes: List<BusStopTimesRecord>,
    navController: NavController
) {
    val listState = rememberLazyListState()

    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (associatedBusStopTimes.isEmpty()) {
            LoadingScreen("Loading trips...")
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxSize()
            ) {
//                stickyHeader() {
//                    Row(
//                        horizontalArrangement = Arrangement.SpaceBetween,
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .height(50.dp)
//                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
//                            .padding(8.dp)
//                    ) {
//                        Text("Time", style = MaterialTheme.typography.titleMedium)
//                        Text("Bus", style = MaterialTheme.typography.titleMedium)
//                    }
//                }
                items(
                    items = associatedBusStopTimes,
                    key = { it.fakeId }
                ) { item ->
                    BusCard(navController, sharedViewModel, item)
                }
            }
        }
    }
}

@Composable
fun BusCard(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    item: BusStopTimesRecord
) {
    val arrivalTime: String = item.stopTimesInfo.formatArrivalTime()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable {
                sharedViewModel.select(item)
                navController.navigate(Trips)
            }
            .padding(16.dp)
    ) {
        Text(
            text = item.routeInfo.routeShortName,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = item.tripInfo.tripHeadsign,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomStart)
        )
        Text(
            text = arrivalTime,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
