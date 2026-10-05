package com.fethica.downloadskit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facade's engine-agnostic half, over a fake manager and a fake index. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsCoreTest {

    private val commands = FakeDownloadCommands()
    private val settings = FakeSettings(true)
    private val listener = RecordingListener()

    /**
     * An unconfined dispatcher so the core's own collectors run the moment they are
     * launched: `advanceUntilIdle` does not reach `backgroundScope` work.
     */
    private fun TestScope.coreScope() =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.core(
        settings: DownloadsSettings = this@DownloadsCoreTest.settings,
        started: Boolean = true,
    ) = DownloadsCore(commands, settings, coreScope(), UnconfinedTestDispatcher(testScheduler))
        .also { it.addListener(listener) }
        .also { if (started) it.start() }

    /**
     * The same core over a dispatcher that runs nothing until the test says so, which is
     * what makes the window between construction and the settings read observable.
     *
     * Its own scope, not `backgroundScope`: `advanceUntilIdle` does not reach background
     * work, so the read would never land. It is cancelled on the way out instead.
     */
    private fun TestScope.withPausedCore(
        settings: DownloadsSettings,
        block: TestScope.(DownloadsCore) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            block(DownloadsCore(commands, settings, scope, dispatcher))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a request reaches the manager with its metadata attached`() = runTest {
        val core = core()
        core.request("e1", "https://x/1.mp3", "audio/mpeg", DownloadMetadata("Item 1", "Collection", "group:1"))
        assertEquals(1, commands.added.size)
        val (id, uri, metadata) = commands.added.single()
        assertEquals("e1", id)
        assertEquals("https://x/1.mp3", uri)
        assertEquals("group:1", metadata.group)
        assertEquals("Item 1", metadata.title)
    }

    @Test
    fun `requestAll files every item in the order it was given`() = runTest {
        val core = core()
        core.requestAll(
            listOf(
                DownloadCommand("a", "https://x/a.mp3", null, DownloadMetadata("A", null, "g")),
                DownloadCommand("b", "https://x/b.mp3", null, DownloadMetadata("B", null, "g")),
            ),
        )
        assertEquals(listOf("a", "b"), commands.added.map { it.first })
    }

    @Test
    fun `an id first seen by the manager reports started once, with its group`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED, group = "group:1"), null)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING, group = "group:1"), null)
        assertEquals(listOf("started:e1:group:1"), listener.events)
    }

    @Test
    fun `a completion reports the final size and how long it took, once`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING), null)
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 5_000, startTimeMs = 1_000, updateTimeMs = 13_000),
            null,
        )
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 5_000, startTimeMs = 1_000, updateTimeMs = 13_000),
            null,
        )
        assertEquals(listOf("started:e1:null", "completed:e1:5000:12"), listener.events)
    }

    @Test
    fun `a failure reports the classified reason, once`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING), null)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_FAILED), java.net.UnknownHostException())
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_FAILED), java.net.UnknownHostException())
        assertEquals(listOf("started:e1:null", "failed:e1:network"), listener.events)
    }

    @Test
    fun `a retry after a failure reports a start of its own`() = runTest {
        val core = core(settings = settings)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED, group = "group:1"), null)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING, group = "group:1"), null)
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_FAILED, group = "group:1"),
            java.net.UnknownHostException(),
        )
        // The retry button: the manager queues the same id again.
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED, group = "group:1"), null)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING, group = "group:1"), null)
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 900, group = "group:1"),
            null,
        )

        assertEquals(
            listOf(
                "started:e1:group:1",
                "failed:e1:network",
                "started:e1:group:1",
                "completed:e1:900:0",
            ),
            listener.events,
        )
    }

    @Test
    fun `a failure on its way out is not a retry`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_FAILED), java.net.UnknownHostException())
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_REMOVING), null)
        assertEquals(listOf("started:e1:null", "failed:e1:network"), listener.events)
    }

    @Test
    fun `a progress tick never counts as the start of a new attempt`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_FAILED), java.net.UnknownHostException())
        core.onProgress(listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 10)))
        assertEquals(listOf("started:e1:null", "failed:e1:network"), listener.events)
    }

    @Test
    fun `a removal reports the bytes that were freed and drops the row`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 4_000), null)
        core.onDownloadRemoved(snapshotOf("e1", Media3.STATE_REMOVING, contentLength = 4_000))
        assertTrue(listener.events.contains("removed:e1:4000"))
        assertEquals(emptyList<DownloadEntry>(), core.entries.value)
    }

    @Test
    fun `restoring the index does not replay started events`() = runTest {
        val core = core()
        core.onIndexLoaded(
            listOf(
                snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000),
                snapshotOf("e2", Media3.STATE_QUEUED),
            ),
        )
        assertEquals(emptyList<String>(), listener.events)
        assertEquals(listOf("e1", "e2"), core.entries.value.map { it.id })
    }

    @Test
    fun `state reports NotDownloaded for an id the index has never heard of`() = runTest {
        val core = core()
        assertEquals(DownloadState.NotDownloaded, core.state("nope").first())
    }

    @Test
    fun `state follows an id through the manager`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 300, percent = 30f), null)
        assertEquals(DownloadState.Downloading(0.3f, 300), core.state("e1").first())
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000, updateTimeMs = 7), null)
        assertEquals(DownloadState.Downloaded(1_000, 7), core.state("e1").first())
    }

    @Test
    fun `storage used tracks the index`() = runTest {
        val core = core()
        assertEquals(0L, core.storageUsed.value)
        core.onIndexLoaded(
            listOf(
                snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000),
                snapshotOf("e2", Media3.STATE_DOWNLOADING, bytes = 250),
            ),
        )
        assertEquals(1_250L, core.storageUsed.value)
    }

    @Test
    fun `removing a group removes every id in it and nothing else`() = runTest {
        val core = core()
        core.onIndexLoaded(
            listOf(
                snapshotOf("a", Media3.STATE_COMPLETED, group = "group:1"),
                snapshotOf("b", Media3.STATE_COMPLETED, group = "group:2"),
                snapshotOf("c", Media3.STATE_COMPLETED, group = "group:1"),
            ),
        )
        core.removeGroup("group:1")
        assertEquals(listOf("a", "c"), commands.removed)
    }

    @Test
    fun `clearing all reports the count and the bytes before asking the manager to wipe`() = runTest {
        val core = core()
        core.onIndexLoaded(
            listOf(
                snapshotOf("a", Media3.STATE_COMPLETED, contentLength = 1_000),
                snapshotOf("b", Media3.STATE_COMPLETED, contentLength = 2_000),
            ),
        )
        core.clearAll()
        assertEquals(listOf("cleared:2:3000"), listener.events)
        assertEquals(1, commands.removeAllCount)
    }

    @Test
    fun `clearing all reports itself once and not every row it wipes`() = runTest {
        val core = core()
        core.onIndexLoaded(
            listOf(
                snapshotOf("a", Media3.STATE_COMPLETED, contentLength = 1_000),
                snapshotOf("b", Media3.STATE_COMPLETED, contentLength = 2_000),
            ),
        )
        core.clearAll()
        // What the manager does next: one removal callback per row.
        core.onDownloadRemoved(snapshotOf("a", Media3.STATE_REMOVING, contentLength = 1_000))
        core.onDownloadRemoved(snapshotOf("b", Media3.STATE_REMOVING, contentLength = 2_000))

        assertEquals(listOf("cleared:2:3000"), listener.events)
        assertEquals(emptyList<DownloadEntry>(), core.entries.value)
        assertEquals(0L, core.storageUsed.value)
    }

    @Test
    fun `a removal after the clear has drained reports itself again`() = runTest {
        val core = core()
        core.onIndexLoaded(listOf(snapshotOf("a", Media3.STATE_COMPLETED, contentLength = 1_000)))
        core.clearAll()
        core.onDownloadRemoved(snapshotOf("a", Media3.STATE_REMOVING, contentLength = 1_000))

        core.onDownloadChanged(snapshotOf("c", Media3.STATE_COMPLETED, contentLength = 500), null)
        core.onDownloadRemoved(snapshotOf("c", Media3.STATE_REMOVING, contentLength = 500))

        assertEquals(
            listOf("cleared:1:1000", "started:c:null", "completed:c:500:0", "removed:c:500"),
            listener.events,
        )
    }

    @Test
    fun `clearing an empty library reports nothing at all`() = runTest {
        val core = core()
        core.clearAll()
        assertEquals(emptyList<String>(), listener.events)
        assertEquals(1, commands.removeAllCount)
    }

    @Test
    fun `pause and resume are passed straight through`() = runTest {
        val core = core()
        core.pauseAll()
        core.resumeAll()
        assertEquals(1, commands.pauseCount)
        assertEquals(1, commands.resumeCount)
    }

    @Test
    fun `the wifi only flag starts from what the host persisted`() = runTest {
        val core = core(FakeSettings(false))
        assertEquals(false, core.wifiOnly.value)
    }

    /**
     * The host installs this from `Application.onCreate`, where a preferences file is
     * parsed on the main thread — and that `onCreate` is already where an app's ANRs
     * cluster. So the core is built without reading anything at all.
     */
    @Test
    fun `constructing the core reads no setting`() = runTest {
        val settings = FakeSettings(false)
        DownloadsCore(commands, settings, coreScope(), UnconfinedTestDispatcher(testScheduler))
        assertEquals(0, settings.reads)
        assertEquals(emptyList<Int>(), commands.requirements)
    }

    /**
     * A download must NEVER start on metered data because the setting had not loaded
     * yet, so the flag holds Wi-Fi-only until the read lands — and the read is on a
     * dispatcher, which is what the paused one here stands in for.
     */
    @Test
    fun `the wifi only flag stays restrictive until the persisted one has been read`() = runTest {
        val settings = FakeSettings(false)
        withPausedCore(settings) { core ->
            core.start()

            assertEquals(true, core.wifiOnly.value)
            assertEquals(0, settings.reads)
            assertEquals(emptyList<Int>(), commands.requirements)

            advanceUntilIdle()
            assertEquals(false, core.wifiOnly.value)
            assertEquals(listOf(requirementsMask(wifiOnly = false)), commands.requirements)
        }
    }

    /** Somebody who reached the toggle before the disk did keeps their choice. */
    @Test
    fun `a choice made before the persisted flag lands survives it`() = runTest {
        withPausedCore(FakeSettings(true)) { core ->
            core.start()
            core.wifiOnly.value = false

            advanceUntilIdle()
            assertEquals(false, core.wifiOnly.value)
            assertEquals(listOf(requirementsMask(wifiOnly = false)), commands.requirements)
        }
    }

    @Test
    fun `flipping the wifi only flag persists it and re-arms the requirements`() = runTest {
        val core = core()
        commands.requirements.clear()
        core.wifiOnly.value = false
        assertEquals(listOf(requirementsMask(wifiOnly = false)), commands.requirements)
        assertEquals(1, settings.writes)
    }

    @Test
    fun `the requirements are armed once at startup from the persisted flag`() = runTest {
        core()
        assertEquals(listOf(requirementsMask(wifiOnly = true)), commands.requirements)
    }

    /**
     * The host builds this core from inside its own constructor, so nothing here may
     * call back out until that constructor has returned: the commands reach a download
     * manager that is a `by lazy` field of the very object still being built, and its
     * delegate is not assigned yet.
     */
    @Test
    fun `a core that has not been started reaches the manager for nothing`() = runTest {
        val core = core(started = false)
        assertEquals(emptyList<Int>(), commands.requirements)
        assertEquals(true, core.wifiOnly.value)

        core.start()
        assertEquals(listOf(requirementsMask(wifiOnly = true)), commands.requirements)
    }

    @Test
    fun `a removed listener stops hearing about downloads`() = runTest {
        val core = core()
        core.removeListener(listener)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED), null)
        assertEquals(emptyList<String>(), listener.events)
    }

    @Test
    fun `every listener hears every event`() = runTest {
        val core = core()
        val second = RecordingListener()
        core.addListener(second)
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED, group = "g"), null)
        assertEquals(listener.events, second.events)
    }

    @Test
    fun `entries keep the order they were first seen in`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("b", Media3.STATE_QUEUED), null)
        core.onDownloadChanged(snapshotOf("a", Media3.STATE_QUEUED), null)
        core.onDownloadChanged(snapshotOf("b", Media3.STATE_DOWNLOADING), null)
        assertEquals(listOf("b", "a"), core.entries.value.map { it.id })
    }

    // --- the progress poll racing the manager's own callbacks -----------------
    //
    // media3 reports state changes but never bytes, so the ring is driven by a poll
    // of `currentDownloads` read on the manager's looper and applied on the core's
    // own dispatcher. A terminal callback can land between those two moments, and
    // the snapshot the poll is still holding is then a description of the past.

    @Test
    fun `a progress tick captured before a completion cannot undo it`() = runTest {
        val core = core()
        val inFlight = snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 400, percent = 40f)
        core.onDownloadChanged(inFlight, null)
        // The completion arrives while the poll is still carrying `inFlight`.
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000, updateTimeMs = 9),
            null,
        )
        core.onProgress(listOf(inFlight))

        assertEquals(DownloadState.Downloaded(1_000, 9), core.entries.value.single().state)
        assertEquals(listOf("started:e1:null", "completed:e1:1000:0"), listener.events)
    }

    @Test
    fun `a progress tick captured before a removal cannot bring the row back`() = runTest {
        val core = core()
        val inFlight = snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 400, percent = 40f)
        core.onDownloadChanged(inFlight, null)
        core.onDownloadRemoved(snapshotOf("e1", Media3.STATE_REMOVING, bytes = 400))
        core.onProgress(listOf(inFlight))

        assertEquals(emptyList<DownloadEntry>(), core.entries.value)
        // And no second `started`, which is what a resurrected row used to report.
        assertEquals(listOf("started:e1:null", "removed:e1:400"), listener.events)
    }

    @Test
    fun `a progress tick moves the bytes of a download that is still running`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED), null)
        core.onProgress(
            listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 512, percent = 25f)),
        )
        assertEquals(DownloadState.Downloading(0.25f, 512), core.entries.value.single().state)
        // A tick is progress, never a first sighting: nothing new was reported.
        assertEquals(listOf("started:e1:null"), listener.events)
    }

    @Test
    fun `a progress tick for an id the core has never seen is ignored`() = runTest {
        val core = core()
        core.onProgress(listOf(snapshotOf("ghost", Media3.STATE_DOWNLOADING, bytes = 10)))
        assertEquals(emptyList<DownloadEntry>(), core.entries.value)
        assertEquals(emptyList<String>(), listener.events)
    }

    @Test
    fun `an index read that finished after a newer callback does not undo it`() = runTest {
        val core = core()
        // The cursor was opened while e1 was still downloading.
        val read = core.markRead()
        val stale = listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 400, updateTimeMs = 5))
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000, updateTimeMs = 11),
            null,
        )
        core.onIndexLoaded(stale, read)

        assertEquals(DownloadState.Downloaded(1_000, 11), core.entries.value.single().state)
    }

    @Test
    fun `an index read cannot restore a download that was removed while it ran`() = runTest {
        val core = core()
        val row = snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000, updateTimeMs = 5)
        core.onIndexLoaded(listOf(row))
        val read = core.markRead()
        core.onDownloadRemoved(snapshotOf("e1", Media3.STATE_REMOVING, contentLength = 1_000))
        core.onIndexLoaded(listOf(row), read)

        assertEquals(emptyList<DownloadEntry>(), core.entries.value)
    }

    /**
     * The row that matters most is the one the cursor never saw: a download that
     * completed while the startup read was open. The old restore rebuilt the list from
     * the snapshot alone, so it vanished for the rest of the process.
     */
    @Test
    fun `a download finished while the index was being read is still listed`() = runTest {
        val core = core()
        val read = core.markRead()
        core.onDownloadChanged(snapshotOf("new", Media3.STATE_COMPLETED, contentLength = 500), null)
        core.onIndexLoaded(listOf(snapshotOf("old", Media3.STATE_COMPLETED, contentLength = 10)), read)

        assertEquals(setOf("old", "new"), core.entries.value.map { it.id }.toSet())
        assertEquals(510L, core.storageUsed.value)
    }

    @Test
    fun `a newer callback wins even when its clock reads earlier than the snapshot`() = runTest {
        val core = core()
        val read = core.markRead()
        // Wall clocks move backwards (a time-zone or NTP correction). Order comes from
        // when the core heard things, never from updateTimeMs.
        core.onDownloadChanged(
            snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000, updateTimeMs = 1),
            null,
        )
        core.onIndexLoaded(
            listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 400, updateTimeMs = 99)),
            read,
        )

        assertEquals(DownloadState.Downloaded(1_000, 1), core.entries.value.single().state)
    }

    @Test
    fun `an older index read landing after a newer one is thrown away`() = runTest {
        val core = core()
        val startup = core.markRead()
        val initialized = core.markRead()
        core.onIndexLoaded(
            listOf(
                snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000),
                snapshotOf("e2", Media3.STATE_COMPLETED, contentLength = 20),
            ),
            initialized,
        )
        core.onIndexLoaded(listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 400)), startup)

        assertEquals(listOf("e1", "e2"), core.entries.value.map { it.id })
        assertEquals(DownloadState.Downloaded(1_000, 0), core.entries.value.first().state)
    }

    @Test
    fun `a progress tick read before a download was parked cannot mark it running again`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 400, percent = 40f), null)
        val read = core.markRead()
        // Wi-Fi went away: media3 parks the transfer back at Queued.
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED, bytes = 400), null)
        core.onProgress(listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 450, percent = 45f)), read)

        assertTrue(core.entries.value.single().state is DownloadState.Queued)
    }

    @Test
    fun `filing the same id again after a removal puts it back`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000), null)
        core.onDownloadRemoved(snapshotOf("e1", Media3.STATE_REMOVING, contentLength = 1_000))
        core.onDownloadChanged(snapshotOf("e1", Media3.STATE_QUEUED, updateTimeMs = 20), null)
        core.onIndexLoaded(listOf(snapshotOf("e1", Media3.STATE_QUEUED, updateTimeMs = 20)))

        assertEquals(listOf("e1"), core.entries.value.map { it.id })
    }

    @Test
    fun `all exposes the grouped view the offline screen draws`() = runTest {
        val core = core()
        core.onIndexLoaded(
            listOf(
                snapshotOf("a", Media3.STATE_COMPLETED, contentLength = 10, group = "group:1"),
                snapshotOf("b", Media3.STATE_COMPLETED, contentLength = 20, group = "group:1"),
            ),
        )
        val groups = groupDownloads(core.all().first())
        assertEquals(1, groups.size)
        assertEquals(30L, groups.single().bytes)
    }

    @Test
    fun `the indexed ids are unknown until the index has been read, then every state counts`() = runTest {
        val core = core()
        core.onDownloadChanged(snapshotOf("early", Media3.STATE_QUEUED), null)
        // A callback is not the index: the host must still hear "not loaded yet".
        assertEquals(null, core.indexedIds())

        core.onIndexLoaded(
            listOf(
                snapshotOf("done", Media3.STATE_COMPLETED, contentLength = 10),
                snapshotOf("broken", Media3.STATE_FAILED),
            ),
        )
        assertEquals(setOf("done", "broken"), core.indexedIds())
    }

    /**
     * Progress ticks and callbacks arrive on different threads. Each publication used to
     * copy the rows under the lock and assign the flows after letting go of it, so a tick
     * that copied "downloading" could land its copy AFTER a completion had published, and
     * the list stayed at 99% for a download that was finished. Publication now happens
     * under the lock, so the last assignment is always the latest state.
     */
    @Test
    fun `a completion racing a progress tick on another thread is never overwritten`() = runTest {
        repeat(300) { round ->
            val core = DownloadsCore(commands, settings, coreScope(), UnconfinedTestDispatcher(testScheduler))
            core.onDownloadChanged(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = 1), null)
            val start = java.util.concurrent.CountDownLatch(1)
            val ticker = Thread {
                start.await()
                for (k in 2L..400L) {
                    core.onProgress(listOf(snapshotOf("e1", Media3.STATE_DOWNLOADING, bytes = k)))
                }
            }
            val finisher = Thread {
                start.await()
                Thread.yield()
                core.onDownloadChanged(snapshotOf("e1", Media3.STATE_COMPLETED, contentLength = 1_000), null)
            }
            ticker.start()
            finisher.start()
            start.countDown()
            ticker.join()
            finisher.join()
            assertEquals(
                "round $round",
                DownloadState.Downloaded(1_000, 0),
                core.entries.value.single().state,
            )
        }
    }
}
