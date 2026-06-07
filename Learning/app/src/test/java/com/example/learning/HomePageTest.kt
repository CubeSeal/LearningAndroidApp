package com.example.learning

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class HomePageTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun launchWith(container: AppContainer) {
        (ApplicationProvider.getApplicationContext() as LearningApplication).repos = container
        ActivityScenario.launch(MainActivity::class.java)
    }

    @Test
    fun simpleTest() {
        launchWith(container = FakeAppContainer())
        assert(true)
    }


    @Test
    fun showLoadingIfNotLoaded() {
        launchWith(container = FakeAppContainer(loadBehaviour = LoadState.NeverLoad))
        composeRule.onNodeWithText("Loading...").assertIsDisplayed()
    }

//    @Test
//    fun `tapping search opens the stop picker`() {
//        composeRule.onNodeWithContentDescription("Edit Stop.").performClick()
//        composeRule.onNodeWithText("Search").assertIsDisplayed()
//    }
}
