package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.engine.EngineException
import com.ultimatevideo.uveditor.engine.audio.AudioClipSpec
import com.ultimatevideo.uveditor.engine.audio.AudioException
import com.ultimatevideo.uveditor.engine.audio.AudioFault
import com.ultimatevideo.uveditor.engine.audio.AudioPlaybackEngine
import com.ultimatevideo.uveditor.engine.audio.AudioSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** The audible clips of [timeline]: audio-track clips and the embedded audio of video clips. */
internal fun audioSnapshotOf(
    timeline: Timeline,
    assets: List<MediaAssetDto>,
    fps: FrameRate,
    clipKey: (String) -> Long,
    assetKey: (String) -> Long,
): AudioSnapshot {
    val withAudio = assets.filter { it.hasAudio }.associateBy { it.id }
    val specs = timeline.tracks.flatMap { track ->
        track.clips.mapNotNull { clip ->
            val asset = withAudio[clip.assetId] ?: return@mapNotNull null
            AudioClipSpec(
                clipKey = clipKey(clip.id),
                assetKey = assetKey(asset.id),
                startFrame = clip.timelineStart.value,
                durationFrames = clip.durationFrames,
                sourceInFrame = clip.sourceIn.value,
                // A clip's source range is in project frames, so the source rate is the project's.
                sourceFpsNum = fps.num,
                sourceFpsDen = fps.den,
                gainDb = clip.gainDb.toFloat().coerceIn(AudioClipSpec.MIN_GAIN_DB, AudioClipSpec.MAX_GAIN_DB),
            )
        }
    }
    return AudioSnapshot(fps.num, fps.den, specs)
}

/**
 * Audio output of the editor. The native audio engine is the master clock while playing. Assets
 * are registered lazily (opening does blocking I/O, so it happens off the main thread) and the
 * latest snapshot is applied once they are known. Failures are reported through [onError], never
 * swallowed; if the audio device cannot start, playback continues silently on the system clock.
 *
 * The output stream is only open while it is needed: it opens on [play], closes [IDLE_STOP_MILLIS]
 * after a [pause] (so a quick pause/play does not reopen the device) and at once in [releaseDevice],
 * which the screen calls when the app goes to the background. Mixer state (assets, snapshot, the
 * position) survives a closed stream, and scrubbing while paused never needs the device.
 */
class EditorAudio(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) : PlaybackOutput, AutoCloseable {

    private val engine: AudioPlaybackEngine? = try {
        AudioPlaybackEngine()
    } catch (e: EngineException) {
        onError("Audio is unavailable: ${e.message}")
        null
    }

    private var streamOpen = false
    private var stopJob: Job? = null

    private val registered = HashSet<Long>()
    private val opening = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val reportedDecodeFaults = HashSet<Long>()
    private var latest: AudioSnapshot? = null
    private var closed = false

    init {
        if (engine != null) {
            scope.launch {
                while (!closed) {
                    delay(FAULT_POLL_MILLIS)
                    pollFaults(engine)
                }
            }
        }
    }

    /** Registers any new [assets] and sends [snapshot] to the mixer. */
    fun update(snapshot: AudioSnapshot, assets: List<MediaAssetDto>, assetKeyOf: (String) -> Long) {
        val engine = engine ?: return
        latest = snapshot
        for (asset in assets) {
            if (!asset.hasAudio) continue
            val key = assetKeyOf(asset.id)
            if (key in registered || key in opening || key in failed) continue
            register(engine, asset, key)
        }
        // Clips of assets still opening play silence until their registration re-applies this.
        apply(engine, snapshot)
    }

    private fun register(engine: AudioPlaybackEngine, asset: MediaAssetDto, key: Long) {
        opening += key
        scope.launch {
            val error = try {
                val descriptor = withContext(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(Uri.parse(asset.uri), "r") ?: throw FileNotFoundException(asset.uri)
                }
                descriptor.use { engine.setAsset(key, it) }
                null
            } catch (e: FileNotFoundException) {
                "A media file is missing, so its audio cannot play"
            } catch (e: SecurityException) {
                "No permission to read a media file's audio"
            } catch (e: AudioException) {
                "A clip's audio cannot be played: ${e.message}"
            }
            opening -= key
            if (error != null) {
                failed += key
                onError(error)
                return@launch
            }
            registered += key
            if (!closed) latest?.let { apply(engine, it) }
        }
    }

    private fun apply(engine: AudioPlaybackEngine, snapshot: AudioSnapshot) {
        // A clip whose media is not registered yet would fail to decode ("never registered") and
        // raise a fault. Send only playable clips; registration re-applies the latest snapshot.
        val playable = snapshot.copy(clips = snapshot.clips.filter { it.assetKey in registered })
        try {
            engine.setSnapshot(playable)
        } catch (e: AudioException) {
            onError("The audio could not be updated: ${e.message}")
        }
    }

    private fun pollFaults(engine: AudioPlaybackEngine) {
        for (fault in engine.pollFaults()) {
            when (fault) {
                // An underrun is a glitch, not a failure to report to the user on every occurrence.
                is AudioFault.Underrun -> Unit
                is AudioFault.Decode -> if (reportedDecodeFaults.add(fault.clipKey)) {
                    onError("A clip's audio could not be decoded (${fault.error})")
                }
                is AudioFault.Device -> onError("The audio device failed (${fault.error})")
            }
        }
    }

    override fun play(fromFrame: Long) {
        val engine = engine ?: return
        stopJob?.cancel()
        try {
            openStream(engine)
            engine.seek(fromFrame)
            engine.play()
        } catch (e: AudioException) {
            onError("Audio playback failed: ${e.message}")
        }
    }

    override fun pause() {
        val engine = engine ?: return
        engine.pause()
        stopJob?.cancel()
        // Close the device after a short idle, not at once: pausing and resuming within a moment is common.
        stopJob = scope.launch {
            delay(IDLE_STOP_MILLIS)
            closeStream(engine)
        }
    }

    override fun releaseDevice() {
        val engine = engine ?: return
        stopJob?.cancel()
        engine.pause()
        closeStream(engine)
    }

    private fun openStream(engine: AudioPlaybackEngine) {
        if (streamOpen) return
        engine.start()
        streamOpen = true
    }

    private fun closeStream(engine: AudioPlaybackEngine) {
        if (!streamOpen) return
        streamOpen = false
        engine.stop()
    }

    override fun seek(frame: Long) {
        val engine = engine ?: return
        try {
            engine.seek(frame)
        } catch (e: AudioException) {
            onError("Audio seek failed: ${e.message}")
        }
    }

    override fun heardFrame(): Long? = engine?.positionFrame()

    override fun close() {
        closed = true
        stopJob?.cancel()
        engine?.close()
    }

    private companion object {
        const val FAULT_POLL_MILLIS = 500L
        const val IDLE_STOP_MILLIS = 1_500L
    }
}
