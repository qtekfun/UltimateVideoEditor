package com.ultimatevideo.uveditor.engine.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A point of a retimed clip's mapping: at [frame] project frames after the clip's start the source
 * plays position [sourceFrame] (in project frames, continuous; it falls for a reversed clip).
 */
data class RetimeKnot(val frame: Long, val sourceFrame: Double)

/** One EQ band: a low shelf, three peaking bands and a high shelf, in that order. [gainDb] 0 leaves it off. */
data class EqBandSpec(val freqHz: Float, val gainDb: Float = 0f, val q: Float = 1f) {
    init {
        require(freqHz.isFinite() && freqHz in MIN_FILTER_HZ..MAX_FILTER_HZ) { "EQ frequency $freqHz Hz is out of range" }
        require(gainDb.isFinite() && gainDb in MIN_EQ_GAIN_DB..MAX_EQ_GAIN_DB) { "EQ gain $gainDb dB is out of range" }
        require(q.isFinite() && q in MIN_Q..MAX_Q) { "EQ Q $q is out of range" }
    }

    companion object {
        const val MIN_FILTER_HZ = 20f
        const val MAX_FILTER_HZ = 20000f
        const val MIN_EQ_GAIN_DB = -18f
        const val MAX_EQ_GAIN_DB = 18f
        const val MIN_Q = 0.1f
        const val MAX_Q = 18f
    }
}

/** The clip's EQ: optional high-pass and low-pass (0 = off) and the five bands. */
data class EqSpec(
    val highPassHz: Float = 0f,
    val lowPassHz: Float = 0f,
    val bands: List<EqBandSpec> = DEFAULT_BANDS,
) {
    init {
        require(bands.size == BAND_COUNT) { "an EQ has $BAND_COUNT bands, got ${bands.size}" }
        require(highPassHz == 0f || (highPassHz.isFinite() && highPassHz in EqBandSpec.MIN_FILTER_HZ..EqBandSpec.MAX_FILTER_HZ)) {
            "high-pass $highPassHz Hz is out of range"
        }
        require(lowPassHz == 0f || (lowPassHz.isFinite() && lowPassHz in EqBandSpec.MIN_FILTER_HZ..EqBandSpec.MAX_FILTER_HZ)) {
            "low-pass $lowPassHz Hz is out of range"
        }
    }

    companion object {
        const val BAND_COUNT = 5
        val DEFAULT_BANDS = listOf(
            EqBandSpec(100f, 0f, 0.7071f),
            EqBandSpec(400f),
            EqBandSpec(1500f),
            EqBandSpec(5000f),
            EqBandSpec(10000f, 0f, 0.7071f),
        )
        val FLAT = EqSpec()
    }
}

/** Role of a track for ducking: the voice drives the gain reduction applied to the music. */
enum class TrackRole(val value: Int) { NORMAL(0), VOICE(1), MUSIC(2) }

/** Bus compressor of a track (a feed-forward peak compressor with linked channels). */
data class CompressorSpec(
    val thresholdDb: Float = -18f,
    val ratio: Float = 3f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 120f,
    val makeupDb: Float = 0f,
) {
    init {
        require(thresholdDb.isFinite() && thresholdDb in -80f..0f) { "compressor threshold $thresholdDb dB is out of range" }
        require(ratio.isFinite() && ratio in 1f..20f) { "compressor ratio $ratio is out of range" }
        require(attackMs.isFinite() && attackMs in 0.1f..500f) { "compressor attack $attackMs ms is out of range" }
        require(releaseMs.isFinite() && releaseMs in 1f..5000f) { "compressor release $releaseMs ms is out of range" }
        require(makeupDb.isFinite() && makeupDb in 0f..24f) { "compressor make-up $makeupDb dB is out of range" }
    }
}

/**
 * One track as the mixer sees it. [gainDb] and [muted] already include solo handling (the editor
 * mutes every other track while one is soloed); the mixer ramps them so toggling never clicks.
 */
data class AudioTrackSpec(
    val trackKey: Long,
    val gainDb: Float = 0f,
    val muted: Boolean = false,
    val role: TrackRole = TrackRole.NORMAL,
    val compressor: CompressorSpec? = null,
) {
    init {
        require(gainDb.isFinite() && gainDb in AudioClipSpec.MIN_GAIN_DB..AudioClipSpec.MAX_GAIN_DB) {
            "track $trackKey gain $gainDb dB is out of range"
        }
    }
}

