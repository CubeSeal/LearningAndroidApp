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
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.learning.database.BusStopInfoEntity
import com.example.learning.database.ScheduledStopTimesInfo
import com.example.learning.ui.theme.LearningTheme
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

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

                if (!isAppReady){
                    LoadingScreen()
                } else {
                    Log.d("INIT", "Home screen loaded.")
                    HOMEScreen()
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
fun HOMEScreen(
    // The Magic: We set the default value to use our global Factory
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
            ArrivalsTable(associatedBusStopTimes)
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
        modifier = Modifier
            .height(75.dp)
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
                style = MaterialTheme.typography.titleMedium
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
    associatedBusStopTimes: List<ScheduledStopTimesInfo>
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
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
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                item() {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Time", style = MaterialTheme.typography.titleSmall)
                        Text("Bus", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(
                    items = associatedBusStopTimes,
                    key = { it.id }
                ) { item ->
                    BusCard(item)
                }
            }
        }
    }
}

@Composable
fun BusCard(
    item: ScheduledStopTimesInfo
) {
    val dayTime: String = item.departureTime.time.toString() +
        "-" +
        item.departureTime.day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val busId: String = item.routeShortName + "-" +item.tripHeadsign

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(dayTime, style = MaterialTheme.typography.bodySmall)
        Text(busId, style = MaterialTheme.typography.bodySmall)
    }
}
