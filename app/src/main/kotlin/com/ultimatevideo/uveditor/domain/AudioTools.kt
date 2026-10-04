package com.ultimatevideo.uveditor.domain

/**
 * The audio tools of a clip and of a track: pan, fade handles, EQ, noise suppression and loudness
 * normalisation per clip; volume, mute, solo, role and a bus compressor per track; sidechain
 * ducking for the project. Everything is plain immutable data (the mixer renders it, see
 * `engine/audio` and `audio/` natively); every value has a documented range and `problem()` says
 * why a value is not allowed, so the operations can refuse it with a typed error.
 */

/** One EQ band; the five bands are a low shelf, three peaking bands and a high shelf. */
data class EqBand(val freqHz: Double, val gainDb: Double = 0.0, val q: Double = 1.0) {
    val isOn: Boolean get() = gainDb != 0.0

    fun problem(): String? = when {
        !freqHz.isFinite() || freqHz !in MIN_FREQ_HZ..MAX_FREQ_HZ -> "EQ frequency must be between ${MIN_FREQ_HZ.toInt()} and ${MAX_FREQ_HZ.toInt()} Hz"
        !gainDb.isFinite() || gainDb !in MIN_GAIN_DB..MAX_GAIN_DB -> "EQ gain must be between $MIN_GAIN_DB and $MAX_GAIN_DB dB"
        !q.isFinite() || q !in MIN_Q..MAX_Q -> "EQ Q must be between $MIN_Q and $MAX_Q"
        else -> null
    }

    companion object {
        const val MIN_FREQ_HZ = 20.0
        const val MAX_FREQ_HZ = 20000.0
        const val MIN_GAIN_DB = -18.0
        const val MAX_GAIN_DB = 18.0
        const val MIN_Q = 0.1
        const val MAX_Q = 18.0
    }
}

/** A clip's EQ: optional high-pass and low-pass filters (0 = off) and five bands. */
data class ClipEq(
    val highPassHz: Double = 0.0,
    val lowPassHz: Double = 0.0,
    val bands: List<EqBand> = DEFAULT_BANDS,
) {
    val isFlat: Boolean get() = highPassHz == 0.0 && lowPassHz == 0.0 && bands.none { it.isOn }

    fun problem(): String? {
        if (bands.size != BAND_COUNT) return "an EQ has $BAND_COUNT bands"
        for (hz in listOf(highPassHz, lowPassHz)) {
            if (hz != 0.0 && (!hz.isFinite() || hz !in EqBand.MIN_FREQ_HZ..EqBand.MAX_FREQ_HZ)) return "filter frequency must be 0 (off) or between 20 and 20000 Hz"
        }
        return bands.firstNotNullOfOrNull { it.problem() }
    }

    fun withBand(index: Int, band: EqBand): ClipEq = copy(bands = bands.mapIndexed { i, b -> if (i == index) band else b })

    companion object {
        const val BAND_COUNT = 5
        val DEFAULT_BANDS = listOf(
            EqBand(100.0, 0.0, 0.7071),
            EqBand(400.0),
            EqBand(1500.0),
            EqBand(5000.0),
            EqBand(10000.0, 0.0, 0.7071),
        )
        val FLAT = ClipEq()
    }
}

/**
 * Noise suppression (spectral subtraction, no learned model): [strength] 0..1 and the noise
 * [profile] taken from a quiet region of the recording, [BINS] magnitudes of a 1024-point STFT.
 */
data class Denoise(val strength: Double, val profile: List<Float>) {
    fun problem(): String? = when {
        !strength.isFinite() || strength <= 0.0 || strength > 1.0 -> "noise suppression strength must be above 0 and at most 1"
        profile.size != BINS -> "the noise profile must have $BINS values"
        profile.any { !it.isFinite() || it < 0f } -> "the noise profile has an invalid value"
        else -> null
    }

    companion object {
        const val BINS = 513
    }
}