/** What a keyframed lane drives; [code] is the wire value (see audio_snapshot.h). */
enum class AutoParam(val code: Int, val min: Float, val max: Float) {
    GAIN_DB(0, AudioClipSpec.MIN_GAIN_DB, AudioClipSpec.MAX_GAIN_DB),
    PAN(1, -1f, 1f),
    EQ_GAIN_0(2, EqBandSpec.MIN_EQ_GAIN_DB, EqBandSpec.MAX_EQ_GAIN_DB),
    EQ_GAIN_1(3, EqBandSpec.MIN_EQ_GAIN_DB, EqBandSpec.MAX_EQ_GAIN_DB),
    EQ_GAIN_2(4, EqBandSpec.MIN_EQ_GAIN_DB, EqBandSpec.MAX_EQ_GAIN_DB),
    EQ_GAIN_3(5, EqBandSpec.MIN_EQ_GAIN_DB, EqBandSpec.MAX_EQ_GAIN_DB),
    EQ_GAIN_4(6, EqBandSpec.MIN_EQ_GAIN_DB, EqBandSpec.MAX_EQ_GAIN_DB),
    ;

    companion object {
        fun eqGain(band: Int): AutoParam = entries[EQ_GAIN_0.ordinal + band]
    }
}

/** A value at [frame] project frames after the clip's own start. */
data class AutoPoint(val frame: Long, val value: Float)

/**
 * A keyframed value of a clip: while the clip plays, [param] takes the value interpolated linearly (per
 * sample) between [points] instead of its static one, holding before the first and after the last. [points]
 * are strictly increasing in frame and inside the clip.
 */
data class AutomationLane(val param: AutoParam, val points: List<AutoPoint>) {
    init {
        require(points.isNotEmpty() && points.size <= MAX_POINTS) { "an automation lane needs 1..$MAX_POINTS points, got ${points.size}" }
        require(points.zipWithNext().all { (a, b) -> b.frame > a.frame } && points.first().frame >= 0) { "automation points must increase from frame 0" }
        require(points.all { it.value.isFinite() && it.value in param.min..param.max }) { "an automation value for $param is out of range" }
    }

    companion object {
        const val MAX_POINTS = 1 shl 20
        const val MAX_LANES = 7
    }
}

/** Sidechain ducking: while a voice track is audible the music tracks drop by [amountDb]. */
data class DuckingSpec(
    val amountDb: Float,
    val thresholdDb: Float = -35f,
    val attackMs: Float = 20f,
    val releaseMs: Float = 400f,
) {
    init {
        require(amountDb.isFinite() && amountDb in 0f..48f) { "ducking amount $amountDb dB is out of range" }
        require(thresholdDb.isFinite() && thresholdDb in -80f..0f) { "ducking threshold $thresholdDb dB is out of range" }
        require(attackMs.isFinite() && attackMs in 1f..5000f) { "ducking attack $attackMs ms is out of range" }
        require(releaseMs.isFinite() && releaseMs in 1f..5000f) { "ducking release $releaseMs ms is out of range" }
    }
}

/**
 * One audio clip as the engine needs it. Times are integer frames: [startFrame] and
 * [durationFrames] in project frames, [sourceInFrame] in the asset's native frames at
 * [sourceFpsNum]/[sourceFpsDen].
 */
