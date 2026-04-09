package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.LoadingScreen
import com.example.learning.repos.BusStopTimesRecord
import com.example.learning.AppViewModelProvider
import com.example.learning.TripsViewModel

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
                            Text(busStopTimesRecord.routeInfo.routeId, modifier = Modifier.align(Alignment.CenterStart))
                            Text(busStopTimesRecord.tripInfo.tripId, modifier = Modifier.align(Alignment.Center))
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
                            Text(item.stopTimesInfo.departureTime.toString(), modifier = Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
            }
        }
    }
}

