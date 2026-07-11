package com.example.learning.ui

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.example.learning.repos.TransitMode
import com.example.learning.repos.transitModeOf
import kotlinx.coroutines.launch
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
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                focusedBusStop?.let { stop ->
                    SaveStop {
                        viewModel.addSavedStop(stop)
                        scope.launch {
                            snackbarHostState.showSnackbar("Saved ${stop.globbedStopName}")
                        }
                    }
                }
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
                { viewModel.toggleFilterForBusStops(it) },
                { navController.navigate(Filter) },
                { viewModel.clearFilters() },
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
    onToggleMode: (TransitFilterOptions) -> Unit,
    onOpenFilters: () -> Unit,
    onResetFilters: () -> Unit,
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
                    onToggleMode,
                    onOpenFilters,
                    onResetFilters,
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
    onToggleMode: (TransitFilterOptions) -> Unit,
    onOpenFilters: () -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The pinned-left button toggles with the filter state: with nothing selected it's a filter
    // button that opens the full FilterPage; once something is selected it becomes a reset button
    // that clears the selection (bringing the filter button back).
    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rowFilters.isNotEmpty()) {
            if (selectedTransitFilterOptions.isEmpty()) {
                IconButton(onClick = onOpenFilters) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = "More filters",
                    )
                }
            } else {
                IconButton(onClick = onResetFilters) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset filters",
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
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
    val scheduledDeparture = item.second.stopTimesRecord.departureTime
    // Have to carry null delay's all the way through, since that's a valid state when the data just isn't there.
    // Only make the choice to treat it as zero when re-calculating departureTimes.
    val departureTime = scheduledDeparture + (delay ?: Duration.ZERO)
    // No realtime at all: the whole row is italicised and the trailing spot shows the scheduled
    // time in the same italic style, signalling "timetable only".
    val untracked = realtime == null
    // The trip is off-schedule (and worth showing both times) only when realtime shifts it by at
    // least a displayed minute; sub-minute deltas render as the same clock time.
    val isOffSchedule = delay != null && delay.toMinutes() != 0L

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
            leadingContent = { ModeRoundel(transitModeOf(record.stopTimesRecord.routeType)) },
            overlineContent = {
                Text(
                    record.stopTimesRecord.tripHeadsign.orEmpty(),
                    fontStyle = if (untracked) FontStyle.Italic else null
                )
            },
            headlineContent = {
                Text(
                    record.stopTimesRecord.routeShortName.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                        .copy(fontStyle = FontStyle.Italic)
                )
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    if (untracked) {
                        // Reserve the (invisible) predicted-time line so the italic scheduled time
                        // lands in the same vertical spot as the realtime row's second line, rather
                        // than centring higher.
                        Text(text = "", style = MaterialTheme.typography.titleMedium)
                        ScheduledTimeText(printTime(scheduledDeparture), onDynamicContainer)
                    } else {
                        Text(
                            text = printTime(departureTime),
                            style = MaterialTheme.typography.titleMedium,
                            color = onDynamicContainer
                        )
                        // Always reserve the scheduled-time line (empty when on time) so the
                        // predicted time aligns with the off-schedule and untracked rows.
                        ScheduledTimeText(
                            if (isOffSchedule) printTime(scheduledDeparture) else "",
                            onDynamicContainer
                        )
                    }
                }
            }
        )
    }
}

/** The scheduled departure time, rendered in the small italic style shared by the off-schedule and
 * untracked rows. */
@Composable
private fun ScheduledTimeText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = color,
    )
}

/**
 * A Transport for NSW mode "roundel": a solid, brand-coloured circle bearing the mode's letter
 * (orange "T" for trains, blue "B" for buses), so the departure's mode reads at a glance.
 */
@Composable
private fun ModeRoundel(mode: TransitMode) {
    val (color, letter, description) = when (mode) {
        TransitMode.TRAIN -> Triple(Color(0xFFF6891F), "T", "Train")   // Sydney Trains orange
        TransitMode.BUS -> Triple(Color(0xFF00B5EF), "B", "Bus")       // TfNSW bus blue
        TransitMode.OTHER -> Triple(Color(0xFF6D6E71), "", "Other transport")
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
