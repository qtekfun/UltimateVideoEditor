package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a JVM cannot hear but can still hold in place. Two defects reached a user with an external microphone: the video's sound from the
 * speaker was recorded with the voice, and the capture thread starved while the editor decoded. The acoustics are checked on a device
 * with `scripts/qa/check-take.py` (docs/QA.md, D15); these tests keep the two protections from being deleted by a refactor.
 */
class VoiceCaptureGuardTest {
    private val sourceRoot: File =
        listOf(File("."), File("app")).map { File(it, "src/main/kotlin/com/qtekfun/ultimatevideoeditor") }.first { it.isDirectory }

    private fun source(path: String) = File(sourceRoot, path).readText()

    @Test
    fun `the capture thread runs at audio priority and the microphone buffer holds at least a second`() {
        val recorder = source("data/voice/AndroidVoiceRecorder.kt")
        assertTrue(recorder.contains("Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)"))
        assertTrue(recorder.contains("SAMPLE_RATE * 2 * CHANNELS"))
        assertTrue(recorder.contains("recordBufferBytes(minBuffer)"))
    }

    @Test
    fun `the editor silences the timeline for a take through the controller's rule`() {
        assertTrue(source("ui/editor/EditorScreen.kt").contains("voice.timelineSilenced"))
        assertTrue(source("ui/editor/EditorVoice.kt").contains("privateOutput = { MonitorOutput.isPrivate(context) }"))
        assertTrue(source("ui/editor/EditorAudio.kt").contains("withAllTracksMuted()"))
    }
}
