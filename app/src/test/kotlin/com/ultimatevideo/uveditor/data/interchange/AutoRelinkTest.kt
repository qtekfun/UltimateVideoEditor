package com.ultimatevideo.uveditor.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRelinkTest {
    private fun wanted(id: String, name: String, size: Long) = BundleMedia(id, name, size)

    @Test
    fun `a file with the same name and size is matched, ignoring case`() {
        val found = AutoRelink.match(
            listOf(wanted("a1", "Interview.MP4", 5000)),
            listOf(RelinkCandidate("content://x/1", "interview.mp4", 5000)),
        )
        assertEquals(mapOf("a1" to "content://x/1"), found)
    }

    @Test
    fun `a name alone is never enough`() {
        val found = AutoRelink.match(
            listOf(wanted("a1", "clip.mp4", 5000), wanted("a2", "other.mp4", 100)),
            listOf(RelinkCandidate("u1", "clip.mp4", 4999), RelinkCandidate("u2", "other.mp4", null)),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `unknown sizes and blank names never match`() {
        val found = AutoRelink.match(
            listOf(wanted("a1", "clip.mp4", -1), wanted("a2", "  ", 10)),
            listOf(RelinkCandidate("u1", "clip.mp4", -1), RelinkCandidate("u2", "  ", 10)),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `the first candidate wins when several match`() {
        val found = AutoRelink.match(
            listOf(wanted("a1", "clip.mp4", 10)),
            listOf(RelinkCandidate("first", "clip.mp4", 10), RelinkCandidate("second", "clip.mp4", 10)),
        )
        assertEquals("first", found["a1"])
    }

    @Test
    fun `each wanted file is matched on its own`() {
        val found = AutoRelink.match(
            listOf(wanted("a1", "a.mp4", 1), wanted("a2", "b.mp4", 2), wanted("a3", "c.mp4", 3)),
            listOf(RelinkCandidate("ub", "b.mp4", 2), RelinkCandidate("ua", "a.mp4", 1)),
        )
        assertEquals(mapOf("a1" to "ua", "a2" to "ub"), found)
    }
}
