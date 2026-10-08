package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoudnessCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun asset(id: String = "a1", frames: Long = 900) =
        MediaAssetDto(id, "content://m/$id", frames, 30, 1, "Rec709-SDR")

    @Test
    fun `the key names the file, its length and the range`() {
        val key = LoudnessCache.keyOf(asset(), 30, 330)
        assertEquals(key, LoudnessCache.keyOf(asset(), 30, 330))
        assert(key != LoudnessCache.keyOf(asset(), 30, 331))
        assert(key != LoudnessCache.keyOf(asset(frames = 901), 30, 330)) // a replaced file measures again
        assert(key != LoudnessCache.keyOf(asset("a2"), 30, 330))
    }

    @Test
    fun `the none cache remembers nothing`() {
        LoudnessCache.None.put("k", -20.0)
        assertNull(LoudnessCache.None.get("k"))
    }

    @Test
    fun `values survive a restart`() {
        val file = File(folder.root, "loudness/cache.json")
        FileLoudnessCache(file).apply {
            put("a", -23.5)
            put("b", -14.0)
        }

        val reloaded = FileLoudnessCache(file)
        assertEquals(-23.5, reloaded.get("a")!!, 0.0)
        assertEquals(-14.0, reloaded.get("b")!!, 0.0)
        assertNull(reloaded.get("c"))
    }

    @Test
    fun `the app cache lives in one file shared by projects and survives a restart`() {
        val first = loudnessCacheIn(folder.root)
        first.put(LoudnessCache.keyOf(asset(), 0, 300), -18.5)

        // A new instance, as after a restart or in another project, finds it in files/loudness/cache.json.
        val second = loudnessCacheIn(folder.root)
        assertEquals(-18.5, second.get(LoudnessCache.keyOf(asset(), 0, 300))!!, 0.0)
        assertEquals(true, File(folder.root, LOUDNESS_CACHE_PATH).isFile)
        assertNull(second.get(LoudnessCache.keyOf(asset(), 0, 301)))
    }

    @Test
    fun `a damaged file is treated as empty and repaired by the next write`() {
        val file = File(folder.root, "cache.json")
        file.writeText("{ this is not json")

        val cache = FileLoudnessCache(file)
        assertNull(cache.get("a"))
        cache.put("a", -20.0)
        assertEquals(-20.0, FileLoudnessCache(file).get("a")!!, 0.0)
    }
}
