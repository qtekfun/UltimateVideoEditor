package com.qtekfun.ultimatevideoeditor.crash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessExitTest {
    @get:Rule val temp = TemporaryFolder()

    private val context = CrashContext(appVersion = "0.1.0 (100)", deviceModel = "Acme Phone", androidRelease = "17", sdkInt = 37)
    private val store get() = CrashReportStore(File(temp.root, "crash"))
    private val handled get() = File(temp.root, "crash/last-exit-handled.txt")

    private fun exit(reason: Int, at: Long, description: String? = null, trace: ByteArray? = null) =
        ProcessExit(reason = reason, timestampMillis = at, importance = 100, pid = 1234, description = description, trace = trace)

    private fun recorder(vararg exits: ProcessExit) = ProcessExitRecorder({ exits.toList() }, store, handled, context)

    private fun tombstone(vararg strings: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x0a, 0x01, 0x00, 0x7f))
        for (s in strings) {
            out.write(s.toByteArray())
            out.write(byteArrayOf(0x00, 0x12, 0x03))
        }
        return out.toByteArray()
    }

    @Test
    fun `a native crash is written to the local report with its symbols`() {
        val trace = tombstone(
            "uv::audio::PreparedSnapshot::adoptStateFrom(uv::audio::PreparedSnapshot const&) const",
            "uv::audio::AudioCore::renderBlock(float*, int)",
            "/data/app/~~abc==/com.qtekfun.ultimatevideoeditor-xyz==/base.apk!libuveditor_engine.so",
            "Cause: null pointer dereference",
            "unrelated chatter without any marker",
        )
        val written = recorder(exit(ExitReason.CRASH_NATIVE, 1_000, "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)", trace)).recordNewExits()
        assertTrue(written)
        val report = store.read()!!
        assertTrue(report.startsWith("ultimateVE native exit report"))
        assertTrue(report.contains("exit: native crash"))
        assertTrue(report.contains("system note: signal 11 (SIGSEGV)"))
        assertTrue(report.contains("PreparedSnapshot::adoptStateFrom"))
        assertTrue(report.contains("AudioCore::renderBlock"))
        assertTrue(report.contains("null pointer dereference"))
        assertFalse("paths must be scrubbed", report.contains("/data/app/"))
        assertFalse(report.contains("unrelated chatter"))
        assertTrue(report.contains("No project, media or personal data"))
    }

    @Test
    fun `an exit is reported once`() {
        val first = recorder(exit(ExitReason.CRASH_NATIVE, 1_000)).recordNewExits()
        store.delete()
        val again = recorder(exit(ExitReason.CRASH_NATIVE, 1_000)).recordNewExits()
        assertTrue(first)
        assertFalse(again)
        assertNull(store.read())
        // A newer one is reported.
        assertTrue(recorder(exit(ExitReason.ANR, 2_000), exit(ExitReason.CRASH_NATIVE, 1_000)).recordNewExits())
        assertTrue(store.read()!!.contains("exit: not responding (ANR)"))
    }

    @Test
    fun `java crashes, low memory and normal exits are left to their own paths`() {
        val written = recorder(
            exit(ExitReason.CRASH, 3_000),
            exit(ExitReason.LOW_MEMORY, 2_000),
            exit(1, 1_000), // EXIT_SELF
            exit(10, 500), // USER_REQUESTED
        ).recordNewExits()
        assertFalse(written)
        assertNull(store.read())
        // They still move the cursor, so a later run does not reread them.
        assertEquals("3000", handled.readText().trim())
    }

    @Test
    fun `the native summary is added above an earlier java report`() {
        store.write("ultimateVE crash report\nolder java crash")
        assertTrue(recorder(exit(ExitReason.SIGNALED, 5_000, "kill by signal 9")).recordNewExits())
        val report = store.read()!!
        assertTrue(report.startsWith("ultimateVE native exit report"))
        assertTrue(report.contains("Earlier report:"))
        assertTrue(report.contains("older java crash"))
        assertTrue(report.contains("exit: signalled"))
    }

    @Test
    fun `a failing source or an unwritable cursor never throws`() {
        val failing = ProcessExitRecorder({ error("system service gone") }, store, handled, context)
        assertFalse(failing.recordNewExits())
        val noExits = ProcessExitRecorder({ emptyList() }, store, handled, context)
        assertFalse(noExits.recordNewExits())
        assertNull(store.read())
    }

    @Test
    fun `tombstone extraction keeps symbols, bounds the output and tolerates garbage`() {
        assertEquals(emptyList<String>(), TombstoneSummary.extract(null))
        assertEquals(emptyList<String>(), TombstoneSummary.extract(ByteArray(0)))
        assertEquals(emptyList<String>(), TombstoneSummary.extract(ByteArray(5000)))
        val many = tombstone(*Array(500) { "uv::foo::bar$it(int)" })
        val frames = TombstoneSummary.extract(many)
        assertTrue(frames.size <= 41)
        assertTrue(frames.first().contains("uv::foo::bar0"))
        val withUri = TombstoneSummary.extract(tombstone("uv::x::y content://media/external/video/media/1 at /sdcard/DCIM/Trip.mp4"))
        assertEquals(1, withUri.size)
        assertFalse(withUri.single().contains("sdcard"))
        assertFalse(withUri.single().contains("Trip"))
        assertNotNull(withUri.single())
    }

    private fun protoField(tag: Int, text: String): ByteArray {
        val body = text.toByteArray()
        require(body.size < 128)
        return byteArrayOf(tag.toByte(), body.size.toByte()) + body
    }

    @Test
    fun `protobuf text fields are read without their tag and length bytes`() {
        // Seen on a real tombstone: the 84-byte symbol is preceded by the tag 0x22 and the length 0x54 ('"T').
        val long = "std::__ndk1::condition_variable::wait(std::__ndk1::unique_lock<std::__ndk1::mutex>&)"
        assertEquals(84, long.length)
        val short = "uv::render::RenderThread::run()"
        val inner = protoField(0x22, short)
        val stream = protoField(0x22, long) + byteArrayOf(0x1a, inner.size.toByte()) + inner
        assertEquals(listOf(long, short), TombstoneSummary.extract(stream))
    }

    @Test
    fun `reason names are readable`() {
        assertEquals("native crash", ExitReason.name(ExitReason.CRASH_NATIVE))
        assertEquals("reason 99", ExitReason.name(99))
        assertTrue(ExitReason.isUnhandledByJava(ExitReason.ANR))
        assertFalse(ExitReason.isUnhandledByJava(ExitReason.CRASH))
    }
}
