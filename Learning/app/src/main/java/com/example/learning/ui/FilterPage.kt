package com.example.learning.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.learning.AppViewModelProvider
import com.example.learning.BackHeader
import com.example.learning.TransitFilterOptions
import com.example.learning.Home
import com.example.learning.HomeViewModel
import com.example.learning.repos.TransitMode

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
    var staged by remember { mutableStateOf(viewModel.selectedFiltersForBusStop.value) }

    FilterScreenContent(
        available = available,
        staged = staged,
        onToggleStaged = { staged = if (it in staged) staged - it else staged + it },
        onReset = { staged = emptySet() },
        onApply = {
            viewModel.applyFilters(staged)
            navController.popBackStack()
        },
        onBack = { navController.popBackStack() },
    )
}

/**
 * Stateless content of the FilterPage: the available filters grouped into sections by type, plus the
 * always-visible Apply / Cancel controls. The hosting [FilterScreen] owns the staged selection and
 * the navigation/ViewModel wiring; this composable just renders [staged] and reports user intent, so
 * it can be exercised directly in tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterScreenContent(
    available: Set<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    onToggleStaged: (TransitFilterOptions) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    val modes = available.filterIsInstance<TransitFilterOptions.TransportMode>()
        .sortedWith(byLabel { it.mode.label })
    // Routes and stands split by mode so trains can be named "lines"/"platforms" while buses (and
    // anything else) keep "routes"/"stands". Empty groups render nothing, so a single-mode stop only
    // ever shows the terms that apply to it.
    val allRoutes = available.filterIsInstance<TransitFilterOptions.RouteShortName>()
    val trainLines = allRoutes.filter { it.mode == TransitMode.TRAIN }
        .sortedWith(byLabel { it.routeShortName })
    val busRoutes = allRoutes.filter { it.mode != TransitMode.TRAIN }
        .sortedWith(byLabel { it.routeShortName })
    val destinations = available.filterIsInstance<TransitFilterOptions.TripHeadsign>()
        .sortedWith(byLabel { it.tripHeadsign })
    val allStands = available.filterIsInstance<TransitFilterOptions.StopStand>()
    val platforms = allStands.filter { it.mode == TransitMode.TRAIN }
        .sortedWith(byLabel { it.stopStand })
    val busStands = allStands.filter { it.mode != TransitMode.TRAIN }
        .sortedWith(byLabel { it.stopStand })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BackHeader(onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Broad → specific hierarchy: mode, then stand/platform, then route/line, then
            // destination — mirroring the Home row's `filterTypeRank` order.
            FilterGroup("Modes", modes, staged, onToggleStaged)
            FilterGroup("Platforms", platforms, staged, onToggleStaged)
            FilterGroup("Stands", busStands, staged, onToggleStaged)
            FilterGroup("Lines", trainLines, staged, onToggleStaged)
            FilterGroup("Routes", busRoutes, staged, onToggleStaged)
            FilterGroup("Destinations", destinations, staged, onToggleStaged)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    options: List<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    onToggleStaged: (TransitFilterOptions) -> Unit,
) {
    if (options.isEmpty()) return

    // Each category collapses independently so a long list (e.g. Stands) can be folded away to reach
    // another section without scrolling past everything.
    var expanded by rememberSaveable(title) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
    if (!expanded) return

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                modifier = Modifier.testTag("filterChip"),
                selected = option in staged,
                onClick = { onToggleStaged(option) },
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
                ),
            )
        }
    }
}
