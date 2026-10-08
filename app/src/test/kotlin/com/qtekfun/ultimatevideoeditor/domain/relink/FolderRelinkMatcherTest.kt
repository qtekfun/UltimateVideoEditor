package com.qtekfun.ultimatevideoeditor.domain.relink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRelinkMatcherTest {

    private fun file(path: String, size: Long? = null, kind: RelinkKind? = RelinkKind.VIDEO) =
        FolderFile("content://t/$path", path.split('/'), kind, size)

    private fun want(id: String, name: String, kind: RelinkKind = RelinkKind.VIDEO, oldPath: String? = null) =
        WantedMedia(id, name, kind, oldPath?.split('/') ?: listOf(name))

    private fun uris(r: FolderMatchResult) = r.matched.associate { it.assetId to it.file.uri }

    @Test
    fun `a single file with the name is the match`() {
        val r = FolderRelinkMatcher.match(listOf(want("a", "clip.mp4")), listOf(file("trip/clip.mp4", 10), file("trip/other.mp4", 5)))

        assertEquals(mapOf("a" to "content://t/trip/clip.mp4"), uris(r))
        assertEquals(MatchBasis.NAME, r.matched.single().basis)
        assertTrue(r.ambiguous.isEmpty() && r.notFound.isEmpty())
    }

    @Test
    fun `names are compared ignoring case and surrounding spaces`() {
        val r = FolderRelinkMatcher.match(listOf(want("a", "Clip.MP4 ")), listOf(file("x/clip.mp4")))

        assertEquals(listOf("a"), r.matched.map { it.assetId })
    }

    @Test
    fun `an item with no file of that name is not found`() {
        val r = FolderRelinkMatcher.match(listOf(want("a", "gone.mp4")), listOf(file("clip.mp4")))

        assertEquals(listOf("a"), r.notFound)
        assertTrue(r.matched.isEmpty())
    }

    @Test
    fun `nothing in the folder finds nothing`() {
        val r = FolderRelinkMatcher.match(listOf(want("a", "a.mp4"), want("b", "b.mp4")), emptyList())

        assertEquals(listOf("a", "b"), r.notFound)
    }

    @Test
    fun `a file of another kind with the same name is not accepted`() {
        val files = listOf(file("clip.mp4", kind = RelinkKind.IMAGE), file("note.txt", kind = null))

        val r = FolderRelinkMatcher.match(listOf(want("a", "clip.mp4"), want("b", "note.txt")), files)

        assertEquals(listOf("a", "b"), r.notFound)
    }

    @Test
    fun `a video file can stand in for missing audio but not the other way round`() {
        val asAudio = FolderRelinkMatcher.match(listOf(want("a", "talk.mp4", RelinkKind.AUDIO)), listOf(file("talk.mp4")))
        val asVideo = FolderRelinkMatcher.match(
            listOf(want("a", "talk.m4a", RelinkKind.VIDEO)),
            listOf(file("talk.m4a", kind = RelinkKind.AUDIO)),
        )

        assertEquals(listOf("a"), asAudio.matched.map { it.assetId })
        assertEquals(listOf("a"), asVideo.notFound)
    }

    @Test
    fun `copies of equal size are the same file and the shallowest one is taken`() {
        val r = FolderRelinkMatcher.match(
            listOf(want("a", "clip.mp4")),
            listOf(file("backup/old/clip.mp4", 100), file("clip.mp4", 100)),
        )

        assertEquals("content://t/clip.mp4", uris(r).getValue("a"))
    }

    @Test
    fun `same name with different sizes is ambiguous and never settled by name`() {
        val r = FolderRelinkMatcher.match(
            listOf(want("a", "clip.mp4")),
            listOf(file("one/clip.mp4", 100), file("two/clip.mp4", 200)),
        )

        assertTrue(r.matched.isEmpty())
        assertEquals(listOf("one/clip.mp4", "two/clip.mp4"), r.ambiguous.single().candidates.map { it.path.joinToString("/") })
    }

    @Test
    fun `same name with unknown sizes is ambiguous too`() {
        val r = FolderRelinkMatcher.match(listOf(want("a", "clip.mp4")), listOf(file("one/clip.mp4"), file("two/clip.mp4")))

        assertEquals("a", r.ambiguous.single().assetId)
    }

    @Test
    fun `a moved folder shows where the others are and settles a duplicated name`() {
        // intro.mp4 is unique and shows Movies/trip moved to Backup/trip; take2.mp4 exists in two places.
        val wanted = listOf(
            want("take", "take2.mp4", oldPath = "Movies/trip/take2.mp4"),
            want("intro", "intro.mp4", oldPath = "Movies/trip/intro.mp4"),
        )
        val files = listOf(
            file("Backup/trip/intro.mp4", 5),
            file("Backup/trip/take2.mp4", 10),
            file("Elsewhere/take2.mp4", 20),
        )

        val r = FolderRelinkMatcher.match(wanted, files)

        assertEquals("content://t/Backup/trip/take2.mp4", uris(r).getValue("take"))
        assertEquals(MatchBasis.MOVED_PATH, r.matched.first { it.assetId == "take" }.basis)
        assertTrue(r.ambiguous.isEmpty())
    }

    @Test
    fun `a flat folder learned from one file applies to files whose old folder is unknown`() {
        val wanted = listOf(want("a", "a.mp4"), want("b", "b.mp4"))
        val files = listOf(file("shoot/a.mp4", 1), file("shoot/b.mp4", 2), file("old/b.mp4", 3))

        val r = FolderRelinkMatcher.match(wanted, files)

        assertEquals("content://t/shoot/b.mp4", uris(r).getValue("b"))
    }

    @Test
    fun `a moved path that points at no candidate leaves the item ambiguous`() {
        val wanted = listOf(want("a", "a.mp4", oldPath = "Movies/a.mp4"), want("b", "b.mp4", oldPath = "Movies/b.mp4"))
        val files = listOf(file("New/a.mp4", 1), file("X/b.mp4", 2), file("Y/b.mp4", 3))

        val r = FolderRelinkMatcher.match(wanted, files)

        assertEquals(listOf("a"), r.matched.map { it.assetId })
        assertEquals(listOf("b"), r.ambiguous.map { it.assetId })
    }

    @Test
    fun `narrowing by facts keeps the only candidate with the right duration`() {
        val short = file("one/clip.mp4", 100)
        val long = file("two/clip.mp4", 200)
        val expected = FolderRelinkMatcher.Facts(60_000_000, 1920, 1080)

        val chosen = FolderRelinkMatcher.narrowByFacts(
            expected,
            listOf(
                short to FolderRelinkMatcher.Facts(10_000_000, 1920, 1080),
                long to FolderRelinkMatcher.Facts(60_100_000, 1920, 1080),
            ),
        )

        assertEquals(long, chosen)
    }

    @Test
    fun `narrowing by facts uses the picture size`() {
        val hd = file("hd/clip.mp4", 1)
        val sd = file("sd/clip.mp4", 2)

        val chosen = FolderRelinkMatcher.narrowByFacts(
            FolderRelinkMatcher.Facts(5_000_000, 1920, 1080),
            listOf(hd to FolderRelinkMatcher.Facts(5_000_000, 1920, 1080), sd to FolderRelinkMatcher.Facts(5_000_000, 640, 360)),
        )

        assertEquals(hd, chosen)
    }

    @Test
    fun `narrowing by facts gives up when several or none fit`() {
        val a = file("a/clip.mp4", 1)
        val b = file("b/clip.mp4", 2)
        val same = FolderRelinkMatcher.Facts(5_000_000, 1920, 1080)

        assertNull(FolderRelinkMatcher.narrowByFacts(same, listOf(a to same, b to same)))
        assertNull(FolderRelinkMatcher.narrowByFacts(same, listOf(a to FolderRelinkMatcher.Facts(1_000_000, 1920, 1080))))
    }

    @Test
    fun `facts a file does not state do not rule it out`() {
        val a = file("a/clip.mp4", 1)

        assertEquals(
            a,
            FolderRelinkMatcher.narrowByFacts(FolderRelinkMatcher.Facts(5_000_000, 1920, 1080), listOf(a to FolderRelinkMatcher.Facts(null, null, null))),
        )
    }

    @Test
    fun `kinds come from the MIME type and fall back to the extension`() {
        assertEquals(RelinkKind.VIDEO, RelinkKind.fromFile("video/mp4", "x.bin"))
        assertEquals(RelinkKind.AUDIO, RelinkKind.fromFile("application/octet-stream", "Song.MP3"))
        assertEquals(RelinkKind.IMAGE, RelinkKind.fromFile(null, "photo.jpeg"))
        assertNull(RelinkKind.fromFile("text/plain", "notes.txt"))
    }
}
