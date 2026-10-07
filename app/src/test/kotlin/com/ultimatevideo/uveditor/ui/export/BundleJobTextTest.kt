package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleItemKind
import com.ultimatevideo.uveditor.data.interchange.BundleVerification
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class BundleJobTextTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    private val packing = BundleProgress(
        kind = BundleItemKind.MEDIA, itemName = "IMG_0014.mov", mediaIndex = 3, mediaCount = 12,
        doneBytes = (1.8 * gib).toLong(), totalBytes = (7.4 * gib).toLong(), bytesPerSecond = 42.0 * mib, remainingMs = 120_000,
    )

    @Test
    fun `byte sizes use one decimal in the right unit`() {
        assertEquals("7.4 GB", BundleJobText.bytes((7.4 * gib).toLong()))
        assertEquals("512.0 MB", BundleJobText.bytes(512 * mib))
        assertEquals("300 B", BundleJobText.bytes(300))
        assertEquals("1.0 KB", BundleJobText.bytes(1024))
    }

    @Test
    fun `done of total is in the unit of the total`() {
        assertEquals("1.8 of 7.4 GB", BundleJobText.bytesOf((1.8 * gib).toLong(), (7.4 * gib).toLong()))
        assertEquals("0.5 of 7.4 GB", BundleJobText.bytesOf(512 * mib, (7.4 * gib).toLong()))
        assertEquals("200.0 of 300.0 MB", BundleJobText.bytesOf(200 * mib, 300 * mib))
    }

    @Test
    fun `time left is rounded and worded`() {
        assertEquals("a few seconds left", BundleJobText.left(4_000))
        assertEquals("about 40 s left", BundleJobText.left(39_000))
        assertEquals("about 2 min left", BundleJobText.left(120_000))
        assertEquals("about 5 min left", BundleJobText.left(290_000))
        assertEquals("about 1 h 5 min left", BundleJobText.left((65 * 60_000).toLong()))
        assertEquals("about 2 h left", BundleJobText.left(2 * 3_600_000L))
    }

    @Test
    fun `the step line names the media file and its place`() {
        assertEquals("Packing media 3 of 12: IMG_0014.mov", BundleJobText.step(packing))
        assertEquals("Media 3 of 12", BundleJobText.shortStep(packing))
        assertEquals("Writing the project data", BundleJobText.step(BundleProgress()))
        assertEquals("Adding warm.cube", BundleJobText.step(BundleProgress(kind = BundleItemKind.RESOURCE, itemName = "warm.cube")))
    }

    @Test
    fun `the progress line is bytes then time left`() {
        assertEquals("1.8 of 7.4 GB, about 2 min left", BundleJobText.progressLine(packing))
        assertEquals("1.8 of 7.4 GB", BundleJobText.progressLine(packing.copy(remainingMs = null)))
    }

    @Test
    fun `the saved line has the size the count and how long it took`() {
        val result = BundleWriteResult(mediaCopied = 14, mediaSkipped = emptyList(), lutsIncluded = 2, fontsIncluded = 1)

        assertEquals(
            "Backup saved: Holiday.uvbundle (7.4 GB, 14 media files, 2 LUTs, 1 font, took 4:12)",
            BundleJobText.savedLine("Holiday.uvbundle", result, (7.4 * gib).toLong(), 252_000),
        )
        assertEquals(
            "Backup saved: a.uvbundle (12.0 KB, 1 media file, took 3 s)",
            BundleJobText.savedLine("a.uvbundle", BundleWriteResult(1, emptyList()), 12 * 1024, 3_000),
        )
    }

    @Test
    fun `what could not go in is listed`() {
        val result = BundleWriteResult(1, listOf("a.mov", "b.mov", "c.mov", "d.mov"), resourcesSkipped = listOf("Arial"))

        assertEquals("Not copied (cannot be read): a.mov, b.mov, c.mov and 1 more. Not included: Arial", BundleJobText.skippedLine(result))
        assertEquals("", BundleJobText.skippedLine(BundleWriteResult(1, emptyList())))
    }

    @Test
    fun `failures name their cause`() {
        assertTrue(BundleJobText.failure(IOException("write failed: ENOSPC (No space left on device)")).contains("storage is full"))
        assertTrue(BundleJobText.failure(com.ultimatevideo.uveditor.data.ProjectError.Io("export bundle to content://x", IOException("No space left on device"))).contains("storage is full"))
        assertTrue(BundleJobText.failure(SecurityException("denied")).contains("permission"))
        assertTrue(BundleJobText.failure(FileNotFoundException("gone")).contains("could not be opened"))
        assertEquals(
            "Could not read clip.mov while writing the bundle (EIO)",
            BundleJobText.failure(IOException("could not read clip.mov while writing the bundle", IOException("EIO"))),
        )
        assertEquals("IllegalStateException", BundleJobText.failure(IllegalStateException()))
    }

    private val running = BundleJobState.Running("p1", "Holiday", packing, 0)

    @Test
    fun `the running view feeds the bar the dialog and the notification the same words`() {
        val view = bundleViewFor(running)!!

        assertEquals("Backing up Holiday", view.title)
        assertEquals("Packing media 3 of 12: IMG_0014.mov", view.step)
        assertEquals("1.8 of 7.4 GB, about 2 min left", view.progressLine)
        assertEquals("42.0 MB/s", view.rateLine)
        assertEquals(24, view.percent)
        assertTrue(view.running)
        val note = bundleNotificationFor(running)!!
        assertEquals("Backing up Holiday", note.title)
        assertEquals("Media 3 of 12 · 1.8 of 7.4 GB, about 2 min left", note.text)
        assertEquals(24, note.progressPercent)
        assertTrue(note.ongoing && note.showCancel && note.bundle)
        assertNull(note.shareUri)
    }

    @Test
    fun `no bar position before the first byte`() {
        val note = bundleNotificationFor(running.copy(progress = BundleProgress()))!!

        assertTrue(note.indeterminate)
    }

    @Test
    fun `verifying shows an indeterminate check with Cancel`() {
        val note = bundleNotificationFor(running.copy(verifying = true))!!

        assertEquals("Checking the backup of Holiday", note.title)
        assertEquals("Checking the saved file", note.text)
        assertTrue(note.indeterminate && note.showCancel && note.ongoing)
    }

    private val result = BundleWriteResult(14, emptyList(), bytesWritten = (7.4 * gib).toLong())
    private val verified = BundleVerification.Verified(30, (7.4 * gib).toLong())

    @Test
    fun `a saved backup offers Share in the notification and the view`() {
        val done = BundleJobState.Done("p1", "Holiday", "content://out/h.uvbundle", "Holiday.uvbundle", result, verified, 252_000)

        val view = bundleViewFor(done)!!
        assertEquals("Backup saved", view.title)
        assertEquals("Backup saved: Holiday.uvbundle (7.4 GB, 14 media files, took 4:12)", view.message)
        assertTrue(view.detail, view.detail.startsWith("Checked: the file is complete"))
        assertTrue(view.canShare)
        val note = bundleNotificationFor(done)!!
        assertEquals("Backup saved", note.title)
        assertEquals("Backup saved: Holiday.uvbundle (7.4 GB, 14 media files, took 4:12)", note.text)
        assertEquals("content://out/h.uvbundle", note.shareUri)
        assertFalse(note.ongoing || note.showCancel)
    }

    @Test
    fun `a warning leads with the problem and is not shareable`() {
        val done = BundleJobState.Done("p1", "Holiday", "content://out/h.uvbundle", "Holiday.uvbundle", result, BundleVerification.Warning(listOf("Its table of contents cannot be read")), 1_000)

        val view = bundleViewFor(done)!!
        assertEquals("Backup saved, but check the file", view.title)
        assertTrue(view.detail, view.detail.contains("table of contents"))
        assertFalse(view.canShare)
        assertTrue(bundleNotificationFor(done)!!.text.contains("table of contents"))
    }

    @Test
    fun `failed and cancelled with a leftover are shown, a clean cancel is not`() {
        val failed = BundleJobState.Failed("p1", "Holiday", "The storage is full.", " A partly written file could not be removed: x.")
        assertEquals("Backup failed", bundleViewFor(failed)?.title)
        assertEquals("Backup failed", bundleNotificationFor(failed)?.title)
        assertTrue(bundleViewFor(failed)!!.message.contains("could not be removed"))
        assertNull(bundleViewFor(BundleJobState.Cancelled("p1", "Holiday")))
        assertNull(bundleNotificationFor(BundleJobState.Cancelled("p1", "Holiday")))
        assertNull(bundleViewFor(BundleJobState.Idle))
        assertEquals("Backup cancelled", bundleViewFor(BundleJobState.Cancelled("p1", "Holiday", " Left over."))?.title)
    }

    @Test
    fun `the suggested file name is safe`() {
        assertEquals("My_trip_2.uvbundle", suggestedBundleName("My trip 2"))
        assertEquals("project.uvbundle", suggestedBundleName("///"))
    }
}
