package com.qtekfun.ultimatevideoeditor.ui.editor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** [PlaybackAudioFocus] on the platform's [AudioManager]: media usage, movie content, no permission needed. */
class AndroidPlaybackAudioFocus(context: Context) : PlaybackAudioFocus {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null

    override fun request(onLost: () -> Unit) {
        abandon()
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(
                { change ->
                    // CAN_DUCK keeps playing: the system lowers our volume itself only when it is asked to, and a short sound is no reason to stop.
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
                },
                handler,
            )
            .build()
        request = next
        audioManager?.requestAudioFocus(next)
    }

    override fun abandon() {
        val held = request ?: return
        request = null
        audioManager?.abandonAudioFocusRequest(held)
    }
}
