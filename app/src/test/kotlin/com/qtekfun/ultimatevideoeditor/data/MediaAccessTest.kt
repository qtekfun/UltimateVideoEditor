package com.qtekfun.ultimatevideoeditor.data

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The I/O boundary of a content URI whose volume is gone (a USB drive that is not plugged in). The owner's crash was an
 * IllegalArgumentException from ExternalStorageProvider that no call site caught.
 */
class MediaAccessTest {

    /** The exceptions a provider, its process or its volume can throw, with the problem each must become. */
    private val gone: List<Pair<Exception, MediaProblem>> = listOf(
        IllegalArgumentException("Failed to determine if 4450-56F6:Movies/VID.mp4 is child of 4450-56F6:: java.io.FileNotFoundException: Missing root for 4450-56F6") to MediaProblem.UNREADABLE,
        IllegalArgumentException("Unknown URI content://x/y") to MediaProblem.UNREADABLE,
        FileNotFoundException("No content provider: content://x/y") to MediaProblem.UNREADABLE,
        IOException("EIO (I/O error)") to MediaProblem.UNREADABLE,
        IOException("ENODEV (No such device)") to MediaProblem.UNREADABLE,
        IllegalStateException("provider is gone") to MediaProblem.UNREADABLE,
        UnsupportedOperationException("query on a missing document") to MediaProblem.UNREADABLE,
        SecurityException("Permission Denial: opening provider") to MediaProblem.PERMISSION_LOST,
    )

    @Test
    fun `every provider-gone exception becomes a typed MediaImportException`() {
        for ((thrown, problem) in gone) {
            try {
                guardMedia("VID.mp4") { throw thrown }
                fail("not thrown: $thrown")
            } catch (e: MediaImportException) {
                assertEquals(thrown.toString(), problem, e.problem)
                assertSame(thrown, e.cause)
                assertTrue(e.message!!.contains("VID.mp4"))
            }
        }
    }

    @Test
    fun `a bug is not hidden as a missing file`() {
        val bug = NullPointerException("a bug")
        try {
            guardMedia("VID.mp4") { throw bug }
            fail("swallowed")
        } catch (e: NullPointerException) {
            assertSame(bug, e)
        }
        try {
            mediaOrNull<Int> { throw ArithmeticException("division by zero") }
            fail("swallowed")
        } catch (e: ArithmeticException) {
            // propagates
        }
    }

    @Test
    fun `an import exception passes through the guard unchanged`() {
        val inner = MediaImportException("unsupported", problem = MediaProblem.UNSUPPORTED)
        try {
            guardMedia("x") { throw inner }
            fail("not thrown")
        } catch (e: MediaImportException) {
            assertSame(inner, e)
        }
    }

    @Test
    fun `a successful read returns its value`() {
        assertEquals(7, guardMedia("x") { 7 })
        assertEquals(7, mediaOrNull { 7 })
        assertEquals("ok", asIoFailure("u") { "ok" })
    }

    @Test
    fun `mediaOrNull answers null for every provider-gone exception`() {
        for ((thrown, _) in gone) assertNull(thrown.toString(), mediaOrNull<Int> { throw thrown })
    }

    @Test
    fun `asIoFailure turns provider-gone exceptions into IOException for callers that handle it`() {
        for ((thrown, _) in gone) {
            try {
                asIoFailure("content://x/y") { throw thrown }
                fail("not thrown: $thrown")
            } catch (e: IOException) {
                if (thrown is IOException) assertSame(thrown, e) else assertSame(thrown, e.cause)
            }
        }
    }

    @Test
    fun `the message tells the owner to reconnect the drive`() {
        assertTrue(MediaFailure.describe(MediaProblem.UNREADABLE, "VID.mp4").contains("USB"))
    }
}
