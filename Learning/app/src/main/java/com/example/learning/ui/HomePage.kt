package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.HomeViewModel
import com.example.learning.LoadingScreen
import com.example.learning.PickStop
import com.example.learning.SharedViewModel
import com.example.learning.Trips
import com.example.learning.repos.BusStopInfo
import com.example.learning.repos.BusStopTimesRecord

@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    sharedViewModel: SharedViewModel,
) {
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
                        StopTitle(focusedBusStop)
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
    closestBusStop: BusStopInfo?
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
