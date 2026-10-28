package com.qtekfun.ultimatevideoeditor.ui.editor

/**
 * The preview's claim on the device's audio output. While it plays, other apps' music is paused by the system; when it
 * stops the claim is given back. The platform part is in `AndroidPlaybackAudioFocus`; the view model only sees this.
 * All calls happen on the main thread.
 */
interface PlaybackAudioFocus {
    /**
     * Asks for focus before playback starts. [onLost] is called when focus is taken away for good or for a while (another
     * app's playback, a call), never for a transient duck. Playback goes ahead whatever the answer, so a refusal is not an error.
     */
    fun request(onLost: () -> Unit)

    /** Gives focus up (playback paused or ended). Safe to call when nothing was requested. */
    fun abandon()
}

/** For tests and screens without a platform: holds no focus. */
object NoPlaybackAudioFocus : PlaybackAudioFocus {
    override fun request(onLost: () -> Unit) = Unit

    override fun abandon() = Unit
}
