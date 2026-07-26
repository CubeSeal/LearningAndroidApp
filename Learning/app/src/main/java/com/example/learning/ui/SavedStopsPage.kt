package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.Home
import com.example.learning.SavedNavEvent
import com.example.learning.resetTo
import com.example.learning.SavedStop
import com.example.learning.SavedStopsViewModel
import com.example.learning.TransitFilterOptions
import com.example.learning.repos.GlobbedStopRecord

@Composable
fun SavedStopsScreen(
    navController: NavController,
    viewModel: SavedStopsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val savedStops by viewModel.savedStops.collectAsStateWithLifecycle()
    val expandedSavedStops by viewModel.expandedSavedStops.collectAsStateWithLifecycle()
    val pendingClearAll by viewModel.pendingClearAll.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            if (event is SavedNavEvent.NavigateToHome) navController.resetTo(Home)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SavedStopsContent(
            savedStops = savedStops,
            expandedSavedStops = expandedSavedStops,
            onToggleExpanded = viewModel::toggleExpanded,
            onSelect = { stop, combo -> viewModel.onSavedStopSelected(stop, combo) },
            onRemoveCombo = { stopId, combo -> viewModel.removeCombo(stopId, combo) },
            onClearAll = { viewModel.onClearAllClicked(it) },
        )
    }

    pendingClearAll?.let { stop ->
        ConfirmClearAllDialog(
            stop = stop,
            onConfirm = { viewModel.onConfirmClearAll() },
            onDismiss = { viewModel.onDismissClearAll() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedStopsContent(
    savedStops: List<SavedStop>,
    expandedSavedStops: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onSelect: (GlobbedStopRecord, Set<TransitFilterOptions>) -> Unit,
    onRemoveCombo: (String, Set<TransitFilterOptions>) -> Unit,
    onClearAll: (GlobbedStopRecord) -> Unit,
) {
    if (savedStops.isEmpty()) {
        SavedEmptyState(modifier = Modifier.fillMaxSize())
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items = savedStops, key = { it.stop.globbedStopId }) { saved ->
                SavedStopRow(
                    saved = saved,
                    expanded = saved.stop.globbedStopId in expandedSavedStops,
                    onToggleExpanded = onToggleExpanded,
                    onSelect = onSelect,
                    onRemoveCombo = onRemoveCombo,
                    onClearAll = onClearAll,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SavedStopRow(
    saved: SavedStop,
    expanded: Boolean,
    onToggleExpanded: (String) -> Unit,
    onSelect: (GlobbedStopRecord, Set<TransitFilterOptions>) -> Unit,
    onRemoveCombo: (String, Set<TransitFilterOptions>) -> Unit,
    onClearAll: (GlobbedStopRecord) -> Unit,
) {
    val stop = saved.stop
    ListItem(
        modifier = Modifier.clickable { onSelect(stop, emptySet()) },
        headlineContent = { Text(stop.globbedStopName) },
        leadingContent = {
            if (saved.combos.isNotEmpty()) {
                IconButton(onClick = { onToggleExpanded(stop.globbedStopId) }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse saved filters" else "Show saved filters",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Icon(
                    Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = { onClearAll(stop) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove ${stop.globbedStopName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    if (expanded) {
        saved.combos.forEach { combo ->
            SavedComboRow(
                stop = stop,
                combo = combo,
                onSelect = onSelect,
                onRemoveCombo = onRemoveCombo,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedComboRow(
    stop: GlobbedStopRecord,
    combo: Set<TransitFilterOptions>,
    onSelect: (GlobbedStopRecord, Set<TransitFilterOptions>) -> Unit,
    onRemoveCombo: (String, Set<TransitFilterOptions>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(stop, combo) }
            .padding(start = 48.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            combo.forEach { option ->
                FilterChip(
                    selected = false,
                    onClick = { onSelect(stop, combo) },
                    label = { FilterChipLabel(option) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                )
            }
        }
        IconButton(onClick = { onRemoveCombo(stop.globbedStopId, combo) }) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove filter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmClearAllDialog(
    stop: GlobbedStopRecord,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove saved stop?") },
        text = { Text("This removes ${stop.globbedStopName} and all of its saved filters.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SavedEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Save,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No saved stops yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap the + button to add stops you visit often.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
