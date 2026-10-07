package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.AudioRole
import com.ultimatevideo.uveditor.domain.ParamIds
import com.ultimatevideo.uveditor.domain.ParamTracks
import com.ultimatevideo.uveditor.engine.audio.AutoParam
import com.ultimatevideo.uveditor.engine.audio.AutoPoint
import com.ultimatevideo.uveditor.engine.audio.AutomationLane
import com.ultimatevideo.uveditor.domain.ClipEq
import com.ultimatevideo.uveditor.domain.VoiceFx
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.isTrackAudible
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.engine.EngineException
import com.ultimatevideo.uveditor.engine.audio.AudioClipSpec
import com.ultimatevideo.uveditor.engine.audio.AudioErrorCode
import com.ultimatevideo.uveditor.engine.audio.AudioException
import com.ultimatevideo.uveditor.engine.audio.LoudnessResult
import com.ultimatevideo.uveditor.engine.audio.PeakLevels
import com.ultimatevideo.uveditor.engine.audio.AudioFault
import com.ultimatevideo.uveditor.engine.audio.AudioPlaybackEngine
import com.ultimatevideo.uveditor.engine.audio.AudioSnapshot
import com.ultimatevideo.uveditor.engine.audio.AudioTrackSpec
import com.ultimatevideo.uveditor.engine.audio.CompressorSpec
import com.ultimatevideo.uveditor.engine.audio.DuckingSpec
import com.ultimatevideo.uveditor.engine.audio.EqBandSpec
import com.ultimatevideo.uveditor.engine.audio.EqSpec
import com.ultimatevideo.uveditor.engine.audio.RetimeKnot
import com.ultimatevideo.uveditor.engine.audio.TrackRole
import com.ultimatevideo.uveditor.engine.audio.VoiceField
import com.ultimatevideo.uveditor.engine.audio.VoiceLane
import com.ultimatevideo.uveditor.engine.audio.VoiceSpec
import com.ultimatevideo.uveditor.domain.RenderClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * The audible clips of [timeline]: audio-track clips and the embedded audio of video clips, with
 * each transition folded in as an overlap and an equal-power fade (see [renderClips]).
 */
