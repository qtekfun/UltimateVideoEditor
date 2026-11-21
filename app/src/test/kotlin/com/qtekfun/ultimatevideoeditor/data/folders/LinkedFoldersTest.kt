package com.qtekfun.ultimatevideoeditor.data.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LinkedFoldersTest {
    private val auth = "com.android.externalstorage.documents"
    private val movies = LinkedFolder("lf-1", "content://$auth/tree/primary%3AMovies", "Movies")
    private val sd = LinkedFolder("lf-2", "content://$auth/tree/1234-ABCD%3ARushes", "Rushes")

    @Test
    fun `folders are added at the end and removed by id`() {
        val list = LinkedFolders.add(LinkedFolders.add(emptyList(), movies), sd)
        assertEquals(listOf(movies, sd), list)
        assertEquals(listOf(sd), LinkedFolders.remove(list, "lf-1"))
        assertEquals(list, LinkedFolders.remove(list, "nope"))
    }

    @Test
    fun `linking the same folder again changes nothing`() {
        val list = listOf(movies)
        assertSame(list, LinkedFolders.add(list, movies.copy(id = "lf-9", label = "Again")))
        assertSame(list, LinkedFolders.add(list, movies.copy(treeUri = "content://$auth/tree/primary:Movies")))
    }

    @Test
    fun `the same folder of another provider is another folder`() {
        val other = movies.copy(id = "lf-3", treeUri = "content://other.provider/tree/primary%3AMovies")
        assertEquals(2, LinkedFolders.add(listOf(movies), other).size)
    }

    @Test
    fun `renaming keeps the old label when the new one is blank`() {
        val list = listOf(movies, sd)
        assertEquals("Holiday", LinkedFolders.rename(list, "lf-1", "  Holiday ")[0].label)
        assertSame(list, LinkedFolders.rename(list, "lf-1", "   "))
        assertEquals(list, LinkedFolders.rename(list, "nope", "X"))
    }

    @Test
    fun `a folder is named after itself, not its whole path`() {
        assertEquals("Trips", LinkedFolders.defaultLabel("content://$auth/tree/primary%3AMovies%2FTrips"))
        assertEquals("Rushes", LinkedFolders.defaultLabel(sd.treeUri))
    }

    @Test
    fun `the tree uris are what the permission trim must keep`() {
        assertEquals(setOf(movies.treeUri, sd.treeUri), LinkedFolders.treeUris(listOf(movies, sd)))
    }

    @Test
    fun `the list survives a round trip, even with awkward characters in a label`() {
        val awkward = LinkedFolder("lf-3", "content://$auth/tree/x", "50% off\tand\nmore\r%09")
        val list = listOf(movies, awkward, sd)
        assertEquals(list, LinkedFoldersCodec.decode(LinkedFoldersCodec.encode(list)))
    }

    @Test
    fun `bad lines are skipped and duplicates dropped when the list is read`() {
        val text = listOf(
            "lf-1\t${movies.treeUri}\tMovies",
            "garbage",
            "\t${sd.treeUri}\tno id",
            "lf-4\tfile:///not/a/tree\tbad uri",
            "lf-5\t${movies.treeUri}\tduplicate of the first",
            "lf-2\t${sd.treeUri}\tRushes",
        ).joinToString("\n")
        assertEquals(listOf(movies, sd), LinkedFoldersCodec.decode(text))
        assertEquals(emptyList<LinkedFolder>(), LinkedFoldersCodec.decode(null))
        assertEquals(emptyList<LinkedFolder>(), LinkedFoldersCodec.decode("  "))
    }

    @Test
    fun `a browse position survives a round trip with awkward names`() {
        val position = BrowsePosition("lf-1", listOf("primary:Rushes" to "Rushes", "primary:Rushes/50%\tday" to "50% off\nday", "primary:Rushes/a/b" to "b"))
        assertEquals(position, BrowsePositionCodec.decode(BrowsePositionCodec.encode(position)))
    }

    @Test
    fun `a browse position that is damaged is not a position`() {
        assertEquals(null, BrowsePositionCodec.decode(null))
        assertEquals(null, BrowsePositionCodec.decode(""))
        assertEquals(null, BrowsePositionCodec.decode("lf-1"))
        assertEquals(null, BrowsePositionCodec.decode("\nid\tname"))
        // A line without a name is skipped, the rest is kept.
        assertEquals(BrowsePosition("lf-1", listOf("a" to "A")), BrowsePositionCodec.decode("lf-1\nbroken\na\tA"))
    }
}
