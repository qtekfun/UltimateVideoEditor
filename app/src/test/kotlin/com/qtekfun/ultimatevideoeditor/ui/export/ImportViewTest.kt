package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.BundleImportSummary
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.ImportSteps
import com.qtekfun.ultimatevideoeditor.data.interchange.sampleProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import com.qtekfun.ultimatevideoeditor.ui.text.english
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

class ImportViewTest {
    private val project = sampleProject().copy(id = "id-1", name = "Holiday")
    private val clean = ImportReport(project, BundleImportSummary(mediaCopied = 14, relinked = 0, missing = emptyList()))

    private fun done(report: ImportReport = clean, quick: Boolean = false, bytes: Long = 7_945_689_497, took: Long = 252_000) =
        ImportJobState.Done("content://in/h", "Holiday.uvbundle", report, bytes, took, quick)

    private fun running(progress: BundleProgress, revealed: Boolean = true) =
        ImportJobState.Running("content://in/h", "Holiday.uvbundle", progress, 0, revealed)

    // region the words while it runs

    @Test
    fun `the step names the file and its place in the list`() {
        val p = BundleProgress(BundleItemKind.MEDIA, "IMG_0014.mov", 3, 12, 1_800_000_000, 7_945_689_497, 5.0e7, 120_000)

        assertEquals("Importing project: file 3 of 12: IMG_0014.mov", ImportJobText.step(p).english())
        assertEquals("File 3 of 12", ImportJobText.shortStep(p).english())
        assertEquals("1.7 of 7.4 GB, about 2 min left", ImportJobText.progressLine(p).english())
    }

    @Test
    fun `the other steps say what is going on, from the first moment to the last`() {
        assertEquals("Importing project: opening the file", ImportJobText.step(BundleProgress()).english())
        assertEquals("Importing project: reading the project data", ImportJobText.step(BundleProgress(BundleItemKind.PROJECT, "project.json")).english())
        assertEquals("Importing project: finishing", ImportJobText.step(BundleProgress(BundleItemKind.PROJECT, ImportSteps.FINISHING)).english())
        assertEquals("Importing project: adding teal.cube", ImportJobText.step(BundleProgress(BundleItemKind.RESOURCE, "teal.cube")).english())
    }

    @Test
    fun `a stream has no total, so the bytes done are all there is`() {
        assertEquals("1.5 GB so far", ImportJobText.progressLine(BundleProgress(doneBytes = 1_610_612_736, totalBytes = 0)).english())
        assertEquals("", ImportJobText.progressLine(BundleProgress()).english())
        assertEquals("File 2", ImportJobText.shortStep(BundleProgress(BundleItemKind.MEDIA, "a", 2, 0)).english())
    }

    @Test
    fun `nothing is shown until the import has been revealed`() {
        assertNull(importViewFor(running(BundleProgress(), revealed = false)))
        assertNull(importNotificationFor(running(BundleProgress(), revealed = false)))
        assertNull(importViewFor(ImportJobState.Idle))
        assertNull(importViewFor(ImportJobState.Cancelled("u", "n")))
        assertNull(importViewFor(ImportJobState.NeedsMediaFolder("u")))
    }

    @Test
    fun `the bar, the dialog and the notification say the same thing`() {
        val p = BundleProgress(BundleItemKind.MEDIA, "IMG_0014.mov", 3, 12, 1_800_000_000, 7_945_689_497)
        val view = importViewFor(running(p))!!
        val note = importNotificationFor(running(p))!!

        assertTrue(view.running)
        assertEquals(22, view.percent)
        assertEquals("1.7 of 7.4 GB · 22%", view.barLine.english())
        assertTrue(note.ongoing && note.showCancel && note.import && !note.bundle)
        assertEquals(22, note.progressPercent)
        assertEquals("File 3 of 12 · 1.7 of 7.4 GB", note.text.english())
        assertEquals(view.title, note.title)
        assertFalse(view.indeterminate)
        assertTrue("before the first byte the bar is indeterminate", importViewFor(running(BundleProgress()))!!.indeterminate)
    }

    // endregion

    // region the result

    @Test
    fun `a clean import says what it did and offers Open`() {
        val view = importViewFor(done())!!

        assertEquals(ImportView.Phase.IMPORTED, view.phase)
        assertEquals("Imported: Holiday (7.4 GB, 14 media files, took 4:12)", view.message.english())
        assertEquals("Project imported", view.title.english())
        assertTrue(view.canOpen)
        assertEquals("", view.detail.english())
        val note = importNotificationFor(done())!!
        assertFalse(note.ongoing)
        assertEquals(view.message, note.text)
    }

