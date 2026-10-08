package com.qtekfun.ultimatevideoeditor.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun `there are exactly three tips and each one says something`() {
        assertEquals(3, Tips.all.size)
        assertTrue(Tips.all.all { it.title.isNotBlank() && it.body.length > 40 })
        // The tips point at the key actions the guide names.
        val text = Tips.all.joinToString(" ") { it.title + " " + it.body }.lowercase()
        listOf("import", "split", "export", "layout", "long-press").forEach { assertTrue("missing $it", text.contains(it)) }
    }

    @Test
    fun `next walks the tips and the last one finishes`() {
        var state = OnboardingState()
        assertEquals(0, state.index)
        assertFalse(state.isLast)
        state = state.next().next()
        assertEquals(2, state.index)
        assertTrue(state.isLast)
        assertFalse(state.finished)
        assertTrue(state.next().finished)
    }

    @Test
    fun `previous stops at the first tip`() {
        assertEquals(0, OnboardingState().previous().index)
        assertEquals(0, OnboardingState(index = 1).previous().index)
    }

    @Test
    fun `skip ends the tour from any tip`() {
        assertTrue(OnboardingState().dismiss().finished)
        assertTrue(OnboardingState(index = 2).dismiss().finished)
    }

    @Test
    fun `the store remembers that the tips were seen until reset`() {
        val store = FakeOnboardingStore()
        assertFalse(store.seen())
        store.markSeen()
        assertTrue(store.seen())
        store.reset()
        assertFalse(store.seen())
    }
}

class FakeOnboardingStore(private var seen: Boolean = false) : OnboardingStore {
    override fun seen() = seen

    override fun markSeen() {
        seen = true
    }

    override fun reset() {
        seen = false
    }
}
