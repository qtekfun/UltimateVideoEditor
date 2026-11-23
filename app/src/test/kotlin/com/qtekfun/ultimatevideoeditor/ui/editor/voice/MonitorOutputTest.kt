package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorOutputTest {
    @Test
    fun `headsets and headphones of every kind are private`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        )) assertTrue("type $type", MonitorOutput.isPrivate(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, type)))
    }

    @Test
    fun `the phone's own speaker, the earpiece and no output are not private`() {
        assertFalse(MonitorOutput.isPrivate(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        assertFalse(MonitorOutput.isPrivate(emptyList()))
    }
}
