package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.engine.audio.LoudnessResult
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Offline measurements of a media file for the audio tools: integrated loudness (for "normalise")
 * and the noise profile of a quiet region (for noise suppression). Everything runs on the device
 * with classical signal processing; nothing leaves it. Implemented by [EditorAudio] on top of the
 * native engine, and by fakes in the tests.
 */
interface AudioAnalyzer {
    /**
     * Integrated loudness of [startMicros, endMicros) of [asset]'s sound.
     * @throws AudioAnalysisException with a message fit for the user when it cannot be measured.
     */
    suspend fun loudness(asset: MediaAssetDto, assetKey: Long, startMicros: Long, endMicros: Long): LoudnessResult

    /**
     * The 513-bin noise profile of [startMicros, endMicros) of [asset]'s sound.
     * @throws AudioAnalysisException when the region is too short or the media cannot be read.
     */
    suspend fun noiseProfile(asset: MediaAssetDto, assetKey: Long, startMicros: Long, endMicros: Long): FloatArray

    /** Stops a measurement in progress (it then fails with a [AudioAnalysisException]). */
    fun cancel()
}

/** A measurement that could not be done; [message] is meant for the user. */
class AudioAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Loudness measurements kept per file and range, so normalising the same clip twice does not decode it again. */
interface LoudnessCache {
    fun get(key: String): Double?

    fun put(key: String, lufs: Double)

    companion object {
        /** A cache that remembers nothing. */
        val None: LoudnessCache = object : LoudnessCache {
            override fun get(key: String): Double? = null

            override fun put(key: String, lufs: Double) = Unit
        }

        /** Key of a measurement: the file, the source range and the file's length (a replaced file changes it). */
        fun keyOf(asset: MediaAssetDto, startFrame: Long, endFrame: Long): String =
            "${asset.id}:${asset.durationFrames}:$startFrame-$endFrame"
    }
}

/**
 * The loudness cache the app uses: one file under [filesDir] shared by every project (keys name the file and range, not
 * the project), so a measurement survives a restart and the same clip in another project is not measured again.
 */
fun loudnessCacheIn(filesDir: File): LoudnessCache = FileLoudnessCache(File(filesDir, LOUDNESS_CACHE_PATH))

internal const val LOUDNESS_CACHE_PATH = "loudness/cache.json"

/**
 * [LoudnessCache] on disk: one small JSON file of `key -> LUFS`, read once and rewritten on each new
 * measurement. A damaged or unreadable file is treated as empty (it only holds recomputable values).
 */
class FileLoudnessCache(private val file: File) : LoudnessCache {
    private val serializer = MapSerializer(String.serializer(), Double.serializer())
    private val values: MutableMap<String, Double> = load()

    @Synchronized
    override fun get(key: String): Double? = values[key]

    @Synchronized
    override fun put(key: String, lufs: Double) {
        values[key] = lufs
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(Json.encodeToString(serializer, values))
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (_: IOException) {
            // Losing a cache entry only costs a re-measurement later.
        }
    }

    private fun load(): MutableMap<String, Double> = try {
        if (file.isFile) Json.decodeFromString(serializer, file.readText()).toMutableMap() else mutableMapOf()
    } catch (_: IOException) {
        mutableMapOf()
    } catch (_: kotlinx.serialization.SerializationException) {
        mutableMapOf()
    }
}
