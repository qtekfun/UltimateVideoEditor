package com.ultimatevideo.uveditor.ui.about

import com.ultimatevideo.uveditor.crash.CrashReportStore
import com.ultimatevideo.uveditor.ui.onboarding.FakeOnboardingStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AboutModelTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @Before fun layout() {
        filesDir = temp.newFolder("files")
        cacheDir = temp.newFolder("cache")
    }

    private fun write(path: File, bytes: Int): File = path.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun seedProject(id: String) {
        val project = File(filesDir, "projects/$id")
        write(File(project, "project.json"), 1_000)
        write(File(project, "project.json.bak"), 500)
        write(File(project, "waveforms/a.peaks"), 200)
        write(File(project, "thumbnails/a/L0.tiles"), 300)
        write(File(project, "stab/s.bin"), 40)
        write(File(project, "track/t.bin"), 60)
    }

    @Test
    fun `usage separates projects, caches and proxies`() {
        seedProject("p1")
        write(File(cacheDir, "tmp.bin"), 700)
        write(File(cacheDir, "proxies/p.mp4"), 5_000)

        val usage = StorageMeter(filesDir, cacheDir).usage()

        assertEquals(1_500L, usage.projectsBytes)
        assertEquals(200L + 300 + 40 + 60 + 700, usage.cacheBytes)
        assertEquals(5_000L, usage.proxyBytes)
    }

    @Test
    fun `clearing caches keeps projects and proxies and reports what it freed`() {
        seedProject("p1")
        seedProject("p2")
        write(File(cacheDir, "tmp.bin"), 700)
        write(File(cacheDir, "proxies/p.mp4"), 5_000)
        val meter = StorageMeter(filesDir, cacheDir)

        val freed = meter.clearCaches()

        assertEquals(2 * (200L + 300 + 40 + 60) + 700, freed)
        assertTrue(File(filesDir, "projects/p1/project.json").exists())
        assertTrue(File(filesDir, "projects/p2/project.json.bak").exists())
        assertFalse(File(filesDir, "projects/p1/waveforms").exists())
        assertFalse(File(filesDir, "projects/p2/thumbnails").exists())
        assertFalse(File(cacheDir, "tmp.bin").exists())
        assertTrue(File(cacheDir, "proxies/p.mp4").exists())
        assertEquals(0L, meter.usage().cacheBytes)
    }

    @Test
    fun `clearing with nothing to clear is fine`() {
        assertEquals(0L, StorageMeter(File(filesDir, "nowhere"), File(cacheDir, "none")).clearCaches())
    }

    @Test
    fun `controller reads, deletes and resets what the screen shows`() {
        val store = CrashReportStore(File(filesDir, "crash"))
        val onboarding = FakeOnboardingStore(seen = true)
        val controller = AboutController(AppVersion("0.1.0", 100), store, StorageMeter(filesDir, cacheDir), onboarding)

        assertNull(controller.snapshot().crashReport)
        store.write("report text")
        val snapshot = controller.snapshot()
        assertEquals("report text", snapshot.crashReport)
        assertEquals("0.1.0 (100)", snapshot.version.display)
        assertEquals("https://github.com/qtekfun/UltimateVideoEditor", snapshot.repositoryUrl)

        controller.deleteCrashReport()
        assertNull(controller.snapshot().crashReport)

        assertTrue(onboarding.seen())
        controller.showTipsAgain()
        assertFalse(onboarding.seen())
    }

    @Test
    fun `byte sizes read well`() {
        val us = java.util.Locale.US
        assertEquals("512 B", AboutController.formatBytes(512, us))
        assertEquals("1.5 KB", AboutController.formatBytes(1536, us))
        assertEquals("2.0 MB", AboutController.formatBytes(2L * 1024 * 1024, us))
        assertEquals("3.00 GB", AboutController.formatBytes(3L * 1024 * 1024 * 1024, us))
        assertEquals("1,5 KB", AboutController.formatBytes(1536, java.util.Locale.GERMANY))
    }
}
