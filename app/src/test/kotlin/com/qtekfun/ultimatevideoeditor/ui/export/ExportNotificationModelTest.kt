package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNotificationModelTest {
    private fun running(permille: Int, estimate: ExportEstimate = ExportEstimate()) =
        ExportJobState.Running("p1", "Holiday", permille, 0, estimate)

    @Test
    fun `a running export shows the project, whole percent and a cancel action`() {
        val model = checkNotNull(exportNotificationFor(running(425)))

        assertEquals("Exporting Holiday", model.title)
        assertEquals("42%", model.text)
        assertEquals(42, model.progressPercent)
        assertTrue(model.ongoing)
        assertTrue(model.showCancel)
        assertFalse(model.indeterminate)
    }

    @Test
    fun `no progress yet gives an indeterminate bar`() {
        assertTrue(checkNotNull(exportNotificationFor(running(0))).indeterminate)
    }

    @Test
    fun `the time left is added when it is known and dropped when the export is stalled`() {
        val known = checkNotNull(exportNotificationFor(running(500, ExportEstimate(remainingMs = 90_000))))
        assertEquals("50% · about 1:30 left", known.text)

        val stalled = checkNotNull(exportNotificationFor(running(500, ExportEstimate(remainingMs = 90_000, stalled = true))))
        assertEquals("50%", stalled.text)
    }

    @Test
    fun `progress is clamped to 0 to 100`() {
        assertEquals(100, checkNotNull(exportNotificationFor(running(1000))).progressPercent)
        assertEquals(0, checkNotNull(exportNotificationFor(running(-5))).progressPercent)
    }

    @Test
    fun `states that look the same compare equal so the service does not repost them`() {
        assertEquals(exportNotificationFor(running(421)), exportNotificationFor(running(424)))
    }

    @Test
    fun `finished and failed exports get a plain, dismissible notification`() {
        val done = checkNotNull(exportNotificationFor(ExportJobState.Done("p1", "Holiday", "content://x", "Holiday.mp4")))
        assertEquals("Export finished", done.title)
        assertTrue(done.text.contains("Holiday.mp4"))
        assertFalse(done.ongoing)
        assertFalse(done.showCancel)
        assertNull(done.progressPercent)

        val failed = checkNotNull(exportNotificationFor(ExportJobState.Failed("p1", "Holiday", ExportException(ExportErrorCode.CODEC_ERROR, "boom"))))
        assertEquals("Export failed", failed.title)
        assertTrue(failed.text.contains("boom"))
        assertFalse(failed.ongoing)
    }

    @Test
    fun `idle and cancelled leave no notification`() {
        assertNull(exportNotificationFor(ExportJobState.Idle))
        assertNull(exportNotificationFor(ExportJobState.Cancelled("p1", "Holiday")))
        assertNotNull(exportNotificationFor(running(1)))
    }
}
