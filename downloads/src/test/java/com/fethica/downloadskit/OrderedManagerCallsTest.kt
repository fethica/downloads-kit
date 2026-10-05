package com.fethica.downloadskit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The calls that drive the media3 manager directly (requirements, pause, resume).
 *
 * Each used to be its own coroutine that first waited for the manager to be built and
 * then hopped to the looper, so two calls made in order could land in either order.
 * For the Wi-Fi-only switch that meant off-then-on could end with the OFF mask applied
 * while the toggle and the preference both said Wi-Fi only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderedManagerCallsTest {

    private class FakeManager {
        val applied = mutableListOf<String>()
    }

    @Test
    fun `calls reach the manager in the order they were made even when the first is slowest to get there`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val manager = FakeManager()
        var resolutions = 0
        // Deliberately reversed: the first resolution is the slow one, which is exactly
        // the interleaving that let the older mask land last.
        val calls = OrderedManagerCalls(
            scope = scope,
            resolve = {
                delay(if (resolutions++ == 0) 500 else 1)
                manager
            },
            target = dispatcher,
        )
        try {
            calls.submit { it.applied += "metered allowed" }
            calls.submit { it.applied += "wifi only" }
            advanceUntilIdle()

            assertEquals(listOf("metered allowed", "wifi only"), manager.applied)
            // And the manager is resolved once, not once per call.
            assertEquals(1, resolutions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `calls made before the manager exists wait for it and then run in order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val manager = FakeManager()
        val calls = OrderedManagerCalls(
            scope = scope,
            resolve = {
                delay(1_000)
                manager
            },
            target = dispatcher,
        )
        try {
            repeat(5) { i -> calls.submit { it.applied += "call $i" } }
            testScheduler.advanceTimeBy(500)
            assertEquals(emptyList<String>(), manager.applied)
            advanceUntilIdle()
            assertEquals((0 until 5).map { "call $it" }, manager.applied)

            calls.submit { it.applied += "later" }
            advanceUntilIdle()
            assertEquals("later", manager.applied.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failing call does not stop later manager calls`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val manager = FakeManager()
        val failures = mutableListOf<Throwable>()
        val calls = OrderedManagerCalls(
            scope = scope,
            resolve = { manager },
            target = dispatcher,
            onFailure = failures::add,
        )
        try {
            calls.submit { throw IllegalStateException("watcher failed") }
            calls.submit { it.applied += "still applied" }
            advanceUntilIdle()

            assertEquals(listOf("still applied"), manager.applied)
            assertEquals(listOf("watcher failed"), failures.map { it.message })
        } finally {
            scope.cancel()
        }
    }
}
