package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.example.learning.printTime

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
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
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
                    Column(
                        modifier = Modifier
                            .heightIn(128.dp)
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            routeShortName,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            routeLongName,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                items(
                    items = stopTimesByTrip,
                    key = { it.stopTimesInfo.sequence }
                ) { item ->
                    val (dynamicContainer, onDynamicContainer) = when (item.stopInfo.stopId == busStopTimesRecord.stopInfo.stopId) {
                        true -> MaterialTheme.colorScheme.secondaryContainer to
                                MaterialTheme.colorScheme.onSecondaryContainer

                        false -> MaterialTheme.colorScheme.surfaceContainerHigh to
                                MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(dynamicContainer)
                            .padding(16.dp)
                        ) {
//                            Text(
//                                item.stopTimesInfo.sequence.toString(),
//                                modifier = Modifier.align(Alignment.TopStart)
//                            )
                            Text(
                                item.stopInfo.stopName,
                                modifier = Modifier.align(Alignment.TopStart),
                                style = MaterialTheme.typography.bodyMedium,
                                color = onDynamicContainer
                            )
                            Text(
                                printTime(item.stopTimesInfo.departureTime),
                                modifier = Modifier.align(Alignment.TopEnd),
                                style = MaterialTheme.typography.bodyMedium,
                                color = onDynamicContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

