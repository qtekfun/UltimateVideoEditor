package com.qtekfun.ultimatevideoeditor.ui.templates

import com.qtekfun.ultimatevideoeditor.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotLabelsTest {
    @Test fun singularForOne() = assertEquals("1 slot", slotCountLabel(1).english())

    @Test fun pluralOtherwise() {
        assertEquals("0 slots", slotCountLabel(0).english())
        assertEquals("2 slots", slotCountLabel(2).english())
    }
}
