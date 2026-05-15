// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

class TestClock(private var currentInstant: Instant = Clock.System.now()) : Clock {
    override fun now(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant + duration
    }
}
