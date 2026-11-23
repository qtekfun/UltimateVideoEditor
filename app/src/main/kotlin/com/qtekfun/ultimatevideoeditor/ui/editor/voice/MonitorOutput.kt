package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Whether the sound of the timeline reaches only the person while a take is recorded. Through the phone's speaker the microphone would
 * record the video's sound together with the voice, so without a private output the timeline is silent during the take.
 */
object MonitorOutput {
    private val PRIVATE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** True when one of the connected output [types] is a headset or headphones. */
    fun isPrivate(types: Collection<Int>): Boolean = types.any { it in PRIVATE_TYPES }

    fun isPrivate(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return isPrivate(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    }
}
