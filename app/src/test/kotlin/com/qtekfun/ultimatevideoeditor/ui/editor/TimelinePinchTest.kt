package com.qtekfun.ultimatevideoeditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinch zooms the time axis only; there is no way left to change the lane height by gesture (DECISIONS "No vertical zoom"). */
class TimelinePinchTest {
    private class Recorder {
        val zooms = mutableListOf<Pair<Float, Float>>()
        val pinch = TimelinePinch { factor, focusX -> zooms += factor to focusX }
    }

    @Test
    fun `a horizontal pinch zooms the time axis around the focus`() {
        val r = Recorder()
        r.pinch.onScale(1.1f, 300f)
        r.pinch.onScale(1.2f, 310f)
        assertEquals(listOf(1.1f to 300f, 1.2f to 310f), r.zooms)
    }

    @Test
    fun `a vertical pinch reaches nothing but the time axis`() {
        val r = Recorder()
        // Fingers spread up and down: the detector still reports a scale factor, and it is a time-axis zoom, as before lane zoom
        // existed. The only callback a TimelinePinch has is the time zoom, so the lane height cannot change.
        r.pinch.onScale(1.5f, 200f)
        assertEquals(listOf(1.5f to 200f), r.zooms)
        assertEquals(1, TimelinePinch::class.java.declaredConstructors.single().parameterCount)
    }

    @Test
    fun `degenerate factors are ignored`() {
        val r = Recorder()
        r.pinch.onScale(0f, 1f)
        r.pinch.onScale(-1f, 1f)
        r.pinch.onScale(Float.NaN, 1f)
        r.pinch.onScale(Float.POSITIVE_INFINITY, 1f)
        assertTrue(r.zooms.isEmpty())
    }

    @Test
    fun `no lane zoom entry point is left on the engine or the native bindings`() {
        val loader = TimelinePinch::class.java.classLoader
        for (name in listOf("com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine", "com.qtekfun.ultimatevideoeditor.engine.timeline.NativeTimeline")) {
            val methods = Class.forName(name, false, loader).declaredMethods.map { it.name.lowercase() }
            assertTrue("$name still has a lane zoom method", methods.none { "zoomlanes" in it || "lanezoom" in it })
        }
        // The only lane height input is the preset (a setting), which takes a scale, not a pinch factor and a focus.
        val setters = Class.forName("com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine", false, loader).declaredMethods
            .filter { it.name == "setLaneScale" }
        assertEquals(1, setters.size)
        assertEquals(2, setters.single().parameterCount)  // the lane scale and the audio lanes' factor
    }
}
