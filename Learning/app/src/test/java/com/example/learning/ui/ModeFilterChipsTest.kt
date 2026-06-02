package com.example.learning.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.learning.BusFilterOptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behaviour of [ModeFilterChips] — the compact Home-page filter row. Driven through the public
 * composable with Robolectric so it runs in the JVM inner loop (no emulator). We assert on what the
 * user sees: the given chips render, tapping one toggles it, and the trailing "more" chevron only
 * shows when there are extra filters (and opens the FilterPage when tapped).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ModeFilterChipsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rowFilters = listOf(
        BusFilterOptions.RouteShortName("370"),
        BusFilterOptions.RouteShortName("412"),
        BusFilterOptions.TripHeadsign("City"),
    )

    private fun setChips(
        selected: Set<BusFilterOptions> = emptySet(),
        showMore: Boolean = false,
        onToggle: (BusFilterOptions) -> Unit = {},
        onOpenFilters: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    ModeFilterChips(
                        rowFilters = rowFilters,
                        selectedBusFilterOptions = selected,
                        showMore = showMore,
                        onToggleMode = onToggle,
                        onOpenFilters = onOpenFilters,
                    )
                }
            }
        }
    }

    @Test
    fun `the row renders the given filters`() {
        setChips()

        composeRule.onNodeWithText("370").assertIsDisplayed()
        composeRule.onNodeWithText("412").assertIsDisplayed()
        composeRule.onNodeWithText("City").assertIsDisplayed()
    }

    @Test
    fun `tapping a chip toggles that filter`() {
        var toggled: BusFilterOptions? = null
        setChips(onToggle = { toggled = it })

        composeRule.onNodeWithText("412").performClick()

        assertEquals(BusFilterOptions.RouteShortName("412"), toggled)
    }

    @Test
    fun `the more-filters chevron shows and opens the filter page when there are extras`() {
        var opened = false
        setChips(showMore = true, onOpenFilters = { opened = true })

        composeRule.onNodeWithContentDescription("More filters").performClick()

        assertEquals(true, opened)
    }

    @Test
    fun `the more-filters chevron is hidden when there are no extras`() {
        setChips(showMore = false)

        composeRule.onNodeWithContentDescription("More filters").assertDoesNotExist()
    }
}
