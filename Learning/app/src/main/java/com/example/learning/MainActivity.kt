package com.example.learning

// The main navigation components

// Helper for finding the start destination in the graph
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learning.database.BusStopInfo
import com.example.learning.database.ScheduledStopTimesInfo
import com.example.learning.ui.theme.LearningTheme
import kotlinx.coroutines.launch


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
//                val app = application as LearningApplication
//                val isAppReady by app.repos
//                    .isLoaded
//                    .collectAsStateWithLifecycle()

                HOMEScreen()
            }
        }


    }
}

@Composable
fun HOMEScreen(
    // The Magic: We set the default value to use our global Factory
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val closestBusStops by viewModel.closestBusStops.collectAsStateWithLifecycle()
    val focusedBusStop by viewModel.focusedBusStop.collectAsStateWithLifecycle()
    val associatedBusStopTimes by viewModel.associatedStopTimes.collectAsStateWithLifecycle()

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
                busStopInfo = focusedBusStop,
                busStopsDropdown = closestBusStops,
                associatedBusStopTimes = associatedBusStopTimes,
                stopChangeCallback = viewModel::updateFocusedBusStop
            )
        }
    }
}

@Composable
fun NearestStopCard(
    busStopInfo: BusStopInfo?,
    busStopsDropdown: List<BusStopInfo>,
    associatedBusStopTimes: List<ScheduledStopTimesInfo>,
    stopChangeCallback: (BusStopInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .height(500.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF00B5EF))
            .padding(16.dp)
    ) {
        CardHeader(busStopInfo, busStopsDropdown, stopChangeCallback)

        ArrivalsTable(associatedBusStopTimes)
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
            .clip(RoundedCornerShape(10.dp))
            .padding(2.dp)
            .background(Color.Gray)
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = closestBusStop?.name ?: "Loading...",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allBusStops.take(100).forEach { option ->
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
    associatedBusStopTimes: List<ScheduledStopTimesInfo>
) {
    val colWeight1 = 0.25f
    val colWeight2 = 0.25f
    val colWeight3 = 0.5f

    Row {
        Text("Day", Modifier.weight(colWeight3))
        Text("Time", Modifier.weight(colWeight1))
        Text("Name", Modifier.weight(colWeight2))
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(
            items = associatedBusStopTimes,
            key = { it.id }
        ) { item ->
            Row {
                Text(item.arrivalTime.day.toString(), Modifier.weight(colWeight3))
                Text(item.departureTime.time.toString(), Modifier.weight(colWeight1))
                Text(item.routeShortName, Modifier.weight(colWeight2))
            }
        }
    }
}
