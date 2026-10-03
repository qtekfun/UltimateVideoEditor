package com.ultimatevideo.uveditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionTrimTest {

    private class FakeUris(override val limit: Int, held: List<String>) : PersistedUris {
        val current = held.toMutableList()
        val released = mutableListOf<String>()

        override fun held(): List<String> = current.toList()

        override fun release(uri: String) {
            current -= uri
            released += uri
        }
    }

    @Test
    fun `near the limit means at least 80 percent of it`() {
        assertFalse(PermissionTrim.nearLimit(held = 408, limit = 512))
        assertTrue(PermissionTrim.nearLimit(held = 409, limit = 512))
        assertFalse(PermissionTrim.nearLimit(held = 10, limit = 0))
    }

    @Test
    fun `unused keeps what a project refers to`() {
        assertEquals(listOf("b", "d"), PermissionTrim.unused(listOf("a", "b", "c", "d"), setOf("a", "c")))
    }

    @Test
    fun `well under the limit nothing is released`() {
        val uris = FakeUris(limit = 100, held = (1..50).map { "u$it" })

        assertEquals(0, trimPersistedUris(uris, referenced = emptySet()))
        assertTrue(uris.released.isEmpty())
    }

    @Test
    fun `near the limit only unreferenced permissions are released`() {
        val uris = FakeUris(limit = 10, held = (1..9).map { "u$it" })
        val messages = mutableListOf<String>()

        val released = trimPersistedUris(uris, referenced = setOf("u1", "u2", "u3")) { messages += it }

        assertEquals(6, released)
        assertEquals(listOf("u1", "u2", "u3"), uris.current)
        assertEquals(1, messages.size)
    }

    @Test
    fun `permissions every project needs are never released even at the limit`() {
        val uris = FakeUris(limit = 4, held = listOf("a", "b", "c", "d"))

        assertEquals(0, trimPersistedUris(uris, referenced = setOf("a", "b", "c", "d")))
        assertEquals(4, uris.current.size)
    }
}
