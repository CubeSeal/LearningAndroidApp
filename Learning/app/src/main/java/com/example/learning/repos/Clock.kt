package com.example.learning.repos

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Wall-clock time, behind a seam so tests can move it forward. */
interface ClockSource {
    /** The current minute, emitted again each time a new one starts. */
    val currentMinute: Flow<LocalDateTime>
}

class SystemClockSource : ClockSource {
    override val currentMinute: Flow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now.truncatedTo(ChronoUnit.MINUTES))
            val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(ChronoUnit.MILLIS.between(now, nextMinute))
        }
    }
}

class FakeClockSource(start: LocalDateTime = LocalDateTime.now()) : ClockSource {
    private val _currentMinute = MutableStateFlow(start.truncatedTo(ChronoUnit.MINUTES))
    override val currentMinute = _currentMinute.asStateFlow()

    fun advanceMinutes(minutes: Long = 1) = _currentMinute.update { it.plusMinutes(minutes) }
}
