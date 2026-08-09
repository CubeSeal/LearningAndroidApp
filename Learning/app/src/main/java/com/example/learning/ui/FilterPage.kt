package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.BackHeader
import com.example.learning.FilterNavEvent
import com.example.learning.FilterTabs
import com.example.learning.FilterViewModel
import com.example.learning.TransitFilterOptions

/**
 * Route-level FilterPage, driven by its own [FilterViewModel]. The staged selection lives in that
 * ViewModel, which is scoped to this destination, so Back simply discards it by leaving; Apply
 * commits to [com.example.learning.TransitInfo], where Home reads it from.
 */
@Composable
fun FilterScreen(
    navController: NavController,
    viewModel: FilterViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val selectedFilterTab by viewModel.selectedFilterTab.collectAsStateWithLifecycle()
    val visibleOptions by viewModel.visibleOptions.collectAsStateWithLifecycle()
    val stagedFilters by viewModel.stagedFilters.collectAsStateWithLifecycle()

    // Re-arm the "navigate once" latch each time this screen is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            if (event is FilterNavEvent.PopBack) navController.popBackStack()
        }
    }

    FilterScreenContent(
        tabs = tabs,
        selectedFilterTab = selectedFilterTab,
        options = visibleOptions,
        staged = stagedFilters,
        onSelectFilterTab = { viewModel.selectFilterTab(it) },
        onToggleStaged = { viewModel.toggleStaged(it) },
        onReset = { viewModel.resetStaging() },
        onApply = { viewModel.applyStaging() },
        onBack = { viewModel.onBackClicked() },
    )
}

/**
 * Stateless content of the FilterPage: a single scrollable tab row, the selected tab's chips below,
 * and the always-visible Apply / Reset controls. [FilterViewModel] decides which tabs exist, which
 * one is selected and which chips belong to it; this composable just renders them and reports user
 * intent, so it can be exercised directly in tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterScreenContent(
    tabs: List<FilterTabs>,
    selectedFilterTab: FilterTabs?,
    options: List<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    onSelectFilterTab: (FilterTabs) -> Unit,
    onToggleStaged: (TransitFilterOptions) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BackHeader(onBack)

        if (tabs.isNotEmpty()) {
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(selectedFilterTab).coerceAtLeast(0)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == selectedFilterTab,
                        onClick = { onSelectFilterTab(tab) },
                        text = { Text(tab.title) },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            FilterChipGrid(
                options = options,
                staged = staged,
                onToggleStaged = onToggleStaged,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Reset filters",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f),
            ) { Text("Apply") }
        }
    }
}

/** The selected tab's chip grid. The tab's own label already identifies it, so this renders no title
 *  of its own. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterChipGrid(
    options: List<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    onToggleStaged: (TransitFilterOptions) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                modifier = Modifier.testTag("filterChip"),
                selected = option in staged,
                onClick = { onToggleStaged(option) },
                label = { FilterChipLabel(option) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
            )
        }
    }
}