/** The audio settings of one clip, on top of its [Clip.gainDb] volume. */
data class ClipAudio(
    /** Balance: -1 hard left, 0 untouched, 1 hard right. */
    val pan: Double = 0.0,
    /** The clip's own fade handles, in clip frames (equal-power ramps); 0 = none. */
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0,
    val eq: ClipEq = ClipEq.FLAT,
    val denoise: Denoise? = null,
    /** Gain from "normalise loudness", measured once and stored; added to [Clip.gainDb] when mixing. */
    val normalizeDb: Double = 0.0,
    /** The loudness target (LUFS) [normalizeDb] was computed for, for display; null when not normalised. */
    val targetLufs: Double? = null,
) {
    val isNeutral: Boolean get() = this == NONE

    fun problem(durationFrames: Long): String? = when {
        !pan.isFinite() || pan !in -1.0..1.0 -> "pan must be between -1 and 1"
        fadeInFrames < 0 || fadeInFrames > durationFrames -> "the fade-in must fit inside the clip"
        fadeOutFrames < 0 || fadeOutFrames > durationFrames -> "the fade-out must fit inside the clip"
        !normalizeDb.isFinite() || normalizeDb !in -MAX_NORMALIZE_DB..MAX_NORMALIZE_DB -> "normalise gain must be within $MAX_NORMALIZE_DB dB"
        targetLufs != null && (!targetLufs.isFinite() || targetLufs !in -60.0..0.0) -> "the loudness target must be between -60 and 0 LUFS"
        else -> eq.problem() ?: denoise?.problem()
    }

    /**
     * The settings of the part [from, to) of a clip (see [Clip.cropped]). A fade handle belongs to its
     * edge: trimming moves the edge and the handle goes with it, never longer than the new length.
     * Cuts that leave a clip in two (split, overwrite) clear the handle of the side that is no longer
     * the original edge with [withoutFadeIn] / [withoutFadeOut].
     */
    fun cropped(from: Long, to: Long): ClipAudio {
        if (this == NONE) return this
        val length = to - from
        return copy(fadeInFrames = fadeInFrames.coerceIn(0, length), fadeOutFrames = fadeOutFrames.coerceIn(0, length))
    }

    companion object {
        val NONE = ClipAudio()
        const val MAX_NORMALIZE_DB = 36.0
    }
}

enum class AudioRole { NORMAL, VOICE, MUSIC }

/** A feed-forward bus compressor on a track. */
data class BusCompressor(
    val thresholdDb: Double = -18.0,
    val ratio: Double = 3.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 120.0,
    val makeupDb: Double = 0.0,
) {
    fun problem(): String? = when {
        !thresholdDb.isFinite() || thresholdDb !in -80.0..0.0 -> "compressor threshold must be between -80 and 0 dB"
        !ratio.isFinite() || ratio !in 1.0..20.0 -> "compressor ratio must be between 1 and 20"
        !attackMs.isFinite() || attackMs !in 0.1..500.0 -> "compressor attack must be between 0.1 and 500 ms"
        !releaseMs.isFinite() || releaseMs !in 1.0..5000.0 -> "compressor release must be between 1 and 5000 ms"
        !makeupDb.isFinite() || makeupDb !in 0.0..24.0 -> "compressor make-up gain must be between 0 and 24 dB"
        else -> null
    }
}

/** The mixer settings of one track. Solo mutes every track that is not soloed. */
data class TrackAudio(
    val volumeDb: Double = 0.0,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val role: AudioRole = AudioRole.NORMAL,
    val compressor: BusCompressor? = null,
) {
    val isNeutral: Boolean get() = this == NONE

    fun problem(): String? = when {
        !volumeDb.isFinite() || volumeDb !in MIN_VOLUME_DB..MAX_VOLUME_DB -> "track volume must be between $MIN_VOLUME_DB and $MAX_VOLUME_DB dB"
        else -> compressor?.problem()
    }

    companion object {
        val NONE = TrackAudio()
        const val MIN_VOLUME_DB = -96.0
        const val MAX_VOLUME_DB = 12.0
    }
}

/**
 * Sidechain ducking: while any VOICE track is audible the MUSIC tracks drop by [amountDb]. It is
 * gain automation derived from the voice itself and computed by the mixer, so it is never baked in
 * and follows every edit.
 */
data class Ducking(
    val amountDb: Double = 10.0,
    val thresholdDb: Double = -35.0,
    val attackMs: Double = 20.0,
    val releaseMs: Double = 400.0,
) {
    fun problem(): String? = when {
        !amountDb.isFinite() || amountDb !in 0.0..48.0 -> "ducking amount must be between 0 and 48 dB"
        !thresholdDb.isFinite() || thresholdDb !in -80.0..0.0 -> "ducking threshold must be between -80 and 0 dB"
        !attackMs.isFinite() || attackMs !in 1.0..5000.0 -> "ducking attack must be between 1 and 5000 ms"
        !releaseMs.isFinite() || releaseMs !in 1.0..5000.0 -> "ducking release must be between 1 and 5000 ms"
        else -> null
    }
}

/** The clip without its fade-out handle (the half of a cut that no longer holds the original end). */
fun Clip.withoutFadeOut(): Clip = if (audio.fadeOutFrames == 0L) this else copy(audio = audio.copy(fadeOutFrames = 0))

/** The clip without its fade-in handle (the half of a cut that no longer holds the original start). */
fun Clip.withoutFadeIn(): Clip = if (audio.fadeInFrames == 0L) this else copy(audio = audio.copy(fadeInFrames = 0))

/** True when some track is soloed, which silences every track that is not. */
val Timeline.anySolo: Boolean get() = tracks.any { it.audio.solo }

/** A track's effective mute: muted itself, or another track is soloed and this one is not. */
fun Timeline.isTrackAudible(track: Track): Boolean = !track.audio.mute && (!anySolo || track.audio.solo)
