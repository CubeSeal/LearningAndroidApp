package com.example.learning.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.LoadingScreen
import com.example.learning.TripsViewModel
import com.example.learning.printTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    navController: NavController,
    viewModel: TripsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val stopTimesByTrip by viewModel.busStopTimesRecord.collectAsStateWithLifecycle()
    val stopId = viewModel.stopId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (stopTimesByTrip.isEmpty()) {
            LoadingScreen("Loading trip information...")
        } else {
            val first = stopTimesByTrip.first()
            val routeShortName = first.routeInfo.routeShortName
            val routeLongName = first.routeInfo.routeLongName

            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            routeShortName,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            routeLongName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                itemsIndexed(
                    items = stopTimesByTrip,
                    key = { _, item -> item.stopTimesInfo.sequence }
                ) { index, item ->
                    val isFocused = item.stopInfo.stopId == stopId
                    val (container, onContainer) = if (isFocused) {
                        MaterialTheme.colorScheme.secondaryContainer to
                                MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface to
                                MaterialTheme.colorScheme.onSurface
                    }

                    StopRow(
                        stopName = item.stopInfo.stopName,
                        departureTime = printTime(item.stopTimesInfo.departureTime),
                        isFirst = index == 0,
                        isLast = index == stopTimesByTrip.lastIndex,
                        isFocused = isFocused,
                        container = container,
                        onContainer = onContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun StopRow(
    stopName: String,
    departureTime: String,
    isFirst: Boolean,
    isLast: Boolean,
    isFocused: Boolean,
    container: Color,
    onContainer: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .height(IntrinsicSize.Min)
    ) {
        // Rail spans the full row height including any padding
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                drawLine(
                    color = onContainer.copy(alpha = 0.4f),
                    start = Offset(centerX, if (isFirst) size.height / 2 else 0f),
                    end = Offset(centerX, if (isLast) size.height / 2 else size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
            Box(
                modifier = Modifier
                    .size(if (isFocused) 14.dp else 10.dp)
                    .align(Alignment.Center)
                    .background(onContainer, CircleShape)
            )
        }

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stopName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                color = onContainer
            )
            if (isFocused) {
                Text(
                    "Your stop",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
            }
        }

        // Trailing time
        Text(
            departureTime,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier
                .padding(end = 16.dp)
                .align(Alignment.CenterVertically)
        )
    }
}
