package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
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
import com.example.learning.repos.GlobbedStopRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickStopScreen(
    navController: NavController,
    viewModel: PickStopViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filteredStops by viewModel.filteredBusStops.collectAsStateWithLifecycle()
    val closestStops by viewModel.closestBusStops.collectAsStateWithLifecycle()
    val searchExpanded by viewModel.searchExpanded.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            if (event is PickStopNavEvent.PopBack) navController.popBackStack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BackHeader({ viewModel.onBackClicked() })

        Box(
            Modifier
                .fillMaxSize()
                .semantics { isTraversalGroup = true },
        ) {
            StopSearchBar(
                query = query,
                filteredStops = filteredStops,
                closestStops = closestStops,
                onQueryChange = { viewModel.onQueryChange(it) },
                searchExpanded = searchExpanded,
                onSearchExpandedChange = { viewModel.onSearchExpandedChange(it) },
                onStopSelected = { viewModel.onStopSelected(it) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.StopSearchBar(
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
        colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
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
                trailingIcon = null,
            )
        },
        expanded = searchExpanded,
        onExpandedChange = { onSearchExpandedChange(it) },
    ) {
        LazyColumn {
            items(items = filteredStops, key = { it.globbedStopId }) { busStop ->
                ListItem(
                    headlineContent = { Text(busStop.globbedStopName) },
                    modifier = Modifier.clickable {
                        onStopSelected(busStop)
                        onSearchExpandedChange(false)
                    },
                )
            }
        }
    }

    LazyColumn {
        items(items = closestStops, key = { it.globbedStopId }) { busStop ->
            ListItem(
                headlineContent = { Text(busStop.globbedStopName) },
                modifier = Modifier.clickable {
                    onStopSelected(busStop)
                    onSearchExpandedChange(false)
                },
            )
        }
    }
}
