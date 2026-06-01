package com.example.learning.repos

/** State-based fake for [RealtimeGtfsSource]; returns the canned feed it was seeded with. */
class FakeRealtimeSource(
    private val busData: List<RealtimeBusTripInfo> = emptyList(),
) : RealtimeGtfsSource {
    override suspend fun getBusData(): List<RealtimeBusTripInfo> = busData
}
