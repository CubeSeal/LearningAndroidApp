package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.repos.BusStopInfo
import com.example.learning.AppViewModelProvider
import com.example.learning.PickStopViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickStopScreen(
    navController: NavController,
    viewModel: PickStopViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val searchCallback = fun(busStop: BusStopInfo) {
        viewModel.updateFocusedBusStop(busStop)
        navController.popBackStack()
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filteredStops by viewModel.filteredBusStops.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    )
    {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(50.dp)
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground // or onBackground, onPrimary, etc.
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .semantics { isTraversalGroup = true }
        ) {
            SearchBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .semantics { traversalIndex = 0f },
                inputField = {
                    // Customizable input field implementation
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { viewModel.onQueryChange(it) },
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
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier
                                .clickable {
                                    searchCallback(busStop)
                                    expanded = false
                                }
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
