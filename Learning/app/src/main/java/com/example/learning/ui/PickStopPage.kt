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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.learning.AppViewModelProvider
import com.example.learning.Home
import com.example.learning.PickStopViewModel
import com.example.learning.repos.BusStopInfo

enum class SearchTab(val label: String) {
    Search("Search"),
    Saved("Saved")
}

@Composable
fun PickStopScreen(
    navController: NavController,
    viewModel: PickStopViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    fun searchCallback(busStop: BusStopInfo) {
        viewModel.updateFocusedBusStop(busStop)
        navController.popBackStack()
    }
    val closestStops by viewModel.closestBusStops.collectAsStateWithLifecycle()
    val savedStops by viewModel.savedStops.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(SearchTab.Search) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filteredStops by viewModel.filteredBusStops.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    )
    {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(48.dp)
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground // or onBackground, onPrimary, etc.
                )
            }
        }

        PrimaryTabRow(
            selectedTabIndex = SearchTab.entries.indexOf(selectedTab)
        ) {
            SearchTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }

        when (selectedTab) {
            SearchTab.Search -> SearchStopsTabPage(
                closestStops = closestStops,
                query = query,
                onQueryChange = { viewModel.onQueryChange(it) },
                filteredStops = filteredStops,
                searchCallback = ::searchCallback
            )

            SearchTab.Saved -> SavedStopsTabPage(
               savedStops = savedStops,
               onTap = { stop ->
                   viewModel.updateFocusedBusStop(stop)
                   navController.navigate(Home) {
                       popUpTo(navController.graph.findStartDestination().id) {
                           saveState = true
                       }
                       launchSingleTop = true
                       restoreState = true
                   }

               },
               onRemove = viewModel::removeSavedStop
            )
        }
    }
}

@Composable
fun SearchStopsTabPage(
    closestStops: List<BusStopInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    filteredStops: List<BusStopInfo>,
    searchCallback: (BusStopInfo) -> Unit
) {

    Box(
        Modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true }
    ) {
        SearchBar(
            query = query,
            filteredStops = filteredStops,
            onQueryChange = onQueryChange,
            searchCallback = searchCallback
        )
    }

    ClosetStopsList(
        closestStops = closestStops,
        searchCallback = searchCallback
    )
}

@Composable
fun ClosetStopsList(
   closestStops: List<BusStopInfo>,
   searchCallback: (BusStopInfo) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(closestStops) {
            ListItem(
                modifier = Modifier.clickable { searchCallback(it) },
                headlineContent = {
                    Text(
                        it.stopName,
                        style = MaterialTheme.typography.bodyLarge
                            .copy(fontStyle = FontStyle.Italic)
                    )
                }
            )
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.SearchBar(
    query: String,
    filteredStops: List<BusStopInfo>,
    onQueryChange: (String) -> Unit,
    searchCallback: (BusStopInfo) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

        SearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(color = MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
                .semantics { traversalIndex = 0f },
            colors = SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.background
            ),
            inputField = {
                // Customizable input field implementation
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { onQueryChange(it) },
                    onSearch = {
                        filteredStops.firstOrNull()?.let {
                            searchCallback(it)
                        }
                        expanded = false
                    },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = null
                )
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            // Show search results in a lazy column for better performance
            LazyColumn {
                items(
                    items = filteredStops,
                    key = { it.stopId }
                ) { busStop ->
                    ListItem(
                        headlineContent = { Text(busStop.stopName) },
                        supportingContent = null,
                        leadingContent = null,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier
                            .clickable {
                                searchCallback(busStop)
                                expanded = false
                            }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedStopsTabPage(
    savedStops: List<BusStopInfo>,
    onTap: (BusStopInfo) -> Unit,
    onRemove: (BusStopInfo) -> Unit
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
                key = { it.stopId }
            ) { stop ->
                SavedStopRow(
                    stop = stop,
                    onTap = onTap,
                    onRemove = onRemove
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
    stop: BusStopInfo,
    onTap: (BusStopInfo) -> Unit,
    onRemove: (BusStopInfo) -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = {onTap(stop)}),
        headlineContent = { Text(stop.stopName) },
        supportingContent = { Text("Stop ${stop.stopId}") },
        leadingContent = {
            Icon(
                Icons.Default.DirectionsBus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = {onRemove(stop)}) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove ${stop.stopName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
