package com.qtekfun.ultimatevideoeditor.data.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentKeyTest {
    private val auth = "com.android.externalstorage.documents"

    @Test
    fun `a file has one key whether it came from the picker or from a linked folder`() {
        val picked = "content://$auth/document/primary%3AMovies%2FTrip%2Fa.mp4"
        val linked = "content://$auth/tree/primary%3AMovies/document/primary%3AMovies%2FTrip%2Fa.mp4"
        assertEquals(DocumentKey(auth, "primary:Movies/Trip/a.mp4"), DocumentKey.of(picked))
        assertEquals(DocumentKey.of(picked), DocumentKey.of(linked))
    }

    @Test
    fun `different files and different providers have different keys`() {
        assertTrue(DocumentKey.of("content://a/document/x") != DocumentKey.of("content://a/document/y"))
        assertTrue(DocumentKey.of("content://a/document/x") != DocumentKey.of("content://b/document/x"))
    }

    @Test
    fun `a plus in an id is a plus and a percent escape is decoded`() {
        assertEquals("a+b c", DocumentKey.of("content://a/document/a%2Bb%20c")!!.documentId)
    }

    @Test
    fun `things that are not documents have no key`() {
        assertNull(DocumentKey.of("file:///sdcard/a.mp4"))
        assertNull(DocumentKey.of("content://a/tree/primary%3AMovies"))
        assertNull(DocumentKey.of("content://a/document/"))
        assertNull(DocumentKey.of("content://a/document"))
        assertNull(DocumentKey.of("not a uri"))
    }

    @Test
    fun `only a document inside a tree is a tree document`() {
        assertTrue(DocumentKey.isTreeDocument("content://$auth/tree/primary%3AMovies/document/primary%3AMovies%2Fa.mp4"))
        assertFalse(DocumentKey.isTreeDocument("content://$auth/document/primary%3AMovies%2Fa.mp4"))
        assertFalse(DocumentKey.isTreeDocument("content://$auth/tree/primary%3AMovies"))
        assertFalse(DocumentKey.isTreeDocument("file:///a/tree/b/document/c"))
    }

    @Test
    fun `the tree of a tree document is found, and nothing else has one`() {
        val doc = "content://$auth/tree/primary%3AMovies/document/primary%3AMovies%2Fa.mp4"
        assertEquals("content://$auth/tree/primary%3AMovies", DocumentKey.treeOf(doc))
        assertNull(DocumentKey.treeOf("content://$auth/document/x"))
    }

    @Test
    fun `a tree id and a default name come from the uri`() {
        assertEquals("primary:Movies/Trips", DocumentKey.treeIdOf("content://$auth/tree/primary%3AMovies%2FTrips"))
        assertEquals("Trips", DocumentKey.lastNameOf("primary:Movies/Trips"))
        assertEquals("Movies", DocumentKey.lastNameOf("primary:Movies/"))
        assertEquals("primary", DocumentKey.lastNameOf("primary:"))
        assertNull(DocumentKey.treeIdOf("content://$auth/document/x"))
    }
}
