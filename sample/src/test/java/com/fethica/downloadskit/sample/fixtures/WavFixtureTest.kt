package com.fethica.downloadskit.sample.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFixtureTest {

    @Test
    fun everyFixtureHasAValidPcmHeader() {
        for (fixture in FixtureCatalog.all) {
            val bytes = fixture.bytes()
            assertEquals(fixture.sizeBytes, bytes.size)
            val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(bytes.size - 8, le.getInt(4))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals(16, le.getInt(16))
            assertEquals(1, le.getShort(20).toInt()) // PCM
            assertEquals(1, le.getShort(22).toInt()) // mono
            assertEquals(8_000, le.getInt(24))
            assertEquals(16_000, le.getInt(28)) // byte rate
            assertEquals(2, le.getShort(32).toInt()) // block align
            assertEquals(16, le.getShort(34).toInt())
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
            assertEquals(bytes.size - WavFixture.HEADER_BYTES, le.getInt(40))
        }
    }

    @Test
    fun aToneThenSilence() {
        for (fixture in FixtureCatalog.all) {
            val le = ByteBuffer.wrap(fixture.bytes()).order(ByteOrder.LITTLE_ENDIAN)
            val toneSamples = WavFixture.SAMPLE_RATE * fixture.toneMillis / 1000
            var peak = 0
            for (n in 0 until toneSamples) peak = maxOf(peak, kotlin.math.abs(le.getShort(44 + n * 2).toInt()))
            assertTrue("tone peak $peak", peak in 3_900..4_100)
            val samples = (fixture.sizeBytes - WavFixture.HEADER_BYTES) / 2
            for (n in toneSamples until samples) assertEquals(0, le.getShort(44 + n * 2).toInt())
        }
    }

    @Test
    fun sha256IsStableAcrossTwoGenerations() {
        for (fixture in FixtureCatalog.all) {
            val again = WavFixture(fixture.name, fixture.seed, fixture.sizeBytes, fixture.toneHz, fixture.toneMillis)
            assertEquals(sha256Hex(fixture.bytes()), sha256Hex(again.bytes()))
            assertTrue(fixture.bytes().contentEquals(again.bytes()))
            assertEquals(fixture.sha256, again.sha256)
        }
    }

    @Test
    fun sha256MatchesThePinnedValues() {
        for (fixture in FixtureCatalog.all) {
            assertEquals(fixture.name, FixtureCatalog.PINNED_SHA256[fixture.name], fixture.sha256)
        }
    }

    @Test
    fun theSeedChangesTheBytes() {
        val a = FixtureCatalog.small
        val b = WavFixture(a.name, a.seed + 1, a.sizeBytes, a.toneHz, a.toneMillis)
        assertTrue(a.sha256 != b.sha256)
    }

    @Test
    fun catalogSizesNamesAndPathsAreStable() {
        assertEquals(listOf(65_536, 262_144, 1_048_576), FixtureCatalog.all.map { it.sizeBytes })
        assertEquals(listOf("tone-small", "tone-medium", "tone-large"), FixtureCatalog.all.map { it.name })
        assertEquals("/fixtures/tone-medium.wav", FixtureCatalog.medium.path)
        assertEquals(FixtureCatalog.large, FixtureCatalog.byPath("/fixtures/tone-large.wav"))
    }
}