internal fun audioSnapshotOf(
    timeline: Timeline,
    assets: List<MediaAssetDto>,
    fps: FrameRate,
    clipKey: (String) -> Long,
    assetKey: (String) -> Long,
): AudioSnapshot {
    val withAudio = assets.filter { it.hasAudio }.associateBy { it.id }
    // One mixer track per timeline track that can carry sound (video clips bring their embedded audio).
    val mixerTracks = timeline.tracks.filter { it.type != TrackType.TITLE }
    val indexOfTrack = mixerTracks.withIndex().associate { (i, t) -> t.id to i }
    val specs = timeline.renderClips().mapNotNull { clip ->
        if (clip.kind == RenderKind.TITLE) return@mapNotNull null
        val asset = withAudio[clip.assetId] ?: return@mapNotNull null
        val trackIndex = indexOfTrack[clip.trackId] ?: return@mapNotNull null
        val knots = retimeKnotsOf(clip)
        if (clip.retime != null && knots.isEmpty()) return@mapNotNull null // too fast, too slow or frozen: silent
        val tools = clip.audio
        // The clip's own fade handles are measured from the clip's own start and end. Where a transition
        // already ramps that edge (the render window starts earlier or ends later) the crossfade is the fade.
        val fadeIn = if (clip.crossfadeInFrames > 0) 0L else tools.fadeInFrames
        val fadeOut = if (clip.crossfadeOutFrames > 0) 0L else tools.fadeOutFrames
        val denoise = tools.denoise
        AudioClipSpec(
            clipKey = clipKey(clip.clipId),
            assetKey = assetKey(asset.id),
            startFrame = clip.startFrame,
            durationFrames = clip.durationFrames,
            sourceInFrame = clip.sourceInFrame.coerceAtLeast(0),
            // A clip's source range is in project frames, so the source rate is the project's.
            sourceFpsNum = fps.num,
            sourceFpsDen = fps.den,
            // Volume and the stored loudness-normalise gain share one stage.
            gainDb = (clip.gainDb + tools.normalizeDb).toFloat().coerceIn(AudioClipSpec.MIN_GAIN_DB, AudioClipSpec.MAX_GAIN_DB),
            fadeInFrames = clip.crossfadeInFrames,
            fadeOutFrames = clip.crossfadeOutFrames,
            retimeKnots = knots,
            trackIndex = trackIndex,
            pan = tools.pan.toFloat(),
            userFadeInFrames = fadeIn.coerceIn(0, clip.durationFrames),
            userFadeOutFrames = fadeOut.coerceIn(0, clip.durationFrames),
            fadeShape = tools.fadeShape.code,
            eq = eqSpecOf(tools.eq),
            denoiseStrength = denoise?.strength?.toFloat() ?: 0f,
            noiseProfile = denoise?.profile ?: emptyList(),
            automation = automationLanesOf(clip),
            voice = voiceSpecOf(tools.voice),
            voiceAutomation = voiceLanesOf(clip),
        )
    }
    val tracks = mixerTracks.map { track ->
        val a = track.audio
        AudioTrackSpec(
            trackKey = stableTrackKey(track.id),
            gainDb = a.volumeDb.toFloat().coerceIn(AudioClipSpec.MIN_GAIN_DB, AudioClipSpec.MAX_GAIN_DB),
            muted = !timeline.isTrackAudible(track),
            role = when (a.role) {
                AudioRole.NORMAL -> TrackRole.NORMAL
                AudioRole.VOICE -> TrackRole.VOICE
                AudioRole.MUSIC -> TrackRole.MUSIC
            },
            compressor = a.compressor?.let {
                CompressorSpec(it.thresholdDb.toFloat(), it.ratio.toFloat(), it.attackMs.toFloat(), it.releaseMs.toFloat(), it.makeupDb.toFloat())
            },
        )
    }.ifEmpty { listOf(AudioTrackSpec(trackKey = 0)) }
    val ducking = timeline.ducking?.takeIf { it.amountDb > 0.0 }?.let {
        DuckingSpec(it.amountDb.toFloat(), it.thresholdDb.toFloat(), it.attackMs.toFloat(), it.releaseMs.toFloat())
    }
    return AudioSnapshot(fps.num, fps.den, specs, tracks, ducking)
}

/**
 * The keyframed volume, pan and EQ band gains of [clip] as mixer lanes. Keys are measured from the clip's own
 * start, the snapshot from the start of its render window (a transition can begin earlier), so frames are
 * shifted by the difference. The volume lane carries the same sum the static gain does (volume plus the stored
 * loudness-normalise gain), so animating the volume never changes the normalised level.
 */
internal fun automationLanesOf(clip: RenderClip): List<AutomationLane> {
    if (clip.params.isEmpty()) return emptyList()
    val shift = clip.keyframeOriginFrame - clip.startFrame
    val lanes = ArrayList<AutomationLane>(clip.params.size)
    for (track in clip.params) {
        val (param, offset) = when {
            track.paramId == ParamIds.GAIN_DB -> AutoParam.GAIN_DB to clip.audio.normalizeDb
            track.paramId == ParamIds.PAN -> AutoParam.PAN to 0.0
            else -> {
                val band = ParamIds.parseEqBand(track.paramId)?.takeIf { it in 0 until ClipEq.BAND_COUNT } ?: continue
                AutoParam.eqGain(band) to 0.0
            }
        }
        val points = ParamTracks.audioPoints(track.keys)
            .map { (frame, value) -> AutoPoint(frame + shift, (value + offset).toFloat().coerceIn(param.min, param.max)) }
            .filter { it.frame in 0..clip.durationFrames }
        if (points.isNotEmpty()) lanes += AutomationLane(param, points)
    }
    return lanes
}