data class AudioClipSpec(
    val clipKey: Long,
    val assetKey: Long,
    val startFrame: Long,
    val durationFrames: Long,
    val sourceInFrame: Long,
    val sourceFpsNum: Int,
    val sourceFpsDen: Int,
    val gainDb: Float = 0f,
    /** Equal-power fade-in over the first frames of the clip (a transition's incoming side); 0 = none. */
    val fadeInFrames: Long = 0,
    /** Equal-power fade-out over the last frames of the clip (a transition's outgoing side); 0 = none. */
    val fadeOutFrames: Long = 0,
    /**
     * Empty for a clip that plays its source at 1x from [sourceInFrame]. A retimed clip (speed change,
     * ramp, reverse) lists at least two knots instead: the first at frame 0, the last at
     * [durationFrames], strictly increasing in between; [sourceInFrame] is then unused. The native
     * mixer interpolates linearly between knots, so the pitch follows the speed.
     */
    val retimeKnots: List<RetimeKnot> = emptyList(),
    /** Index of this clip's track in [AudioSnapshot.tracks]. */
    val trackIndex: Int = 0,
    /** Balance, -1 (left) to 1 (right); 0 leaves the signal untouched. */
    val pan: Float = 0f,
    /** The clip's own fade handles, equal power, in project frames (0 = none). */
    val userFadeInFrames: Long = 0,
    val userFadeOutFrames: Long = 0,
    val eq: EqSpec = EqSpec.FLAT,
    /** Noise suppression strength 0..1 (0 = off); [noiseProfile] must then hold [NOISE_PROFILE_BINS] magnitudes. */
    val denoiseStrength: Float = 0f,
    val noiseProfile: List<Float> = emptyList(),
    /**
     * Keyframed gain (dB, the sum [gainDb] holds), pan and EQ band gains; each parameter at most once.
     * Frames count from [startFrame]. Empty when nothing is animated.
     */
    val automation: List<AutomationLane> = emptyList(),
) {
    init {
        require(automation.size <= AutomationLane.MAX_LANES && automation.map { it.param }.toSet().size == automation.size) {
            "clip $clipKey has invalid automation lanes"
        }
        require(automation.all { it.points.last().frame <= durationFrames }) { "clip $clipKey has an automation point past its end" }
        require(trackIndex >= 0) { "clip $clipKey has a negative track index" }
        require(pan.isFinite() && pan in -1f..1f) { "clip $clipKey pan $pan is out of range" }
        require(userFadeInFrames in 0..durationFrames) { "clip $clipKey has an invalid fade-in handle $userFadeInFrames" }
        require(userFadeOutFrames in 0..durationFrames) { "clip $clipKey has an invalid fade-out handle $userFadeOutFrames" }
        require(denoiseStrength.isFinite() && denoiseStrength in 0f..1f) { "clip $clipKey noise suppression $denoiseStrength is out of range" }
        require((denoiseStrength > 0f) == noiseProfile.isNotEmpty()) { "clip $clipKey needs a noise profile exactly when noise suppression is on" }
        require(noiseProfile.isEmpty() || noiseProfile.size == NOISE_PROFILE_BINS) { "clip $clipKey noise profile has ${noiseProfile.size} bins" }
        require(noiseProfile.all { it.isFinite() && it >= 0f }) { "clip $clipKey noise profile has an invalid magnitude" }
        require(startFrame in 0..MAX_FRAME) { "clip $clipKey has an invalid start $startFrame" }
        require(durationFrames in 1..MAX_FRAME) { "clip $clipKey has an invalid duration $durationFrames" }
        require(sourceInFrame in 0..MAX_FRAME) { "clip $clipKey has an invalid source in-point $sourceInFrame" }
        require(sourceFpsNum > 0 && sourceFpsDen > 0) { "clip $clipKey has an invalid source fps" }
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB) { "clip $clipKey gain $gainDb dB is out of range" }
        require(fadeInFrames in 0..durationFrames) { "clip $clipKey has an invalid fade-in $fadeInFrames" }
        require(fadeOutFrames in 0..durationFrames) { "clip $clipKey has an invalid fade-out $fadeOutFrames" }
        if (retimeKnots.isNotEmpty()) {
            require(retimeKnots.size in 2..MAX_KNOTS) { "clip $clipKey has ${retimeKnots.size} retime knots" }
            require(retimeKnots.first().frame == 0L && retimeKnots.last().frame == durationFrames) {
                "clip $clipKey retime knots must run from frame 0 to $durationFrames"
            }
            require(retimeKnots.zipWithNext().all { (a, b) -> b.frame > a.frame }) { "clip $clipKey retime knots must increase" }
            require(retimeKnots.all { it.sourceFrame.isFinite() && kotlin.math.abs(it.sourceFrame) <= MAX_FRAME }) {
                "clip $clipKey has a retime knot outside the media"
            }
        }
    }

    companion object {
        const val MAX_FRAME: Long = 1L shl 40
        const val MIN_GAIN_DB = -96f
        const val MAX_GAIN_DB = 24f
        const val MAX_KNOTS = 4096
        /** STFT bins of a noise profile; see `kDenoiseBins` in spectral_denoise.h. */
        const val NOISE_PROFILE_BINS = 513
    }
}

/**
 * Immutable audio view of the timeline sent to the native mixer. Independent of the editing
 * model (see SPECS.md 5.2). Clips on different tracks may overlap; they are summed.
 */
