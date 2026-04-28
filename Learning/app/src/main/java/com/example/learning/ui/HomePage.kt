package com.example.learning.ui

import android.util.Log
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.HomeViewModel
import com.example.learning.LoadingScreen
import com.example.learning.PickStop
import com.example.learning.RealtimeBusStopTimesRecord
import com.example.learning.Trips
import com.example.learning.printTime
import com.example.learning.repos.BusStopInfo
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val focusedBusStop by viewModel.focusedBusStop.collectAsStateWithLifecycle()
    val associatedBusStopTimes by viewModel.associatedStopTimes.collectAsStateWithLifecycle()
    val isAppReady by viewModel.isUpToDate.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            // If BypassHeader is simple, you can keep it as-is here.
            // If you want M3 styling/scroll behavior, use TopAppBar/CenterAlignedTopAppBar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars) // back in
                    .height(48.dp)
                    .padding(horizontal = 16.dp)
            ) {
                BypassHeader(isAppReady)
            }
        },
        floatingActionButton = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                focusedBusStop?.let { SaveStop { viewModel.addSavedStop(it) } }
                EditStop { navController.navigate(PickStop) }
            }
        },
        contentWindowInsets = WindowInsets.statusBars, // top only
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshLocation() },
            modifier = Modifier.padding(innerPadding)
        ) {
            MasterLazyColumn(
                listState,
                navController,
                focusedBusStop,
                associatedBusStopTimes
            )
        }
    }
}

@Composable
fun SaveStop(onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = { onClick() },
    ) {
        Icon(Icons.Filled.Save, "Save stop.")
    }
}


@Composable
fun EditStop(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = { onClick() },
    ) {
        Icon(Icons.Filled.Create, "Edit Stop.")
    }
}

@Composable
fun MasterLazyColumn(
    listState: LazyListState,
    navController: NavController,
    focusedBusStop: BusStopInfo?,
    associatedBusStopTimes: List<Pair<Boolean, RealtimeBusStopTimesRecord>>
) {
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

    LazyColumn(
        state = listState,
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
    ) {
        item() {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 96.dp, vertical = 16.dp),
                thickness = 2.dp,
            )
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

        if (associatedBusStopTimes.isEmpty()) {
            Log.d("Home-Page", "associatedBusStopTimes is empty: $associatedBusStopTimes.")
            item() { LoadingScreen("Loading trips...") }
        } else {
            items(
                items = associatedBusStopTimes,
                key = { it.second.busStopTimesRecord.fakeId }
            ) { item ->
                BusCard(navController, item)
            }
        }

        item {
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }
    }

}

@Composable
fun BypassHeader(isAppReady: Boolean ){
    if (!isAppReady) {
        Text(
            text = "Not ready",
            color = MaterialTheme.colorScheme.onBackground,
        )
    } else {
        Text(
            text = "Ready",
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun StopTitle(closestBusStop: BusStopInfo? ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(96.dp)
    ) {
        Text(
            text = closestBusStop?.stopName ?: "Loading local stop...",
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
fun LazyItemScope.BusCard(
    navController: NavController,
    item: Pair<Boolean, RealtimeBusStopTimesRecord>
) {
    val (isFirst, record) = item
    val realtime = record.realtimeBusInfo
    val realTimeAvailable = realtime != null

    val (dynamicContainer, onDynamicContainer) = when {
        isFirst && realTimeAvailable ->
            MaterialTheme.colorScheme.secondaryContainer to
                    MaterialTheme.colorScheme.onSecondaryContainer
        isFirst ->
            MaterialTheme.colorScheme.surfaceContainerHigh to
                    MaterialTheme.colorScheme.onSurfaceVariant
        else ->
            MaterialTheme.colorScheme.surfaceContainerLow to
                    MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateItem(
                fadeInSpec = tween(300, easing = LinearOutSlowInEasing),
                fadeOutSpec = tween(300, easing = FastOutLinearInEasing),
                placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
            )
    ) {
        ListItem(
            modifier = Modifier.clickable {
                navController.navigate(
                    Trips(
                        item.second.busStopTimesRecord.tripInfo.tripId,
                        item.second.busStopTimesRecord.stopInfo.stopId,
                        item.second.busStopTimesRecord.stopTimesInfo.departureTime.toLocalDate().toString(),
                    )
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = dynamicContainer,
                headlineColor = onDynamicContainer,
                supportingColor = onDynamicContainer,
                trailingIconColor = onDynamicContainer,
                overlineColor = onDynamicContainer
            ),
            overlineContent = {
                Text(record.busStopTimesRecord.tripInfo.tripHeadsign)
            },
            headlineContent = {
                Text(
                    record.busStopTimesRecord.routeInfo.routeShortName,
                    style = MaterialTheme.typography.bodyLarge
                        .copy(fontStyle = FontStyle.Italic)
                )
            },
            supportingContent = {
                Text(
                    if (realTimeAvailable) "${realtime.distance.roundToInt()} m away"
                    else "Untracked"
                )
            },
            trailingContent = {
                Text(
                    printTime(record.busStopTimesRecord.stopTimesInfo.departureTime),
                    style = MaterialTheme.typography.titleMedium,
                    color = onDynamicContainer
                )
            }
        )
    }
}
