package com.qtekfun.ultimatevideoeditor.ui.editor

/**
 * What the editor's transport drives while playing: in practice the audio engine, whose device
 * clock is the master clock. All calls happen on the main thread. Frames are project frames.
 */
interface PlaybackOutput {
    /** Starts output at [fromFrame]. */
    fun play(fromFrame: Long)

    fun pause()

    /** Moves the output to [frame] while paused. */
    fun seek(frame: Long)

    /** Project frame being heard right now, or null if the output cannot report one. */
    fun heardFrame(): Long?

    /** Frees the audio device (the app is in the background). Playing again reopens it. */
    fun releaseDevice() {}
}
