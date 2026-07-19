package com.example.learning

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import com.example.learning.repos.GlobbedStopRecord
import com.example.learning.repos.LatLon
import com.example.learning.repos.StopRecord
import com.example.learning.repos.StopTimesRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class HomeVerticalSliceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun launchWith(container: AppContainer) {
        (ApplicationProvider.getApplicationContext() as LearningApplication).repos = container
        ActivityScenario.launch(MainActivity::class.java)
    }

    @Test
    fun showLoadingIfNotLoaded() {
        launchWith(container = FakeAppContainer(loadBehaviour = LoadState.NeverLoad))
        composeRule.onNodeWithText("Loading...").assertIsDisplayed()
    }

    @Test
    fun homePageLoadsNormally() {
        launchWith(container = FakeAppContainer())
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun dontShowLoadingIfLoaded() {
        launchWith(container = FakeAppContainer(loadBehaviour = LoadState.DelayedLoad))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Loading").assertIsNotDisplayed()
    }

    @Test
    fun tappingSearchOpensStopPicker() {
        launchWith(container = FakeAppContainer())
        composeRule.onNodeWithContentDescription("Edit Stop.").performClick()
        composeRule.onAllNodesWithText("Search")[0].assertIsDisplayed()
    }

    // Selecting a route filter narrows the departures to that route only, and deselecting restores the
    // full list. Both departures are buses, so the (all-trips) mode filter is dropped and the roundel's
    // "Bus" contentDescription is unique to the departure rows — letting us count rows unambiguously
    // (the route number itself collides with the filter chip's label).
    @Test
    fun selectingRouteFilterNarrowsDeparturesThenRestores() {
        launchWith(container = twoBusRoutesContainer())

        // Both bus departures render.
        composeRule.waitUntil(5_000) { busRowCount() == 2 }

        // Tap the "100" filter chip (rendered above the list, so index 0 is the chip, not the row).
        composeRule.onAllNodesWithText("100")[0].performClick()
        composeRule.waitUntil(5_000) { busRowCount() == 1 }

        // Deselect it → both departures return.
        composeRule.onAllNodesWithText("100")[0].performClick()
        composeRule.waitUntil(5_000) { busRowCount() == 2 }
    }

    private fun busRowCount() =
        composeRule.onAllNodesWithContentDescription("Bus").fetchSemanticsNodes().size

    private fun twoBusRoutesContainer(): FakeAppContainer {
        val stopLoc = LatLon(-33.8688, 151.2093)
        val stop = GlobbedStopRecord(
            globbedStopId = "G1",
            globbedStopName = "Test Stop",
            stopRecords = listOf(StopRecord("S1", "Test Stop", stopLoc, false)),
        )
        val soon = LocalDateTime.now().plusHours(1)
        fun busDeparture(route: String, headsign: String, at: LocalDateTime) = StopTimesRecord(
            tripId = "trip-$route",
            departureTime = at,
            arrivalTime = at,
            sequence = 1,
            routeId = "route-$route",
            serviceId = "svc",
            tripHeadsign = headsign,
            routeShortName = route,
            routeLongName = "Route $route",
            routeType = 3, // bus
            globbedStopId = "G1",
            globbedStopName = "Test Stop",
            stopId = "S1",
            stopName = "Test Stop",
            stopLoc = stopLoc,
            wheelchairBoarding = false,
        )
        val static = FakeStaticGtfsSource(
            stopsById = mapOf("G1" to stop),
            closest = listOf(stop),
            stopTimesByStop = mapOf(
                "G1" to listOf(
                    busDeparture("100", "Downtown", soon),
                    busDeparture("200", "Uptown", soon.plusMinutes(10)),
                ),
            ),
        )
        return FakeAppContainer(
            gtfsStaticRepository = static,
            locationRepo = FakeLocationSource(stopLoc),
            settingsRepo = FakeSettingsSource(),
        )
    }
}