    @Test
    fun `a project file has no media count and no size`() {
        val state = done(ImportReport(project), bytes = 0, took = 300)

        assertEquals("Imported: Holiday (took 0 s)", importViewFor(state)!!.message.english())
        assertEquals("1 media file", ImportJobText.doneLine(done(ImportReport(project, BundleImportSummary(1, 0, emptyList())), bytes = 0, took = 1_000)).english().substringAfter("(").substringBefore(","))
    }

    @Test
    fun `missing media, a new name and the like make it an import with notes`() {
        val report = ImportReport(
            project.copy(name = "Holiday (2)"),
            BundleImportSummary(mediaCopied = 12, relinked = 1, missing = listOf("a.mov", "b.mov", "c.mov", "d.mov")),
            renamedFrom = "Holiday",
        )

        val view = importViewFor(done(report))!!

        assertEquals(ImportView.Phase.NOTES, view.phase)
        assertEquals("Imported, with notes", view.title.english())
        assertTrue(view.detail.english(), view.detail.english().contains("The name \"Holiday\" was taken, so the project is called \"Holiday (2)\"."))
        assertTrue(view.detail.english(), view.detail.english().contains("Missing media (relink in the editor): a.mov, b.mov, c.mov and 1 more"))
        assertTrue(view.detail.english(), view.detail.english().contains("1 media file found on this device by name and size."))
        assertTrue("still openable", view.canOpen)
    }

    @Test
    fun `relinking alone is good news, not a note`() {
        val view = importViewFor(done(ImportReport(project, BundleImportSummary(0, 2, emptyList()))))!!

        assertEquals(ImportView.Phase.IMPORTED, view.phase)
        assertTrue(view.detail.english().contains("2 media files found on this device"))
    }

    @Test
    fun `an import that ended before anything was shown is left to the message of the project list`() {
        assertNull(importViewFor(done(quick = true)))
        assertEquals("Imported \"Holiday\". 14 media files came with it", ImportJobText.quickLine(clean).english())
    }

    // endregion

    // region why it failed

    private fun failure(error: Throwable) = ImportJobText.failure(error).english()

    @Test
    fun `a full disk is named`() {
        val text = failure(ProjectError.Io("import", IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("The storage is full. Free some space and import again. Nothing was added to the project list.", text)
        assertTrue(failure(ProjectError.Bundle("Not enough free space: this bundle needs 7.5 GB and 1.0 GB are free.")).startsWith("The storage is full").not())
    }

    @Test
    fun `a lost permission is named, also behind a wrapper`() {
        assertTrue(failure(SecurityException("Permission Denial")).contains("no longer has permission to read that file"))
        assertTrue(failure(ProjectError.Io("import", IOException("Cannot read x", SecurityException("revoked")))).contains("permission"))
    }

    @Test
    fun `a file that is gone, or whose app is gone, says so`() {
        val gone = failure(ProjectError.Io("import from content://x", FileNotFoundException("content://x: open failed: ENOENT")))
        assertTrue(gone, gone.contains("could not be opened. It may have been moved or deleted"))
        assertTrue(failure(ProjectError.Io("import", IOException("Cannot read content://gone/1: Unknown URI"))).contains("could not be opened"))
    }

    @Test
    fun `a bad file keeps its own words`() {
        val bundle = failure(ProjectError.Bundle("The bundle is damaged: the file ends before byte 4096"))
        assertEquals("The bundle is damaged: the file ends before byte 4096. Nothing was added to the project list.", bundle)
        val corrupt = failure(ProjectError.Corrupt("not JSON"))
        assertTrue(corrupt, corrupt.startsWith("This is not a readable project (not JSON)."))
        val newer = failure(ProjectError.UnsupportedVersion(99, 3))
        assertTrue(newer, newer.contains("newer than supported") && newer.contains("Update the app"))
    }

    @Test
    fun `any other error still names its cause and never a bare class`() {
        val text = failure(ProjectError.Io("import", IOException("Connection reset")))
        assertTrue(text, text.contains("Connection reset"))
        assertTrue(failure(IllegalStateException()).contains("IllegalStateException"))
        assertTrue(failure(RuntimeException("odd")).endsWith("Nothing was added to the project list."))
    }

    @Test
    fun `a failure is shown in the bar and the notification`() {
        val state = ImportJobState.Failed("u", "Holiday.uvbundle", UiText.Raw("The storage is full."))

        val view = importViewFor(state)!!
        assertEquals(ImportView.Phase.FAILED, view.phase)
        assertEquals("The storage is full.", view.message.english())
        assertNotNull(importNotificationFor(state))
        assertFalse(view.canOpen)
    }

    // endregion
}
