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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.learning.transitModeRank
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
    // Which groups have had their "…" chip flicked to reveal the overflow beyond the top-10 cap.
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }

    FilterScreenContent(
        available = available,
        staged = staged,
        expandedGroups = expandedGroups,
        onToggleStaged = { staged = if (it in staged) staged - it else staged + it },
        onExpandGroup = { title -> expandedGroups = expandedGroups + title },
        onReset = {
            staged = emptySet()
            expandedGroups = emptySet()
        },
        onApply = {
            viewModel.applyFilters(staged)
            navController.popBackStack()
        },
        onBack = { navController.popBackStack() },
    )
}

/**
 * Stateless content of the FilterPage: the available filters grouped into sections by type, plus the
 * always-visible Apply / Cancel controls. The hosting [FilterScreen] owns the staged selection, the
 * per-group expansion state, and the navigation/ViewModel wiring; this composable just renders them
 * and reports user intent, so it can be exercised directly in tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterScreenContent(
    available: Set<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    expandedGroups: Set<String>,
    onToggleStaged: (TransitFilterOptions) -> Unit,
    onExpandGroup: (String) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    val modes = available.filterIsInstance<TransitFilterOptions.TransportMode>()
        .sortedBy { transitModeRank(it.mode) }
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
            FilterGroup("Modes", modes, staged, "Modes" in expandedGroups, onToggleStaged) { onExpandGroup("Modes") }
            FilterGroup("Platforms", platforms, staged, "Platforms" in expandedGroups, onToggleStaged) { onExpandGroup("Platforms") }
            FilterGroup("Stands", busStands, staged, "Stands" in expandedGroups, onToggleStaged) { onExpandGroup("Stands") }
            FilterGroup("Lines", trainLines, staged, "Lines" in expandedGroups, onToggleStaged) { onExpandGroup("Lines") }
            FilterGroup("Routes", busRoutes, staged, "Routes" in expandedGroups, onToggleStaged) { onExpandGroup("Routes") }
            FilterGroup("Destinations", destinations, staged, "Destinations" in expandedGroups, onToggleStaged) { onExpandGroup("Destinations") }
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

/** How many chips a group shows before the "…" overflow chip; the rest appear once it's flicked. */
private const val GROUP_CAP = 10

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    options: List<TransitFilterOptions>,
    staged: Set<TransitFilterOptions>,
    expanded: Boolean,
    onToggleStaged: (TransitFilterOptions) -> Unit,
    onExpand: () -> Unit,
) {
    if (options.isEmpty()) return

    // Show the top slice until the "…" chip is flicked; overflow is only re-hidden by Reset.
    val visible = if (expanded) options else options.take(GROUP_CAP)
    val hasMore = !expanded && options.size > GROUP_CAP

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visible.forEach { option ->
            FilterChip(
                modifier = Modifier.testTag("filterChip"),
                selected = option in staged,
                onClick = { onToggleStaged(option) },
                label = {
                    when (option) {
                        is TransitFilterOptions.RouteShortName -> Text(option.routeShortName)
                        is TransitFilterOptions.TripHeadsign -> Text(option.tripHeadsign)
                        is TransitFilterOptions.StopStand -> Text(shortStandName(option.stopStand))
                        is TransitFilterOptions.TransportMode -> ModeRoundel(option.mode)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
            )
        }
        if (hasMore) {
            FilterChip(
                modifier = Modifier.testTag("moreChip"),
                selected = false,
                onClick = onExpand,
                label = { Text("…") },
            )
        }
    }
}
