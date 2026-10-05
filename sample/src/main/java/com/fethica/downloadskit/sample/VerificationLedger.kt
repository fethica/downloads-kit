package com.fethica.downloadskit.sample

/**
 * Which verification results still describe the download on disk.
 *
 * A result is only true of the bytes it read. It belongs to one id, one cache key and
 * one download generation: a new request, a removal or a clear bumps the generation and
 * drops the result, and a check still running when that happens is discarded when it
 * lands instead of being shown beside a download it never read.
 *
 * Plain Kotlin with no Android types, so it is tested on the JVM. Thread-safe.
 */
internal class VerificationLedger<R> {

    sealed interface Status<out R> {
        val cacheKey: String

        data class Running(override val cacheKey: String) : Status<Nothing>
        data class Done<R>(override val cacheKey: String, val result: R) : Status<R>
    }

    /** A check in progress: what it was started against. */
    data class Ticket(val id: String, val cacheKey: String, val generation: Long)

    private val lock = Any()
    /** Hands out every generation, so no two invalidations ever share one. */
    private var counter = 0L

    /** The generation of every id not invalidated on its own since the last clear. */
    private var epoch = 0L
    private val generations = HashMap<String, Long>()
    private val statuses = LinkedHashMap<String, Status<R>>()

    /** The current status per id; ids with no valid result are absent. */
    fun snapshot(): Map<String, Status<R>> = synchronized(lock) { LinkedHashMap(statuses) }

    /** Starts a check of [id] against [cacheKey]. */
    fun begin(id: String, cacheKey: String): Ticket = synchronized(lock) {
        statuses[id] = Status.Running(cacheKey)
        Ticket(id, cacheKey, generationOf(id))
    }

    /**
     * Records the result of [ticket]'s check, unless the download it read has been
     * requested again, removed or cleared since it began.
     *
     * @return whether the result was kept.
     */
    fun finish(ticket: Ticket, result: R): Boolean = synchronized(lock) {
        val current = statuses[ticket.id]
        val fresh = generationOf(ticket.id) == ticket.generation && current?.cacheKey == ticket.cacheKey
        if (fresh) statuses[ticket.id] = Status.Done(ticket.cacheKey, result)
        fresh
    }

    /** [id] was requested again or removed: whatever was known about it is stale. */
    fun invalidate(id: String) {
        synchronized(lock) {
            generations[id] = ++counter
            statuses.remove(id)
        }
    }

    /** Everything was cleared. */
    fun invalidateAll() {
        synchronized(lock) {
            epoch = ++counter
            generations.clear()
            statuses.clear()
        }
    }

    private fun generationOf(id: String): Long = generations[id] ?: epoch
}
