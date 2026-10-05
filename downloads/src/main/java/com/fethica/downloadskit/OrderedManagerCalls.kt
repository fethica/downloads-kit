package com.fethica.downloadskit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * One queue for every call that drives the download manager directly, applied in the
 * order the calls were made.
 *
 * Building the manager touches SQLite and the cache index, so it is resolved off the
 * main thread, once; each call then runs on [target], the looper the manager was built
 * against. A fresh coroutine per call used to do both steps on its own, and nothing
 * ordered two such coroutines: flipping Wi-Fi-only off and back on could apply the
 * restrictive mask first and the permissive one last, leaving metered transfers allowed
 * while the switch said Wi-Fi only. The last call made is now always the last applied.
 *
 * @param resolve returns the manager; called once, on whatever [scope] runs.
 */
internal class OrderedManagerCalls<M>(
    scope: CoroutineScope,
    resolve: suspend () -> M,
    private val target: CoroutineContext,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val queue = Channel<(M) -> Unit>(Channel.UNLIMITED)

    init {
        val consumer = scope.launch {
            val manager = resolve()
            for (call in queue) {
                try {
                    withContext(target) { call(manager) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A watcher or manager failure belongs to this command. Letting it
                    // escape would strand every later command in the unlimited queue.
                    onFailure(e)
                }
            }
        }
        consumer.invokeOnCompletion { cause -> queue.close(cause) }
    }

    fun submit(call: (M) -> Unit) {
        queue.trySend(call).exceptionOrNull()?.let(onFailure)
    }
}