/** A key for a track id that is stable across snapshots, so the mixer keeps its envelopes running through edits. */
internal fun stableTrackKey(trackId: String): Long = trackId.hashCode().toLong()

/**
 * The keyframed voice sliders of [clip] as engine lanes. A slider is one to three numbers that the preset turns into the
 * engine's settings with affine maps (see [VoiceFx.params]), so the settings are evaluated at every frame where any
 * animated slider has a point (its keys, plus one point per frame inside an eased or Bezier segment, like
 * [ParamTracks.audioPoints]); the engine then interpolates linearly between them, which is exact for the maps involved.
 * A setting that never differs from its static value gets no lane. Frames are shifted like those of [automationLanesOf].
 */
internal fun voiceLanesOf(clip: RenderClip): List<VoiceLane> {
    val voice = clip.audio.voice ?: return emptyList()
    val tracks = voice.preset.sliders.indices.mapNotNull { i -> ParamTracks.track(clip.params, ParamIds.voice(i))?.let { i to it.keys } }
    if (tracks.isEmpty()) return emptyList()
    val shift = clip.keyframeOriginFrame - clip.startFrame
    val frames = tracks.flatMap { (_, keys) -> ParamTracks.audioPoints(keys).map { it.first } }.toSortedSet()
        .filter { it + shift in 0..clip.durationFrames }
    if (frames.isEmpty()) return emptyList()
    val fixed = voiceSpecOf(voice)
    val specs = frames.map { frame ->
        val values = voice.values.mapIndexed { i, base ->
            val keys = tracks.firstOrNull { it.first == i }?.second
            if (keys == null) base else ParamTracks.evaluate(keys, frame, base)
        }
        frame to voiceSpecOf(voice.copy(values = values))
    }
    return VoiceField.entries.mapNotNull { field ->
        val points = specs.map { (frame, spec) -> AutoPoint(frame + shift, field.clamp(spec.valueOf(field))) }
        if (points.all { it.value == fixed.valueOf(field) }) null else VoiceLane(field, points)
    }
}

/** The engine's view of a clip's voice effect: the preset's sliders resolved to the native chain's settings. */
internal fun voiceSpecOf(fx: VoiceFx?): VoiceSpec {
    val p = fx?.params() ?: return VoiceSpec.NONE
    if (p.isNeutral) return VoiceSpec.NONE
    return VoiceSpec(
        pitchSemitones = p.pitchSemitones.toFloat(),
        formantSemitones = p.formantSemitones.toFloat(),
        whisperMix = p.whisperMix.toFloat(),
        ringHz = p.ringHz.toFloat(),
        ringMix = p.ringMix.toFloat(),
        bandLowHz = p.bandLowHz.toFloat(),
        bandHighHz = p.bandHighHz.toFloat(),
        driveDb = p.driveDb.toFloat(),
        echoMs = p.echoMs.toFloat(),
        echoFeedback = p.echoFeedback.toFloat(),
        echoMix = p.echoMix.toFloat(),
        reverbSize = p.reverbSize.toFloat(),
        reverbDamping = p.reverbDamping.toFloat(),
        reverbMix = p.reverbMix.toFloat(),
    )
}

internal fun eqSpecOf(eq: ClipEq): EqSpec =
    if (eq.isFlat) {
        EqSpec.FLAT
    } else {
        EqSpec(
            highPassHz = eq.highPassHz.toFloat(),
            lowPassHz = eq.lowPassHz.toFloat(),
            bands = eq.bands.map { EqBandSpec(it.freqHz.toFloat(), it.gainDb.toFloat(), it.q.toFloat()) },
        )
    }

/** Slowest and fastest source speed (frames per timeline frame) that is still played; beyond it a clip is muted. */
internal const val MIN_AUDIBLE_SPEED = 0.25
internal const val MAX_AUDIBLE_SPEED = 4.0
private const val AUDIO_KNOT_STEP_FRAMES = 12L

