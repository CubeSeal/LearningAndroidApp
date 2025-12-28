package com.example.learning

// The main navigation components

// Helper for finding the start destination in the graph
import android.Manifest
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learning.ui.theme.LearningTheme
import kotlinx.coroutines.launch

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    PROFILE("Profile", Icons.Default.AccountBox),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Downloading Transport Data...")
        }
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

        // Request permissions on startup
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        enableEdgeToEdge()
        setContent {
            LearningTheme {
                val app = application as LearningApplication
                val isAppReady by app.repos.isLoaded.collectAsStateWithLifecycle()

                if (isAppReady) {
                    HOMEScreen()
                } else {
                    LoadingScreen()
                }
            }
        }


    }
}

//@Composable
//fun LearningApp() {
//    val navController = rememberNavController()
//
//    // Get the current route to determine which item is selected
//    val navBackStackEntry by navController.currentBackStackEntryAsState()
//    val currentDestination = navBackStackEntry?.destination?.route
//
//    NavigationSuiteScaffold(
//        navigationSuiteItems = {
//            AppDestinations.entries.forEach { destination ->
//                item(
//                    icon = { Icon(destination.icon, contentDescription = destination.label) },
//                    label = { Text(destination.label) },
//                    // Compare the current route string with the enum name/route
//                    selected = currentDestination == destination.name,
//                    onClick = {
//                        navController.navigate(destination.name) {
//                            // Pop up to the start destination of the graph to
//                            // avoid building up a large stack of destinations
//                            popUpTo(navController.graph.findStartDestination().id) {
//                                saveState = true
//                            }
//                            // Avoid multiple copies of the same destination
//                            launchSingleTop = true
//                            // Restore state when reselecting a previously selected item
//                            restoreState = true
//                        }
//                    }
//                )
//            }
//        }
//    ) {
//        // Place the NavHost here instead of a static Scaffold
//        NavHost(
//            navController = navController,
//            startDestination = AppDestinations.HOME.name,
//            modifier = Modifier.fillMaxSize().safeDrawingPadding()
//        ) {
//            composable(AppDestinations.HOME.name) {
//                HOMEScreen()
//            }
//            composable(AppDestinations.PROFILE.name) {
//                PROFILEScreen()
//            }
//            composable(AppDestinations.SETTINGS.name) {
//                SETTINGScreen()
//            }
//        }
//    }
//
//}

@Composable
fun HOMEScreen(
    // The Magic: We set the default value to use our global Factory
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val closestBusStop by viewModel.closestBusStop.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
                .padding(20.dp)
        ) {
            NearestStopCard(
                busStopInfo = closestBusStop,
                location = location
            )

            Text("Hello")
        }
    }
}

@Composable
fun NearestStopCard(
    busStopInfo: BusStopInfo?,
    location: Location?
) {
    if (
        busStopInfo != null && location != null
    ) {
        val distance = busStopInfo.getDistance(location)

        Column(
            modifier = Modifier
                .height(200.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF00B5EF))
                .padding(16.dp)
        ) {
            Text(
                text = "CLOSEST BUS STOP",
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = Color.White
            )
            Text(busStopInfo.name, color = Color.White)
            Text(
                text = "Distance is $distance m",
                fontStyle = FontStyle.Italic
            )
        }
    }
}

//@Composable
//fun PROFILEScreen(
//    // The Magic: We set the default value to use our global Factory
//    viewModel: SecondViewModel = viewModel(factory = AppViewModelProvider.Factory)
//) {
//    val busStopsInfo by viewModel.busStopsInfo.collectAsStateWithLifecycle()
//    val location by viewModel.location.collectAsStateWithLifecycle()
//    // Create state for the LazyColumn
//    val listState = rememberLazyListState()
//
//    // This runs once when the composable enters the composition
//    LaunchedEffect(Unit) {
//        viewModel.loadDataIfNeeded()
//    }
//
//    LaunchedEffect(busStopsInfo) {
//        if (busStopsInfo.isNotEmpty()) listState.animateScrollToItem(0)
//    }
//
//    LazyColumn(
//        state = listState,
//        modifier = Modifier.fillMaxSize()
//    ) {
//        items(
//            items = busStopsInfo,
//            key = { it.id }
//        ) { BusStopsCard(it, location) }
//    }
//}
//
//@Composable
//fun SETTINGScreen() {
//    var value by remember { mutableStateOf("Loading...") }
//
//    // This runs once when the composable enters the composition
//    LaunchedEffect(Unit) {
//        value = try {
//            "Cringe"
//        } catch (e: Exception) {
//            "Error: ${e.message}"
//        }
//    }
//
//    Text(
//        text = "Hello $value!"
//    )
//}
//
//@Composable
//fun BusCard(bus: BusInfo) {
//    Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
//        Column(modifier = Modifier.padding(16.dp)) {
//            Text(text = "Bus ID: ${bus.vehicleId}")
//            Text(text = "Route: ${bus.routeId}")
//            Text(text = "Distance from me: ${bus.distance}")
//        }
//    }
//}
//
//@Composable
//fun BusStopsCard(busStop: BusStopInfo, location: Location) {
//    val distance = busStop.getDistance(location)
//
//    Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
//        Column(modifier = Modifier.padding(16.dp)) {
//            Text(text = "Bus ID: ${busStop.id}")
//            Text(text = "Name: ${busStop.name}")
//            Text(text = "Distance from me: ${String.format(Locale.ENGLISH, "%.2f", distance)}m")
//        }
//    }
//}
