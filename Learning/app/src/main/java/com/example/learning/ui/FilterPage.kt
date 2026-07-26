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
import androidx.compose.runtime.remember
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
import com.example.learning.FilterCategory
import com.example.learning.Home
import com.example.learning.HomeNavEvent
import com.example.learning.HomeViewModel
import com.example.learning.TransitFilterOptions
import com.example.learning.filterCategoryOf
import com.example.learning.filterLabel
import com.example.learning.transitModeRank

/**
 * Route-level FilterPage. Shares the **same** [HomeViewModel] as the Home screen by scoping the
 * ViewModel to the Home back-stack entry (still on the stack while Filter is pushed on top), so
 * Apply writes straight back to the Home filter state. Holds the staged selection locally; Apply
 * commits it and returns, Back discards and returns.
 */
@Composable
fun FilterScreen(navController: NavController) {
    val homeEntry = remember { navController.getBackStackEntry<Home>() }
    val viewModel: HomeViewModel = viewModel(homeEntry, factory = AppViewModelProvider.Factory)

    val available by viewModel.availableFiltersForBusStop.collectAsStateWithLifecycle()
    val staged by viewModel.stagedFilters.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedFilterCategory.collectAsStateWithLifecycle()

    // Re-arm the shared HomeViewModel's "navigate once" latch each time this screen is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    LaunchedEffect(Unit) { viewModel.beginStaging() }
    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            if (event is HomeNavEvent.PopBack) navController.popBackStack()
        }
    }

    FilterScreenContent(
        available = available,
        staged = staged,
        selectedCategory = selectedCategory,
        onSelectCategory = { viewModel.selectFilterCategory(it) },
        onToggleStaged = { viewModel.toggleStaged(it) },
        onReset = { viewModel.resetStaging() },
        onApply = { viewModel.applyStaging() },
        onBack = { viewModel.onFilterBackClicked() },
    )
}

/**
 * Stateless content of the FilterPage: a single scrollable tab row over the categories that have any
 * options for this stop, and the selected category's chips below, plus the always-visible Apply /
 * Cancel controls. The hosting [FilterScreen] owns the staged selection, the selected tab, and the
 * navigation/ViewModel wiring; this composable just renders them and reports user intent, so it can
 * be exercised directly in tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterScreenContent(
    available: Set<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    selectedCategory: FilterCategory?,
    onSelectCategory: (FilterCategory) -> Unit,
    onToggleStaged: (TransitFilterOptions) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    // Group by category (Modes always sorts train -> bus -> other; every other category shares the
    // same numeric-aware label sort), then drop empty categories — a single-mode stop only ever shows
    // the tabs that apply to it. Map iteration follows FilterCategory's broad -> specific declaration
    // order (mirroring the Home row's `filterTypeRank` order), since groupBy preserves encounter order
    // and `available` itself has no other ordering guarantee.
    val categorized: Map<FilterCategory, List<TransitFilterOptions>> = available
        .groupBy(filterCategoryOf)
        .mapValues { (category, options) ->
            if (category == FilterCategory.Modes) {
                options.sortedBy { transitModeRank((it as TransitFilterOptions.TransportMode).mode) }
            } else {
                options.sortedWith(byLabel(filterLabel))
            }
        }
    val categories = FilterCategory.entries.filter { it in categorized }
    val selected = selectedCategory?.takeIf { it in categorized } ?: categories.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BackHeader(onBack)

        if (categories.isNotEmpty()) {
            ScrollableTabRow(selectedTabIndex = categories.indexOf(selected).coerceAtLeast(0)) {
                categories.forEach { category ->
                    Tab(
                        selected = category == selected,
                        onClick = { onSelectCategory(category) },
                        text = { Text(category.title) },
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
            selected?.let { category ->
                FilterChipGrid(
                    options = categorized.getValue(category),
                    staged = staged,
                    onToggleStaged = onToggleStaged,
                )
            }
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

/**
 * Numeric-aware chip ordering: bus routes read as numbers ("9" before "370" before "412"), with
 * non-numeric labels ("L90") sorted lexically after the numbers.
 */
private fun <T> byLabel(label: (T) -> String): Comparator<T> =
    compareBy({ label(it).toIntOrNull() ?: Int.MAX_VALUE }, { label(it) })

/** The selected category's chip grid. The category's tab label already identifies it, so this renders
 *  no title of its own. */
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
