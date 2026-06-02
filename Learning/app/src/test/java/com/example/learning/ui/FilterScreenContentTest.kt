package com.example.learning.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.learning.BusFilterOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behaviour of [FilterScreenContent] — the full FilterPage editor. Driven via the stateless content
 * composable with Robolectric. We assert what the user sees and does: filters are grouped under
 * per-type section headers, tapping a chip then Apply commits the staged selection, and Back returns
 * without committing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class FilterScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val available = setOf<BusFilterOptions>(
        BusFilterOptions.RouteShortName("370"),
        BusFilterOptions.TripHeadsign("City"),
        BusFilterOptions.StopStand("Stand A"),
    )

    private fun setContent(
        available: Set<BusFilterOptions> = this.available,
        onApply: (Set<BusFilterOptions>) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            var staged by remember { mutableStateOf(emptySet<BusFilterOptions>()) }
            MaterialTheme {
                FilterScreenContent(
                    available = available,
                    staged = staged,
                    onToggleStaged = { staged = if (it in staged) staged - it else staged + it },
                    onApply = { onApply(staged) },
                    onBack = onBack,
                )
            }
        }
    }

    // The label text of every rendered filter chip, in the order they appear in the node tree.
    private fun renderedChipLabels(): List<String> =
        composeRule.onAllNodesWithTag("filterChip")
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].first().text }

    @Test
    fun `sections render collapsed by default, showing only the headers`() {
        setContent()

        composeRule.onNodeWithText("Routes").assertIsDisplayed()
        composeRule.onNodeWithText("Destinations").assertIsDisplayed()
        composeRule.onNodeWithText("Stands").assertIsDisplayed()

        composeRule.onNodeWithText("370").assertDoesNotExist()
        composeRule.onNodeWithText("City").assertDoesNotExist()
        composeRule.onNodeWithText("Stand A").assertDoesNotExist()
    }

    @Test
    fun `tapping a chip then Apply commits the staged selection`() {
        var applied: Set<BusFilterOptions>? = null
        setContent(onApply = { applied = it })

        composeRule.onNodeWithText("Routes").performClick()     // expand the section first
        composeRule.onNodeWithText("370").performClick()
        composeRule.onNodeWithText("Apply").performClick()

        assertEquals(setOf(BusFilterOptions.RouteShortName("370")), applied)
    }

    @Test
    fun `the back arrow returns without committing`() {
        var applied: Set<BusFilterOptions>? = null
        var backCalled = false
        setContent(onApply = { applied = it }, onBack = { backCalled = true })

        composeRule.onNodeWithText("Routes").performClick()     // expand the section first
        composeRule.onNodeWithText("370").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(true, backCalled)
        assertNull(applied)
    }

    @Test
    fun `chips within a section are rendered in sorted order`() {
        setContent(
            available = setOf(
                BusFilterOptions.RouteShortName("412"),
                BusFilterOptions.RouteShortName("9"),
                BusFilterOptions.RouteShortName("370"),
                BusFilterOptions.RouteShortName("L90"),
            )
        )

        composeRule.onNodeWithText("Routes").performClick()   // expand to render the chips

        assertEquals(listOf("9", "370", "412", "L90"), renderedChipLabels())
    }

    @Test
    fun `expanding a section shows its chips and collapsing hides them again`() {
        setContent()

        composeRule.onNodeWithText("370").assertDoesNotExist()  // collapsed by default

        composeRule.onNodeWithText("Routes").performClick()     // expand the Routes section
        composeRule.onNodeWithText("370").assertIsDisplayed()
        composeRule.onNodeWithText("City").assertDoesNotExist() // other sections stay collapsed

        composeRule.onNodeWithText("Routes").performClick()     // collapse again
        composeRule.onNodeWithText("370").assertDoesNotExist()
    }
}
