package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.BackHeader
import com.example.learning.PickStopNavEvent
import com.example.learning.PickStopViewModel
import com.example.learning.SavedStop
import com.example.learning.SearchTab
import com.example.learning.TransitFilterOptions
import com.example.learning.filterLabel
import com.example.learning.repos.GlobbedStopRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickStopScreen(
    navController: NavController,
    viewModel: PickStopViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val savedStops by viewModel.savedStops.collectAsStateWithLifecycle()
    val expandedSavedStops by viewModel.expandedSavedStops.collectAsStateWithLifecycle()
    val pendingClearAll by viewModel.pendingClearAll.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchExpanded by viewModel.searchExpanded.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filteredStops by viewModel.filteredBusStops.collectAsStateWithLifecycle()
    val closestStops by viewModel.closestBusStops.collectAsStateWithLifecycle()

    // Re-arm the ViewModel's "navigate once" latch each time this screen is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            if (event is PickStopNavEvent.PopBack) navController.popBackStack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize().background(MaterialTheme.colorScheme.background)
    )
    {
        BackHeader({ viewModel.onBackClicked() })

        PrimaryTabRow(
            selectedTabIndex = SearchTab.entries.indexOf(selectedTab)
        ) {
            SearchTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.onTabSelected(tab) },
                    text = { Text(tab.label) }
                )
            }
        }

        when (selectedTab) {
            SearchTab.Search -> SearchStopsTabPage(
                query = query,
                onQueryChange = { viewModel.onQueryChange(it) },
                filteredStops = filteredStops,
                closestStops = closestStops,
                searchExpanded = searchExpanded,
                onSearchExpandedChange = { viewModel.onSearchExpandedChange(it) },
                onStopSelected = { viewModel.onStopSelected(it) },
            )

            SearchTab.Saved -> SavedStopsTabPage(
                savedStops = savedStops,
                expandedSavedStops = expandedSavedStops,
                onToggleExpanded = viewModel::toggleExpanded,
                onSelect = { stop, combo -> viewModel.onSavedStopSelected(stop, combo) },
                onRemoveCombo = { stopId, combo -> viewModel.removeCombo(stopId, combo) },
                onClearAll = { viewModel.onClearAllClicked(it) },
            )
        }
    }

    pendingClearAll?.let { stop ->
        ConfirmClearAllDialog(
            stop = stop,
            onConfirm = { viewModel.onConfirmClearAll() },
            onDismiss = { viewModel.onDismissClearAll() },
        )
    }
}

@Composable
fun SearchStopsTabPage(
    query: String,
    onQueryChange: (String) -> Unit,
    filteredStops: List<GlobbedStopRecord>,
    closestStops: List<GlobbedStopRecord>,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onStopSelected: (GlobbedStopRecord) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true }
    ) {
        SearchBar(
            query = query,
            filteredStops = filteredStops,
            closestStops = closestStops,
            onQueryChange = onQueryChange,
            searchExpanded = searchExpanded,
            onSearchExpandedChange = onSearchExpandedChange,
            onStopSelected = onStopSelected,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.SearchBar(
    query: String,
    filteredStops: List<GlobbedStopRecord>,
    closestStops: List<GlobbedStopRecord>,
    onQueryChange: (String) -> Unit,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onStopSelected: (GlobbedStopRecord) -> Unit,
) {
    SearchBar(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .background(color = MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 0.dp)
            .semantics { traversalIndex = 0f },
        windowInsets = WindowInsets(0.dp),
        colors = SearchBarDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background
        ),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = { onQueryChange(it) },
                onSearch = {
                    filteredStops.firstOrNull()?.let { onStopSelected(it) }
                    onSearchExpandedChange(false)
                },
                expanded = searchExpanded,
                onExpandedChange = { onSearchExpandedChange(it) },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = null
            )
        },
        expanded = searchExpanded,
        onExpandedChange = { onSearchExpandedChange(it) },
    ) {
        LazyColumn {
            items(
                items = filteredStops,
                key = { it.globbedStopId }
            ) { busStop ->
                ListItem(
                    headlineContent = { Text(busStop.globbedStopName) },
                    modifier = Modifier
                        .clickable {
                            onStopSelected(busStop)
                            onSearchExpandedChange(false)
                        }
                )
            }
        }
    }

    // Show closest stops here
    LazyColumn {
        items(
            items = closestStops,
            key = { it.globbedStopId }
        ) { busStop ->
            ListItem(
                headlineContent = { Text(busStop.globbedStopName) },
                modifier = Modifier
                    .clickable {
                        onStopSelected(busStop)
                        onSearchExpandedChange(false)
                    }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedStopsTabPage(
    savedStops: List<SavedStop>,
    expandedSavedStops: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onSelect: (GlobbedStopRecord, Set<TransitFilterOptions>) -> Unit,
    onRemoveCombo: (String, Set<TransitFilterOptions>) -> Unit,
    onClearAll: (GlobbedStopRecord) -> Unit,
) {
    if (savedStops.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize() )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = savedStops,
                key = { it.stop.globbedStopId }
            ) { saved ->
                SavedStopRow(
                    saved = saved,
                    expanded = saved.stop.globbedStopId in expandedSavedStops,
                    onToggleExpanded = onToggleExpanded,
                    onSelect = onSelect,
                    onRemoveCombo = onRemoveCombo,
                    onClearAll = onClearAll,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
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
    // Top-level row = the naked stop. Tap selects it with no filters; the chevron (only when combos
    // exist) reveals them; the trailing x is the destructive clear-all (confirmed via dialog).
    ListItem(
        modifier = Modifier.clickable { onSelect(stop, emptySet()) },
        headlineContent = { Text(stop.globbedStopName) },
        supportingContent = { Text("Stop ${stop.globbedStopId}") },
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
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        trailingContent = {
            IconButton(onClick = { onClearAll(stop) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove ${stop.globbedStopName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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

@Composable
private fun SavedComboRow(
    stop: GlobbedStopRecord,
    combo: Set<TransitFilterOptions>,
    onSelect: (GlobbedStopRecord, Set<TransitFilterOptions>) -> Unit,
    onRemoveCombo: (String, Set<TransitFilterOptions>) -> Unit,
) {
    // Indented sub-row: tap applies this exact filter combo; the trailing x removes just this combo.
    ListItem(
        modifier = Modifier
            .clickable { onSelect(stop, combo) }
            .padding(start = 32.dp),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        headlineContent = { Text(combo.joinToString(", ") { filterLabel(it) }) },
        trailingContent = {
            IconButton(onClick = { onRemoveCombo(stop.globbedStopId, combo) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove filter",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
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
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Save,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No saved stops yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap the + button to add stops you visit often.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
