package com.example.learning

import com.example.learning.repos.FakeLocationSource
import com.example.learning.repos.FakeRealtimeSource
import com.example.learning.repos.FakeSettingsSource
import com.example.learning.repos.FakeStaticGtfsSource
import kotlinx.coroutines.test.TestScope

// TransitInfo is wiring: built here, handed to the ViewModels, never read by a test. Each fake
// defaults to an empty world; pass the ones a test seeds or asserts on, keeping a reference to them.
fun TestScope.buildTransitInfo(
    gtfsStaticRepository: FakeStaticGtfsSource = FakeStaticGtfsSource(),
    gtfsRealtimeRepository: FakeRealtimeSource = FakeRealtimeSource(),
    locationRepo: FakeLocationSource = FakeLocationSource(),
    settingsRepo: FakeSettingsSource = FakeSettingsSource(),
) = TransitInfo(
    gtfsStaticRepository,
    gtfsRealtimeRepository,
    locationRepo,
    settingsRepo,
    backgroundScope,
)
