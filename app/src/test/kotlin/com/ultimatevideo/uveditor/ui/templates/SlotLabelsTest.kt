package com.ultimatevideo.uveditor.ui.templates

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotLabelsTest {
    @Test fun singularForOne() = assertEquals("1 slot", slotCountLabel(1))

    @Test fun pluralOtherwise() {
        assertEquals("0 slots", slotCountLabel(0))
        assertEquals("2 slots", slotCountLabel(2))
    }
}