data class AudioSnapshot(
    val fpsNum: Int,
    val fpsDen: Int,
    val clips: List<AudioClipSpec>,
    /** The tracks the clips refer to by [AudioClipSpec.trackIndex]; one default track when none is given. */
    val tracks: List<AudioTrackSpec> = listOf(AudioTrackSpec(trackKey = 0)),
    /** Ducking of the music tracks by the voice tracks; null or an amount of 0 is off. */
    val ducking: DuckingSpec? = null,
    /** Master limiter at -1 dBFS; on unless a test wants the raw sum. */
    val limiterOn: Boolean = true,
) {
    init {
        require(fpsNum > 0 && fpsDen > 0) { "fps must be positive: $fpsNum/$fpsDen" }
        require(clips.map { it.clipKey }.toSet().size == clips.size) { "clip keys must be unique" }
        require(tracks.isNotEmpty() && tracks.size <= MAX_TRACKS) { "an audio snapshot needs 1..$MAX_TRACKS tracks, got ${tracks.size}" }
        require(clips.all { it.trackIndex < tracks.size }) { "a clip refers to a track that does not exist" }
    }

    /** Encodes into a direct little-endian buffer (layout documented in audio_snapshot.h). */
    fun encode(): ByteBuffer {
        val knotCount = clips.sumOf { it.retimeKnots.size }
        val profileFloats = clips.sumOf { it.noiseProfile.size }
        val laneCount = clips.sumOf { it.automation.size }
        val pointCount = clips.sumOf { clip -> clip.automation.sumOf { it.points.size } }
        val size = HEADER_BYTES + tracks.size * TRACK_BYTES + DUCKING_BYTES + clips.size * CLIP_BYTES +
            knotCount * KNOT_BYTES + profileFloats * Float.SIZE_BYTES + laneCount * LANE_BYTES + pointCount * POINT_BYTES
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        buffer.putInt(fpsNum)
        buffer.putInt(fpsDen)
        buffer.putInt(clips.size)
        buffer.putInt(tracks.size)
        buffer.putInt(if (limiterOn) 0 else 1)
        for (track in tracks) {
            val comp = track.compressor
            buffer.putLong(track.trackKey)
            buffer.putFloat(track.gainDb)
            buffer.putInt((if (track.muted) 1 else 0) or (if (comp != null) 2 else 0))
            buffer.putInt(track.role.value)
            buffer.putFloat(comp?.thresholdDb ?: -18f)
            buffer.putFloat(comp?.ratio ?: 3f)
            buffer.putFloat(comp?.attackMs ?: 10f)
            buffer.putFloat(comp?.releaseMs ?: 120f)
            buffer.putFloat(comp?.makeupDb ?: 0f)
        }
        val duck = ducking ?: DuckingSpec(amountDb = 0f)
        buffer.putFloat(duck.amountDb)
        buffer.putFloat(duck.thresholdDb)
        buffer.putFloat(duck.attackMs)
        buffer.putFloat(duck.releaseMs)
        for (clip in clips) {
            buffer.putLong(clip.clipKey)
            buffer.putLong(clip.assetKey)
            buffer.putLong(clip.startFrame)
            buffer.putLong(clip.durationFrames)
            buffer.putLong(clip.sourceInFrame)
            buffer.putInt(clip.sourceFpsNum)
            buffer.putInt(clip.sourceFpsDen)
            buffer.putFloat(clip.gainDb)
            buffer.putInt(clip.fadeInFrames.toInt())
            buffer.putInt(clip.fadeOutFrames.toInt())
            buffer.putInt(clip.retimeKnots.size)
            // The audio block.
            buffer.putInt(clip.trackIndex)
            buffer.putFloat(clip.pan)
            buffer.putInt(clip.userFadeInFrames.toInt())
            buffer.putInt(clip.userFadeOutFrames.toInt())
            buffer.putFloat(clip.eq.highPassHz)
            buffer.putFloat(clip.eq.lowPassHz)
            for (band in clip.eq.bands) {
                buffer.putFloat(band.freqHz)
                buffer.putFloat(band.gainDb)
                buffer.putFloat(band.q)
            }
            buffer.putFloat(clip.denoiseStrength)
            buffer.putInt(clip.noiseProfile.size)
            buffer.putInt(clip.automation.size) // lane count (reserved, always 0, before version 5)
        }
        // The knots of all clips follow, in clip order, then the noise profiles, then the automation lanes.
        for (clip in clips) {
            for (knot in clip.retimeKnots) {
                buffer.putLong(knot.frame)
                buffer.putDouble(knot.sourceFrame)
            }
        }
        for (clip in clips) {
            for (magnitude in clip.noiseProfile) buffer.putFloat(magnitude)
        }
        for (clip in clips) {
            for (lane in clip.automation) {
                buffer.putInt(lane.param.code)
                buffer.putInt(lane.points.size)
                buffer.putInt(0)
                buffer.putInt(0)
                for (point in lane.points) {
                    buffer.putLong(point.frame)
                    buffer.putFloat(point.value)
                    buffer.putInt(0)
                }
            }
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val MAGIC = 0x53415655 // "UVAS"
        const val VERSION = 5
        const val LANE_BYTES = 16
        const val POINT_BYTES = 16
        const val HEADER_BYTES = 28
        const val TRACK_BYTES = 40
        const val DUCKING_BYTES = 16
        const val CLIP_BYTES = 160
        const val KNOT_BYTES = 16
        const val MAX_TRACKS = 256
    }
}
