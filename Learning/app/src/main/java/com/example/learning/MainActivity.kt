package com.example.learning

// The main navigation components

// Helper for finding the start destination in the graph
import android.Manifest
import android.os.Bundle
import android.text.TextUtils.replace
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.learning.database.BusStopInfo
import com.example.learning.database.BusStopTimesRecord
import com.example.learning.ui.theme.TfNSWTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.collections.take

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickStopScreen(
    navController: NavController,
    viewModel: PickStopViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val searchCallback = fun(busStop: BusStopInfo) {
        viewModel.updateFocusedBusStop(busStop)
        navController.popBackStack()
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filteredStops by viewModel.filteredBusStops.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    )
    {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(50.dp)
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground // or onBackground, onPrimary, etc.
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .semantics { isTraversalGroup = true }
        ) {
            SearchBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .semantics { traversalIndex = 0f },
                inputField = {
                    // Customizable input field implementation
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { viewModel.onQueryChange(it) },
                        onSearch = {
                            filteredStops.firstOrNull()?.let {
                                searchCallback(it)
                            }
                            expanded = false
                        },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        trailingIcon = null
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                // Show search results in a lazy column for better performance
                LazyColumn {
                    items(
                        items = filteredStops,
                        key = { it.stopId }
                    ) { busStop ->
                        ListItem(
                            headlineContent = { Text(busStop.stopName) },
                            supportingContent = null,
                            leadingContent = null,
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier
                                .clickable {
                                    searchCallback(busStop)
                                    expanded = false
                                }
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
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

        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
            ) {
                item() {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .height(50.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        IconButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.align(Alignment.BottomStart)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground // or onBackground, onPrimary, etc.
                            )
                        }
                    }
                }
                item() {
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
                }
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

@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    sharedViewModel: SharedViewModel,
) {
    val closestBusStops by viewModel.closestBusStops.collectAsStateWithLifecycle()
    val focusedBusStop by viewModel.focusedBusStop.collectAsStateWithLifecycle()
    val associatedBusStopTimes by viewModel.associatedStopTimes.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val headerAlpha by remember {
        derivedStateOf {
            // If we've scrolled past the first item, it's completely invisible
            if (listState.firstVisibleItemIndex > 1) {
                0f
            } else {
                // How fast it fades out (in pixels). Tweak this number to your liking!
                val fadeDistance = 400f
                val offset = listState.firstVisibleItemScrollOffset
                // Calculate opacity: 1f (fully visible) down to 0f (invisible)
                (1f - (offset / fadeDistance)).coerceIn(0f, 1f)
            }
        }
    }
    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
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
                item() {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .graphicsLayer { alpha = headerAlpha }
                            .height(50.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Create,
                            "Edit Stop.",
                            tint = MaterialTheme.colorScheme.onBackground, // or onBackground, onPrimary, etc.
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(24.dp)
                                .clickable {
                                    navController.navigate(PickStop)
                                }
                        )
                    }
                }

                item() {
                    Box(
                        modifier = Modifier
                            .graphicsLayer { alpha = headerAlpha }
                            .padding(16.dp)
                    ) {
                        StopTitle(
                            focusedBusStop,
                            closestBusStops,
                            viewModel::updateFocusedBusStop
                        )
                    }
                }

                items(
                    items = associatedBusStopTimes,
                    key = { it.fakeId }
                ) { item ->
                    BusCard(navController, sharedViewModel, item)
                }

                item {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
                }
            }
        }
    }
}

@Composable
fun StopTitle(
    closestBusStop: BusStopInfo?,
    allBusStops: List<BusStopInfo>,
    stopChangeCallback: (BusStopInfo) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(100.dp)
    ) {
        Text(
            text = closestBusStop?.stopName ?: "Loading\nlocal\nstop...",
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.displayMedium,
        )
    }
}

@Composable
fun BusCard(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    item: BusStopTimesRecord
) {
    val arrivalTime: String = item.stopTimesInfo.formatArrivalTime()

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
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
}
