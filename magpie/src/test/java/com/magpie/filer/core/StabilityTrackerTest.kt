package com.magpie.filer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityTrackerTest {

    private val tracker = StabilityTracker(settleMillis = 2_200)

    @Test
    fun `a file is never called finished on first sight`() {
        assertFalse(tracker.observe("/a.zip", 4_096, now = 0))
    }

    @Test
    fun `a file that has stopped growing is finished`() {
        assertFalse(tracker.observe("/a.zip", 4_096, now = 0))
        assertTrue(tracker.observe("/a.zip", 4_096, now = 2_500))
    }

    @Test
    fun `two looks too close together are not enough`() {
        assertFalse(tracker.observe("/a.zip", 4_096, now = 0))
        assertFalse(tracker.observe("/a.zip", 4_096, now = 1_000))
    }

    @Test
    fun `a file still growing keeps resetting the clock`() {
        assertFalse(tracker.observe("/a.zip", 1_000, now = 0))
        assertFalse(tracker.observe("/a.zip", 5_000, now = 3_000))
        assertFalse(tracker.observe("/a.zip", 9_000, now = 6_000))
        assertTrue(tracker.observe("/a.zip", 9_000, now = 9_000))
    }

    @Test
    fun `an empty file is never finished, however long it sits there`() {
        assertFalse(tracker.observe("/a.zip", 0, now = 0))
        assertFalse(tracker.observe("/a.zip", 0, now = 60_000))
    }

    @Test
    fun `forgetting a file starts it over`() {
        assertFalse(tracker.observe("/a.zip", 10, now = 0))
        tracker.forget("/a.zip")
        assertFalse(tracker.observe("/a.zip", 10, now = 2_500))
        assertTrue(tracker.observe("/a.zip", 10, now = 5_000))
    }

    @Test
    fun `files that vanish stop being tracked`() {
        tracker.observe("/a.zip", 10, now = 0)
        tracker.observe("/b.zip", 10, now = 0)
        assertEquals(2, tracker.pending)
        tracker.retainOnly(setOf("/a.zip"))
        assertEquals(1, tracker.pending)
    }
}
