package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the person is looking at before deciding to use it: a file of the project or of a linked folder. [uri] is how to read it;
 * [kind] says whether to play it, show it or just play its sound.
 */
data class SourceItem(val uri: String, val name: String, val kind: AssetKind, val sizeBytes: Long? = null)

/** Why a file cannot be previewed; each has its own text for the person. */
enum class SourceProblem {
    /** The file is gone or the access to it was removed. */
    UNREADABLE,

    /** The phone cannot play this format. */
    UNSUPPORTED,

    /** A movie is being exported and has every decoder: the preview waits for it to finish. */
    BUSY,
}

/** The facts shown under the picture. Anything the file does not say is null. */
data class SourceInfo(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val framesPerSecond: Float?,
    val mime: String?,
    val bitrate: Long?,
)

/** How far along the preview is. */
sealed interface SourcePhase {
    data object Loading : SourcePhase

    data object Ready : SourcePhase

    data class Failed(val problem: SourceProblem) : SourcePhase
}

/** The player behind the preview. An interface so the controller is tested without Android's media stack. */
interface AuditionPlayer {
    interface Listener {
        /** The file is ready: its length (0 for a picture) and the size of its picture (0 x 0 when it has none). */
        fun onPrepared(durationMs: Long, width: Int, height: Int)

        fun onCompleted()

        fun onFailed(problem: SourceProblem)
    }

    /** Starts reading [item]; the listener hears the outcome. Paused at the start. */
    fun open(item: SourceItem)

    /** Where the picture is drawn, or null when it is not on screen. */
    fun setSurface(surface: Surface?)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    val positionMs: Long

    fun setMuted(muted: Boolean)

    fun setLooping(looping: Boolean)

    /** Lets go of the decoder. The player is not used again. */
    fun release()
}

/** Reads the facts of a file without playing it. Null when it cannot be read. */
fun interface SourceInfoReader {
    suspend fun read(item: SourceItem): SourceInfo?
}

/** The pure parts of the preview: positions, and the line of facts. */
object SourcePreviewMath {
    /** The position for a slider at [fraction] (0..1) of [durationMs]; out-of-range fractions are held at the ends. */
    fun positionAt(fraction: Float, durationMs: Long): Long =
        (durationMs.coerceAtLeast(0) * fraction.coerceIn(0f, 1f).toDouble()).toLong()

    /** The slider's fraction for [positionMs]; 0 when the length is not known. */
    fun fractionOf(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.coerceIn(0, durationMs).toDouble() / durationMs).toFloat()

    /** `1920 x 1080`, from the picture's size; null when it has none. */
    fun sizeText(width: Int?, height: Int?): String? =
        if (width == null || height == null || width <= 0 || height <= 0) null else "$width x $height"

    /** `29.97 fps` or `30 fps`: a whole number without decimals, others with up to two. */
    fun rateText(fps: Float?): String? {
        if (fps == null || !fps.isFinite() || fps < 1f) return null
        val rounded = Math.round(fps * 100) / 100.0
        return if (rounded == Math.rint(rounded)) "${rounded.toInt()} fps" else "$rounded fps"
    }

    /** `12.4 Mbit/s`, `850 kbit/s`; null for an unknown rate. */
    fun bitrateText(bitsPerSecond: Long?): String? = when {
        bitsPerSecond == null || bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> "%.1f Mbit/s".format(java.util.Locale.ROOT, bitsPerSecond / 1_000_000.0)
        else -> "${bitsPerSecond / 1000} kbit/s"
    }

    /** `video/mp4` to `MP4`; null for no type. */
    fun formatText(mime: String?): String? = mime?.substringAfter('/')?.takeIf { it.isNotBlank() }?.uppercase()

    /** The facts that are known, in a fixed order: size, rate, format, bit rate. */
    fun facts(info: SourceInfo?): List<String> =
        if (info == null) emptyList() else listOfNotNull(sizeText(info.width, info.height), rateText(info.framesPerSecond), formatText(info.mime), bitrateText(info.bitrate))
}

/**
 * The preview of one file before it is used: a player, where it is, and what it says about the file. Compose state, so the sheet updates
 * by reading it. Playback is paused and the decoder released as soon as the file is closed, and nothing starts while [busy] (a movie is
 * being exported and needs every decoder). Opening another file replaces the first one's player; a late answer from a player that was
 * replaced is ignored.
 */
