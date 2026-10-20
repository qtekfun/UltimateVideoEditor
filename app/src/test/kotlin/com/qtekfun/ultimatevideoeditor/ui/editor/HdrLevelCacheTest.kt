package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HdrLevelCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun asset(uri: String = "content://m/a", frames: Long = 900) = MediaAssetDto("asset-1", uri, frames, 30, 1, "Rec2020-HLG")

    @Test
    fun `the key names the file and how it is read, not the project`() {
        val key = HdrLevelCache.keyOf(asset(), SourceColorSpace.HLG)
        assertEquals(key, HdrLevelCache.keyOf(asset().copy(id = "asset-other"), SourceColorSpace.HLG))
        assertNotEquals(key, HdrLevelCache.keyOf(asset(), SourceColorSpace.PQ))
        assertNotEquals(key, HdrLevelCache.keyOf(asset(frames = 901), SourceColorSpace.HLG)) // a replaced file is measured again
        assertNotEquals(key, HdrLevelCache.keyOf(asset("content://m/b"), SourceColorSpace.HLG))
    }

    @Test
    fun `the none cache remembers nothing`() {
        HdrLevelCache.None.put("k", 1.4)
        assertNull(HdrLevelCache.None.get("k"))
    }

    @Test
    fun `levels survive a restart and are shared by every project`() {
        val first = hdrLevelCacheIn(folder.root)
        first.put("k1", 1.62)
        first.put("k2", 0.4)
        val second = hdrLevelCacheIn(folder.root)
        assertEquals(1.62, second.get("k1")!!, 0.0)
        assertEquals(0.4, second.get("k2")!!, 0.0)
        assertNull(second.get("k3"))
        assertEquals(true, File(folder.root, HDR_LEVEL_CACHE_PATH).isFile)
    }

    @Test
    fun `a damaged file reads as empty and can be written again`() {
        val file = File(folder.root, HDR_LEVEL_CACHE_PATH)
        file.parentFile?.mkdirs()
        file.writeText("{not json")
        val cache = FileHdrLevelCache(file)
        assertNull(cache.get("k"))
        cache.put("k", 2.0)
        assertEquals(2.0, FileHdrLevelCache(file).get("k")!!, 0.0)
    }
}
