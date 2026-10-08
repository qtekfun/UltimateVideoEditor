package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportBarTest {
    private fun running(permille: Int, estimate: ExportEstimate = ExportEstimate()) =
        ExportJobState.Running("p1", "Holiday", permille, 0, estimate)

    @Test
    fun `running shows whole percent and the time left when known`() {
        val bar = exportBarFor(running(425, ExportEstimate(remainingMs = 125_000))) as ExportBar.Running

        assertEquals(42, bar.percent)
        assertEquals("p1", bar.projectId)
        assertTrue(bar.detail, bar.detail.startsWith("42% · about "))
    }

    @Test
    fun `running without an estimate or while stalled shows only the percent`() {
        assertEquals("0%", (exportBarFor(running(0)) as ExportBar.Running).detail)
        assertEquals("50%", (exportBarFor(running(500, ExportEstimate(remainingMs = 9_000, stalled = true))) as ExportBar.Running).detail)
    }

    @Test
    fun `finished and failed stay as bars until dismissed`() {
        val done = exportBarFor(ExportJobState.Done("p1", "Holiday", "content://x", "Holiday.mp4"))
        assertEquals(ExportBar.Finished("p1", "Holiday", "content://x", "Holiday.mp4"), done)

        val failed = exportBarFor(ExportJobState.Failed("p1", "Holiday", ExportException(ExportErrorCode.CODEC_ERROR, "boom"))) as ExportBar.Failed
        assertTrue(failed.message, failed.message.contains("boom"))
    }

    @Test
    fun `idle and cancelled have no bar`() {
        assertNull(exportBarFor(ExportJobState.Idle))
        assertNull(exportBarFor(ExportJobState.Cancelled("p1", "Holiday")))
    }

    @Test
    fun `Export is available when nothing runs, shows progress for the running project and is blocked for others`() {
        assertEquals(ExportAvailability.Available, exportAvailability(ExportJobState.Idle, "p2"))
        assertEquals(ExportAvailability.Available, exportAvailability(ExportJobState.Done("p1", "Holiday", "u", "f"), "p2"))
        assertEquals(ExportAvailability.RunningHere, exportAvailability(running(10), "p1"))
        val blocked = exportAvailability(running(10), "p2") as ExportAvailability.BlockedBy
        assertEquals("Another export is running: Holiday", blocked.message)
    }

    @Test
    fun `the notification opens the editor of the exporting project while it has something to show`() {
        val editor = ExportDestination.Editor("p1")
        assertEquals(editor, exportDestination("p1", true, running(100)))
        assertEquals(editor, exportDestination("p1", true, ExportJobState.Done("p1", "Holiday", "u", "f")))
        assertEquals(editor, exportDestination("p1", true, ExportJobState.Failed("p1", "Holiday", null)))
    }

    @Test
    fun `a stale or mismatched notification leads to the project list`() {
        val list = ExportDestination.ProjectList
        assertEquals(list, exportDestination("p1", false, running(100))) // project deleted
        assertEquals(list, exportDestination("p1", true, ExportJobState.Idle)) // process restarted, export gone
        assertEquals(list, exportDestination("p1", true, running(100).copy(projectId = "p2"))) // another export now
        assertEquals(list, exportDestination("p1", true, ExportJobState.Cancelled("p1", "Holiday")))
        assertEquals(list, exportDestination(null, true, running(100))) // no id in the intent
    }

    @Test
    fun `acknowledge for a project leaves another project's result alone`() {
        val state = kotlinx.coroutines.flow.MutableStateFlow<ExportJobState>(ExportJobState.Done("p1", "Holiday", "u", "f"))
        val host = object : ExportJobHost {
            override val state = state
            override fun cancel() = Unit
            override fun acknowledge(onlyProject: String?) {
                state.value = state.value.let { if (onlyProject != null && it.projectId != onlyProject) it else ExportJobState.Idle }
            }
        }
        host.acknowledge("p2")
        assertTrue(state.value is ExportJobState.Done)
        host.acknowledge()
        assertEquals(ExportJobState.Idle, state.value)
    }
}
