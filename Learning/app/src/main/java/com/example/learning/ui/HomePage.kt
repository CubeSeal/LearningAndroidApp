package com.example.learning.ui

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.learning.TransitFilterOptions
import com.example.learning.StopTimesRecordWithRealtime
import com.example.learning.Filter
import com.example.learning.HomeViewModel
import com.example.learning.LoadingScreen
import com.example.learning.PickStop
import com.example.learning.Trips
import com.example.learning.printTime
import com.example.learning.repos.GlobbedStopRecord
import java.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val focusedBusStop by viewModel.focusedBusStop.collectAsStateWithLifecycle()
    val associatedBusStopTimes by viewModel.associatedStopTimes.collectAsStateWithLifecycle()
    val rowFilters by viewModel.rowFilters.collectAsStateWithLifecycle()
    val selectedFiltersForBusStop by viewModel.selectedFiltersForBusStop.collectAsStateWithLifecycle()
    val hasMoreFilters by viewModel.hasMoreFilters.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    Scaffold(
        floatingActionButton = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                focusedBusStop?.let { SaveStop { viewModel.addSavedStop(it) } }
                EditStop { navController.navigate(PickStop) }
            }
        }
    )
    { _ ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() }
        ) {
            MasterLazyColumn(
                listState,
                navController,
                focusedBusStop,
                associatedBusStopTimes,
                rowFilters,
                selectedFiltersForBusStop,
                hasMoreFilters,
                { viewModel.toggleFilterForBusStops(it) },
                { navController.navigate(Filter) },
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
        Icon(Icons.Filled.Search, "Edit Stop.")
    }
}

@Composable
fun MasterLazyColumn(
    listState: LazyListState,
    navController: NavController,
    focusedBusStop: GlobbedStopRecord?,
    associatedBusStopTimes: List<Pair<Boolean, StopTimesRecordWithRealtime>>,
    rowFilters: List<TransitFilterOptions>,
    selectedFiltersForBusStop: Set<TransitFilterOptions>,
    hasMoreFilters: Boolean,
    onToggleMode: (TransitFilterOptions) -> Unit,
    onOpenFilters: () -> Unit,
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
                modifier = Modifier.padding(horizontal = 96.dp, vertical = 8.dp),
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
            item() {
                ModeFilterChips(
                    rowFilters,
                    selectedFiltersForBusStop,
                    hasMoreFilters,
                    onToggleMode,
                    onOpenFilters,
                )
            }

            items(
                items = associatedBusStopTimes,
                key = { item -> item.second.stopTimesRecord.let { Triple(it.stopId,it.tripId, it.departureTime) } }
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
            text = "Loading...",
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
fun StopTitle(closestBusStop: GlobbedStopRecord? ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(96.dp)
    ) {
        Text(
            text = closestBusStop?.globbedStopName ?: "Loading local stop...",
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeFilterChips(
    rowFilters: List<TransitFilterOptions>,
    selectedTransitFilterOptions: Set<TransitFilterOptions>,
    showMore: Boolean,
    onToggleMode: (TransitFilterOptions) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A compact, horizontally-scrollable row of the base filter slice. When more filters exist than
    // fit the cap, a trailing chevron opens the full FilterPage rather than expanding in place.
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rowFilters.forEach { option ->
            FilterChip(
                selected = option in selectedTransitFilterOptions,
                onClick = { onToggleMode(option) },
                label = {
                    when (option) {
                        is TransitFilterOptions.RouteShortName -> Text(option.routeShortName)
                        is TransitFilterOptions.TripHeadsign -> Text(option.tripHeadsign)
                        is TransitFilterOptions.StopStand -> Text(option.stopStand)
                        is TransitFilterOptions.TransportMode -> Text(option.mode.label)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                   selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            )
        }
        if (showMore) {
            IconButton(onClick = onOpenFilters) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "More filters",
                )
            }
        }
    }
}

@Composable
fun LazyItemScope.BusCard(
    navController: NavController,
    item: Pair<Boolean, StopTimesRecordWithRealtime>
) {
    val (isFirst, record) = item
    val realtime = record.realtimeStopTimesRecord
    val delay = realtime?.stopTimeDelay
    // Have to carry null delay's all the way through, since that's a valid state when the data just isn't there.
    // Only make the choice to treat it as zero when re-calculating departureTimes.
    val delayText = delay?.toMinutes()?.let {
        when {
            it == 1L -> "Delayed by 1 minute"
            it == 0L -> "On time"
            it == -1L -> "Early by 1 minute"
            it > 1 -> "Delayed by $it minutes"
            else -> "Early by ${-it} minutes"
        }
    } ?: "–"
    val departureTime = item.second.stopTimesRecord.departureTime + (delay ?: Duration.ZERO)

    val (dynamicContainer, onDynamicContainer) = when {
        isFirst && realtime != null ->
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
        Log.d("Home-Page", "Item is $item")
        ListItem(
            modifier = Modifier.clickable {
                navController.navigate(
                    Trips(
                        item.second.stopTimesRecord.tripId,
                        item.second.stopTimesRecord.stopId,
                        item.second.stopTimesRecord.departureTime.toLocalDate().toString(),
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
                Text(record.stopTimesRecord.tripHeadsign)
            },
            headlineContent = {
                Text(
                    record.stopTimesRecord.routeShortName,
                    style = MaterialTheme.typography.bodyLarge
                        .copy(fontStyle = FontStyle.Italic)
                )
            },
            supportingContent = { Text(text = delayText) },
            trailingContent = {
                Text(
                    text = printTime(departureTime),
                    style = MaterialTheme.typography.titleMedium,
                    color = onDynamicContainer
                )
            }
        )
    }
}
