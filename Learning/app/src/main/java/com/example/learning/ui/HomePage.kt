package com.example.learning.ui

import android.util.Log
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.learning.SharedViewModel
import com.example.learning.Trips
import com.example.learning.repos.BusStopInfo
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.compareTo
import kotlin.div
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HOMEScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    sharedViewModel: SharedViewModel,
) {
    val focusedBusStop by viewModel.focusedBusStop.collectAsStateWithLifecycle()
    val associatedBusStopTimes by viewModel.associatedStopTimes.collectAsStateWithLifecycle()
    val isAppReady by viewModel.isUpToDate.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    LaunchedEffect(associatedBusStopTimes) {
        listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(48.dp)
                .padding(horizontal = 16.dp)
        ) {
            BypassHeader(isAppReady)
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshLocation() }
        ) {
            MasterLazyColumn(
                listState,
                navController,
                sharedViewModel,
                focusedBusStop,
                associatedBusStopTimes
            )
        }
    }
}

@Composable
fun MasterLazyColumn(
    listState: LazyListState,
    navController: NavController,
    sharedViewModel: SharedViewModel,
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
                StopTitle(focusedBusStop, navController)
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
                BusCard(navController, sharedViewModel, item)
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
fun StopTitle(
    closestBusStop: BusStopInfo?,
    navController: NavController
) {
    val iconSideWeight = 0.1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(96.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(modifier = Modifier.weight(1 - iconSideWeight)) {
                Text(
                    text = closestBusStop?.stopName ?: "Loading local stop...",
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.headlineLarge,
                )
            }

            Box(modifier = Modifier.weight(iconSideWeight)) {
                Icon(
                    Icons.Filled.Create,
                    "Edit Stop.",
                    tint = MaterialTheme.colorScheme.onBackground, // or onBackground, onPrimary, etc.
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            navController.navigate(PickStop)
                        }
                )
            }
        }
    }
}

@Composable
fun LazyItemScope.BusCard(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    item: Pair<Boolean, RealtimeBusStopTimesRecord>
) {
    val localDateNow = LocalDate.now()
    val realTimeAvailable = item.second.realtimeBusInfo != null
    val printTime = item.second.busStopTimesRecord.stopTimesInfo.departureTime.let {
        val time = it.format(DateTimeFormatter.ofPattern("h:mm a"))
        when {
            it.toLocalDate() == localDateNow -> time
            it.toLocalDate() == localDateNow.plusDays(1) -> "Tomorrow $time"
            it.toLocalDate() < localDateNow.plusWeeks(1) -> "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $time"
            else -> "${it.format(DateTimeFormatter.ofPattern("dd/MM"))} $time"
        }
    }

    val (dynamicContainer, onDynamicContainer) = when (item.first) {
        true -> when (realTimeAvailable) {
            true -> MaterialTheme.colorScheme.primaryContainer to
                    MaterialTheme.colorScheme.onPrimaryContainer
            false -> MaterialTheme.colorScheme.surfaceContainerHigh to
                    MaterialTheme.colorScheme.onSurfaceVariant
        }
        false -> MaterialTheme.colorScheme.surfaceContainerLow to
                MaterialTheme.colorScheme.onSurface
    }

    Card (
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateItem(
                fadeInSpec = tween(300, easing = LinearOutSlowInEasing),
                fadeOutSpec = tween(300, easing = FastOutLinearInEasing),
                placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(dynamicContainer)
                .clickable {
                    sharedViewModel.select(item.second.busStopTimesRecord)
                    navController.navigate(Trips)
                }
                .padding(16.dp)
        ) {
            val leftText =
                "${item.second.busStopTimesRecord.routeInfo.routeShortName} - ${item.second.busStopTimesRecord.tripInfo.tripHeadsign}"
            val underLeftText =
                if (realTimeAvailable) "${item.second.realtimeBusInfo!!.distance.roundToInt()}m" else "Untracked"
            Text(
                text = leftText,
                color = onDynamicContainer,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(
                text = underLeftText,
                color = onDynamicContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            Text(
                text = printTime,
                color = onDynamicContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}
