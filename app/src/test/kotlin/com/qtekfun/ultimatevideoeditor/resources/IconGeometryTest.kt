package com.qtekfun.ultimatevideoeditor.resources

import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the launcher vector drawables and checks they stay inside the adaptive-icon safe zone. */
class IconGeometryTest {
    private val res = File("src/main/res")

    private val centre = 54.0
    private val safeRadius = 33.0 // 66dp diameter circle that no launcher mask may crop

    private fun pathData(file: String): List<String> =
        Regex("""android:pathData="([^"]+)"""").findAll(File(res, file).readText()).map { it.groupValues[1] }.toList()

    /** Points of absolute M/L/Q path commands (control points included, so the bound is conservative). */
    private fun points(data: String): List<Pair<Double, Double>> {
        val numbers = Regex("""-?\d+(\.\d+)?""").findAll(data).map { it.value.toDouble() }.toList()
        assertTrue("odd coordinate count in $data", numbers.size % 2 == 0)
        return numbers.chunked(2).map { it[0] to it[1] }
    }

    @Test
    fun `foreground and monochrome stay inside the safe zone`() {
        for (file in listOf("drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml")) {
            val all = pathData(file).flatMap(::points)
            assertTrue(file, all.isNotEmpty())
            val farthest = all.maxOf { (x, y) -> hypot(x - centre, y - centre) }
            assertTrue("$file reaches $farthest from the centre", farthest <= safeRadius)
        }
    }

    @Test
    fun `monochrome has the same shapes as the foreground`() {
        assertEquals(
            pathData("drawable/ic_launcher_foreground.xml"),
            pathData("drawable/ic_launcher_monochrome.xml"),
        )
    }

    @Test
    fun `adaptive icon declares all three layers and the manifest uses it`() {
        for (file in listOf("mipmap-anydpi/ic_launcher.xml", "mipmap-anydpi/ic_launcher_round.xml")) {
            val xml = File(res, file).readText()
            assertTrue(xml.contains("<background"))
            assertTrue(xml.contains("<foreground"))
            assertTrue(xml.contains("<monochrome"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("@mipmap/ic_launcher_round"))
    }
}