@Stable
class SourcePreviewController(
    private val scope: CoroutineScope,
    private val newPlayer: (AuditionPlayer.Listener) -> AuditionPlayer,
    private val infoReader: SourceInfoReader = SourceInfoReader { null },
    private val tickMs: Long = 100,
) {
    var item: SourceItem? by mutableStateOf(null)
        private set

    var phase: SourcePhase by mutableStateOf(SourcePhase.Loading)
        private set

    var playing: Boolean by mutableStateOf(false)
        private set

    var positionMs: Long by mutableStateOf(0)
        private set

    var durationMs: Long by mutableStateOf(0)
        private set

    var muted: Boolean by mutableStateOf(false)
        private set

    var looping: Boolean by mutableStateOf(false)
        private set

    /** The picture's size once known, for its shape on screen. Null for sound and pictures. */
    var videoSize: Pair<Int, Int>? by mutableStateOf(null)
        private set

    var info: SourceInfo? by mutableStateOf(null)
        private set

    /** Set by the editor while a movie is exported: a file cannot be previewed then. */
    var busy: Boolean = false

    private var player: AuditionPlayer? = null
    private var surface: Surface? = null
    private var ticker: Job? = null
    private var infoJob: Job? = null
    private var generation = 0

    /** Opens [source] for preview, paused at the start. */
    fun open(source: SourceItem) {
        closePlayer()
        val mine = ++generation
        item = source
        playing = false
        positionMs = 0
        durationMs = 0
        videoSize = null
        info = null
        if (busy) {
            phase = SourcePhase.Failed(SourceProblem.BUSY)
            return
        }
        phase = SourcePhase.Loading
        val created = newPlayer(object : AuditionPlayer.Listener {
            override fun onPrepared(durationMs: Long, width: Int, height: Int) {
                if (mine != generation) return
                prepared(durationMs, width, height)
            }

            override fun onCompleted() {
                if (mine != generation) return
                // Not looping: it stops at the end, with the position there; the next play starts again from the top.
                playing = false
                ticker?.cancel()
                positionMs = durationMs
            }

            override fun onFailed(problem: SourceProblem) {
                if (mine != generation) return
                playing = false
                ticker?.cancel()
                phase = SourcePhase.Failed(problem)
            }
        })
        player = created
        created.setMuted(muted)
        created.setLooping(looping)
        surface?.let(created::setSurface)
        created.open(source)
        infoJob = scope.launch {
            val read = infoReader.read(source)
            if (mine == generation) info = read
        }
    }

    private fun prepared(length: Long, width: Int, height: Int) {
        durationMs = length.coerceAtLeast(0)
        videoSize = if (width > 0 && height > 0) width to height else null
        phase = SourcePhase.Ready
    }

    /** Lets go of everything; the sheet is gone. */
    fun close() {
        generation++
        closePlayer()
        item = null
        playing = false
        positionMs = 0
        durationMs = 0
        videoSize = null
        info = null
    }

    fun togglePlay() {
        val current = player ?: return
        if (phase != SourcePhase.Ready) return
        if (playing) {
            current.pause()
            playing = false
            ticker?.cancel()
            positionMs = current.positionMs
        } else {
            // At the end a play starts again from the top.
            if (durationMs > 0 && positionMs >= durationMs - END_SLACK_MS) {
                current.seekTo(0)
                positionMs = 0
            }
            current.play()
            playing = true
            startTicker()
        }
    }

    /** Jumps to [fraction] (0..1) of the file; playing carries on from there. */
    fun seekToFraction(fraction: Float) {
        val current = player ?: return
        if (phase != SourcePhase.Ready) return
        val target = SourcePreviewMath.positionAt(fraction, durationMs)
        current.seekTo(target)
        positionMs = target
    }

    fun toggleMute() {
        muted = !muted
        player?.setMuted(muted)
    }

    fun toggleLooping() {
        looping = !looping
        player?.setLooping(looping)
    }

    /** The picture's surface came or went. A surface that goes while playing keeps the sound, as the player does. */
    fun setSurface(next: Surface?) {
        surface = next
        player?.setSurface(next)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            try {
                while (isActive && playing) {
                    positionMs = player?.positionMs ?: break
                    delay(tickMs)
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun closePlayer() {
        ticker?.cancel()
        infoJob?.cancel()
        player?.let {
            runCatching { it.pause() }
            it.release()
        }
        player = null
    }

    private companion object {
        /** A position this close to the end counts as the end. */
        const val END_SLACK_MS = 150L
    }
}