/**
 * The mixer's view of a retimed clip's mapping, or empty when the clip is not retimed or must be
 * silent: a freeze frame has no sound, and between [MIN_AUDIBLE_SPEED] and [MAX_AUDIBLE_SPEED] is the
 * range where varispeed audio is still usable (pitch follows the speed). A ramp is checked at every
 * knot, so one fast stretch mutes the whole clip rather than playing noise.
 */
internal fun retimeKnotsOf(clip: RenderClip): List<RetimeKnot> {
    val retime = clip.retime ?: return emptyList()
    if (retime.isFreeze) return emptyList()
    val from = clip.startFrame - clip.keyframeOriginFrame
    val knots = retime.sourceKnots(from, clip.endFrame - clip.keyframeOriginFrame, AUDIO_KNOT_STEP_FRAMES)
    val audible = knots.zipWithNext().all { (a, b) ->
        val speed = kotlin.math.abs(b.second - a.second) / (b.first - a.first).toDouble()
        speed in MIN_AUDIBLE_SPEED..MAX_AUDIBLE_SPEED
    }
    return if (audible) knots.map { RetimeKnot(it.first, it.second) } else emptyList()
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
) : PlaybackOutput, AudioAnalyzer, AutoCloseable {

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

    /** Output peaks since the previous call, for the level meters (silent when the engine is unavailable). */
    fun takePeaks(): PeakLevels = engine?.takePeaks() ?: PeakLevels.SILENT

    // region analysis (loudness, noise profile)

    override suspend fun loudness(asset: MediaAssetDto, assetKey: Long, startMicros: Long, endMicros: Long): LoudnessResult {
        val engine = engine ?: throw AudioAnalysisException("Audio measurements are not available")
        awaitRegistered(engine, asset, assetKey)
        return try {
            withContext(Dispatchers.IO) { engine.measureLoudness(assetKey, startMicros, endMicros) }
        } catch (e: AudioException) {
            throw analysisFailure(e, tooShort = "That stretch is too short to measure")
        }
    }

    override suspend fun noiseProfile(asset: MediaAssetDto, assetKey: Long, startMicros: Long, endMicros: Long): FloatArray {
        val engine = engine ?: throw AudioAnalysisException("Audio measurements are not available")
        awaitRegistered(engine, asset, assetKey)
        return try {
            withContext(Dispatchers.IO) { engine.measureNoiseProfile(assetKey, startMicros, endMicros) }
        } catch (e: AudioException) {
            throw analysisFailure(e, tooShort = "The quiet stretch is too short: mark at least 0.1 s")
        }
    }

    override fun cancel() {
        engine?.cancelAnalysis()
    }

    /** Analyses open the file through the same registry as playback: wait for (or start) its registration. */
    private suspend fun awaitRegistered(engine: AudioPlaybackEngine, asset: MediaAssetDto, key: Long) {
        if (key !in registered && key !in opening && key !in failed) register(engine, asset, key)
        var waited = 0L
        while (key !in registered) {
            if (key in failed) throw AudioAnalysisException("This file's sound cannot be opened")
            if (waited >= REGISTER_TIMEOUT_MILLIS) throw AudioAnalysisException("The file took too long to open")
            delay(REGISTER_POLL_MILLIS)
            waited += REGISTER_POLL_MILLIS
        }
    }

    private fun analysisFailure(e: AudioException, tooShort: String) = AudioAnalysisException(
        when (e.errorCode) {
            AudioErrorCode.Cancelled -> "The measurement was cancelled"
            AudioErrorCode.InvalidArgument -> tooShort
            AudioErrorCode.UnsupportedFormat -> "The sound of this file is in a format that cannot be measured"
            else -> "The sound of this file could not be read (${e.errorCode})"
        },
        e,
    )

    // endregion

    override fun close() {
        closed = true
        stopJob?.cancel()
        engine?.close()
    }

    private companion object {
        const val FAULT_POLL_MILLIS = 500L
        const val IDLE_STOP_MILLIS = 1_500L
        const val REGISTER_POLL_MILLIS = 50L
        const val REGISTER_TIMEOUT_MILLIS = 10_000L
    }
}
