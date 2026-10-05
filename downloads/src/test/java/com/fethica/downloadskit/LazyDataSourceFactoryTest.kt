package com.fethica.downloadskit

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What keeps the download cache off the main thread: the player is handed a factory in
 * `Service.onCreate` that has not opened anything yet.
 */
@OptIn(UnstableApi::class)
class LazyDataSourceFactoryTest {

    private val builds = AtomicInteger()

    private fun factory() = LazyDataSourceFactory {
        builds.incrementAndGet()
        DataSource.Factory { ByteArrayDataSource(ByteArray(1)) }
    }

    @Test
    fun `handing the factory around builds nothing`() {
        factory()
        assertEquals(0, builds.get())
    }

    @Test
    fun `the first data source asked for is what builds it`() {
        val factory = factory()
        factory.createDataSource()
        assertEquals(1, builds.get())
        factory.createDataSource()
        assertEquals(1, builds.get())
    }

    /**
     * Two loading threads can reach a cold factory at once, and media3 throws if a
     * second `SimpleCache` opens a folder that already has one — so however many ask,
     * one thing is built.
     */
    @Test
    fun `threads asking at the same time build one factory between them`() {
        val factory = factory()
        val ready = CountDownLatch(THREADS)
        val go = CountDownLatch(1)
        val threads = List(THREADS) {
            Thread {
                ready.countDown()
                go.await()
                factory.createDataSource()
            }.apply { start() }
        }

        ready.await(WAIT_SECONDS, TimeUnit.SECONDS)
        go.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

        assertEquals(1, builds.get())
    }

    private companion object {
        const val THREADS = 8
        const val WAIT_SECONDS = 5L
    }
}
