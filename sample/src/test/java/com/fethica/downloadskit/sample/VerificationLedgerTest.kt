package com.fethica.downloadskit.sample

import com.fethica.downloadskit.sample.VerificationLedger.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A verdict is shown only beside the download it actually read. */
class VerificationLedgerTest {

    private val ledger = VerificationLedger<String>()

    @Test
    fun aFinishedCheckIsShownWithItsKey() {
        val ticket = ledger.begin("small", "http://127.0.0.1:1/small")
        assertEquals(Status.Running("http://127.0.0.1:1/small"), ledger.snapshot()["small"])
        assertTrue(ledger.finish(ticket, "pass"))
        assertEquals(Status.Done("http://127.0.0.1:1/small", "pass"), ledger.snapshot()["small"])
    }

    @Test
    fun removingTheDownloadDropsItsResult() {
        ledger.finish(ledger.begin("small", "k"), "pass")
        ledger.invalidate("small")
        assertNull(ledger.snapshot()["small"])
    }

    @Test
    fun clearingAllDropsEveryResult() {
        ledger.finish(ledger.begin("small", "k1"), "pass")
        ledger.finish(ledger.begin("large", "k2"), "pass")
        ledger.invalidateAll()
        assertEquals(emptyMap<String, Status<String>>(), ledger.snapshot())
    }

    @Test
    fun aCheckThatLandsAfterARemovalIsDiscarded() {
        val ticket = ledger.begin("small", "k")
        ledger.invalidate("small")
        assertFalse(ledger.finish(ticket, "pass"))
        assertNull(ledger.snapshot()["small"])
    }

    @Test
    fun aCheckThatLandsAfterAClearIsDiscarded() {
        val ticket = ledger.begin("small", "k")
        ledger.invalidateAll()
        assertFalse(ledger.finish(ticket, "pass"))
        assertNull(ledger.snapshot()["small"])
    }

    @Test
    fun aCheckThatLandsAfterANewRequestAndANewCheckIsDiscarded() {
        val old = ledger.begin("small", "k-old")
        ledger.invalidate("small") // requested again
        val fresh = ledger.begin("small", "k-new")
        assertFalse(ledger.finish(old, "old pass"))
        assertEquals(Status.Running("k-new"), ledger.snapshot()["small"])
        assertTrue(ledger.finish(fresh, "new pass"))
        assertEquals(Status.Done("k-new", "new pass"), ledger.snapshot()["small"])
    }

    @Test
    fun aClearAfterPerIdInvalidationsStillDiscardsOlderChecks() {
        ledger.invalidate("small")
        val ticket = ledger.begin("small", "k")
        ledger.invalidateAll()
        val after = ledger.begin("small", "k")
        assertFalse(ledger.finish(ticket, "stale"))
        assertTrue(ledger.finish(after, "pass"))
    }

    @Test
    fun invalidatingOneIdLeavesTheOthers() {
        ledger.finish(ledger.begin("small", "k1"), "pass")
        val running = ledger.begin("large", "k2")
        ledger.invalidate("small")
        assertTrue(ledger.finish(running, "pass"))
        assertEquals(setOf("large"), ledger.snapshot().keys)
    }
}
