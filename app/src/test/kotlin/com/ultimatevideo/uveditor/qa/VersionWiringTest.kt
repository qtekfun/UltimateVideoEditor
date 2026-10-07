package com.ultimatevideo.uveditor.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hub footer said "Engine v0.1.0" long after the app moved on, because the version was typed into the C++ source. The
 * single source is `gradle/version.properties`; Gradle hands it to CMake as UV_ENGINE_VERSION, which becomes `engineVersion()`.
 * These keep that chain intact and keep a literal version out of the sources. `scripts/qa-smoke.sh` compares the footer on a
 * device with versionName, and `scripts/run-native-tests.sh` builds `engine_version.cpp` with the real value.
 */
class VersionWiringTest {
    private val versionName = File(QaSources.repoDir, "gradle/version.properties").readLines()
        .first { it.startsWith("versionName=") }.substringAfter('=').trim()

    @Test
    fun `the version file holds a plain semantic version`() {
        assertTrue(versionName, Regex("""\d+\.\d+\.\d+""").matches(versionName))
    }

    @Test
    fun `gradle passes the app version to the native build and the app takes versionName from it`() {
        val gradle = File(QaSources.appDir, "build.gradle.kts").readText()
        assertTrue("-DUV_ENGINE_VERSION must be \$appVersionName", gradle.contains("\"-DUV_ENGINE_VERSION=\$appVersionName\""))
        assertTrue("versionName must be appVersionName", Regex("""versionName\s*=\s*appVersionName""").containsMatchIn(gradle))
        assertTrue("appVersionName must come from gradle/version.properties", gradle.contains("version.properties"))
    }

    @Test
    fun `cmake turns it into a compile definition of the engine`() {
        val cmake = File(QaSources.appDir, "src/main/cpp/CMakeLists.txt").readText()
        assertTrue(cmake.contains("""target_compile_definitions(uveditor_engine PRIVATE UV_ENGINE_VERSION="${'$'}{UV_ENGINE_VERSION}")"""))
    }

    @Test
    fun `engineVersion returns the definition and no other version is written into native code`() {
        val source = File(QaSources.appDir, "src/main/cpp/core/engine_version.cpp").readText()
        assertTrue(source.contains("return UV_ENGINE_VERSION;"))
        // The only literal allowed is the marker of a build without the definition (host tests).
        val literals = Regex(""""(\d+\.\d+\.\d+[^"]*)"""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0.0.0-dev"), literals)
        val stray = QaSources.mainSources("kt").filter { f ->
            // "Engine v" followed by digits would be a typed-in version.
            Regex("""Engine v\d""").containsMatchIn(f.readText())
        }.map { QaSources.relative(it) }
        assertEquals("a version typed into the UI", emptyList<String>(), stray)
    }

    @Test
    fun `the footer shows the engine's own version`() {
        val hub = QaSources.main("kotlin/com/ultimatevideo/uveditor/ui/hub/HubScreen.kt").readText()
        assertTrue(hub.contains("\"Engine v\$it\""))
    }
}
