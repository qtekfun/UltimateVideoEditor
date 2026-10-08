package com.qtekfun.ultimatevideoeditor.crash

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportTest {
    @get:Rule val temp = TemporaryFolder()

    private val context = CrashContext(appVersion = "0.1.0 (100)", deviceModel = "Acme Phone", androidRelease = "16", sdkInt = 36)
    private lateinit var previousHandler: Thread.UncaughtExceptionHandler

    @Before fun saveHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ -> }
    }

    @After fun restoreHandler() = Thread.setDefaultUncaughtExceptionHandler(previousHandler)

    @Test
    fun `report carries version, device, thread and the stack`() {
        val report = CrashReportFormat.format(IllegalStateException("boom"), "main", context, 0L)
        assertTrue(report.contains("version: 0.1.0 (100)"))
        assertTrue(report.contains("device: Acme Phone"))
        assertTrue(report.contains("android: 16 (API 36)"))
        assertTrue(report.contains("thread: main"))
        assertTrue(report.contains("java.lang.IllegalStateException: boom"))
        assertTrue(report.contains("\tat com.qtekfun.ultimatevideoeditor.crash.CrashReportTest"))
        assertTrue(report.contains("time: 1970-01-01T00:00:00Z"))
    }

    @Test
    fun `report holds no content uris, paths or media file names`() {
        val error = IllegalArgumentException(
            "cannot open content://com.android.providers.media.documents/document/video%3A42 at /storage/emulated/0/DCIM/Holiday Trip.mp4 " +
                "or /data/user/0/com.qtekfun.ultimatevideoeditor/files/projects/secret-name/project.json",
        )
        val report = CrashReportFormat.format(error, "worker", context, 0L)
        assertFalse(report.contains("providers.media"))
        assertFalse(report.contains("DCIM"))
        assertFalse(report.contains("Holiday Trip"))
        assertFalse(report.contains("secret-name"))
        assertTrue(report.contains("content://<removed>"))
        assertTrue(report.contains("<path>"))
    }

    @Test
    fun `long messages are cut and the cause chain is bounded`() {
        var error: Throwable = RuntimeException("x".repeat(5000))
        repeat(10) { error = RuntimeException("level $it", error) }
        val report = CrashReportFormat.format(error, "main", context, 0L)
        assertTrue(report.lines().none { it.length > 400 })
        assertEquals(5, Regex("(?m)^(Caused by: )?java\\.lang\\.RuntimeException").findAll(report).count())
    }

    @Test
    fun `a missing message prints the class only`() {
        assertEquals("java.lang.NullPointerException", CrashReportFormat.describe(NullPointerException()))
    }

    @Test
    fun `store reads the latest report, deletes everything and survives overwrite`() {
        val store = CrashReportStore(File(temp.root, "crash"))
        assertNull(store.read())
        store.write("first")
        store.write("second")
        assertEquals("second", store.read())
        assertEquals(listOf("second", "first"), store.readAll())
        store.delete()
        assertNull(store.read())
        assertEquals(emptyList<String>(), store.readAll())
        store.delete() // deleting nothing is fine
    }

    @Test
    fun `store rotates and keeps at most five reports, newest first`() {
        val dir = File(temp.root, "crash")
        val store = CrashReportStore(dir)
        for (n in 1..8) store.write("crash $n")

        assertEquals(listOf("crash 8", "crash 7", "crash 6", "crash 5", "crash 4"), store.readAll())
        assertEquals(CrashReportStore.MAX_FILES, dir.listFiles()!!.count { it.name.startsWith("last-crash") })
        store.delete()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `replaceLatest extends the latest report without rotating`() {
        val store = CrashReportStore(File(temp.root, "crash"))
        store.write("older")
        store.write("latest")
        store.replaceLatest("latest plus native summary")

        assertEquals(listOf("latest plus native summary", "older"), store.readAll())
    }

    @Test
    fun `the report of the unmounted-drive crash names the exception and drops the file name`() {
        val error = IllegalArgumentException("Failed to determine if 4450-56F6:Movies/VID20260930191900.mp4 is child of 4450-56F6:Movies")
        val text = CrashReportFormat.format(error, "main", context, 0L)

        assertTrue(text.contains("java.lang.IllegalArgumentException: Failed to determine if"))
        assertFalse(text.contains("VID20260930191900"))
    }

    @Test
    fun `store caps the report size`() {
        val store = CrashReportStore(File(temp.root, "crash"))
        store.write("y".repeat(200_000))
        assertEquals(64 * 1024, store.read()!!.length)
    }

    @Test
    fun `handler writes the report and then calls the previous handler`() {
        val calls = mutableListOf<String>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> calls += e.message.orEmpty() }
        val store = CrashReportStore(File(temp.root, "crash"))
        CrashHandler.install(store, context) { 1_000L }

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("died"))

        assertTrue(store.read()!!.contains("java.lang.IllegalStateException: died"))
        assertEquals(listOf("died"), calls)
    }

    @Test
    fun `a failing store never hides the original crash`() {
        val calls = mutableListOf<String>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> calls += e.message.orEmpty() }
        val blocker = File(temp.root, "not-a-dir").apply { writeText("file") }
        CrashHandler.install(CrashReportStore(File(blocker, "crash")), context)

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("still reported"))

        assertEquals(listOf("still reported"), calls)
    }
}
