package com.ultimatevideo.uveditor.engine.captions

import android.content.Context
import android.net.Uri
import com.ultimatevideo.uveditor.domain.captions.Transcript
import com.ultimatevideo.uveditor.domain.captions.TranscriptWord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** JNI bindings only. Use [WhisperTranscriber]. */
internal object NativeCaptions {
    init {
        System.loadLibrary("uveditor_engine")
    }

    /** Returns a `uv::core::Status` value; results arrive through [callbacks] on the calling thread. */
    external fun nativeTranscribe(
        fd: Int,
        startMs: Long,
        endMs: Long,
        modelPath: String,
        language: String,
        threads: Int,
        callbacks: NativeCaptionCallbacks,
    ): Int
}

/** Called from native code; the names and signatures are looked up by JNI. */
internal interface NativeCaptionCallbacks {
    /** @return false to cancel. */
    fun onProgress(percent: Int): Boolean
    fun onLanguage(code: String)
    fun onWord(text: String, startMs: Long, endMs: Long)
}

/** [Transcriber] backed by whisper.cpp inside `uveditor_engine`. */
class WhisperTranscriber(
    private val context: Context,
    private val models: CaptionModelStore,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_THREADS),
) : Transcriber {

    override suspend fun transcribe(request: TranscribeRequest, onProgress: (Int) -> Unit): Transcript {
        val modelFile = models.fileOf(request.model)
            ?: throw CaptionException(CaptionErrorCode.ModelMissing, "The ${request.model.label} model is not downloaded")
        val descriptor = try {
            context.contentResolver.openFileDescriptor(Uri.parse(request.assetUri), "r")
                ?: throw FileNotFoundException(request.assetUri)
        } catch (e: FileNotFoundException) {
            throw CaptionException(CaptionErrorCode.IoError, "Could not open the clip's media: ${e.message}", e)
        } catch (e: SecurityException) {
            throw CaptionException(CaptionErrorCode.IoError, "No permission to read the clip's media", e)
        }

        val job: Job = currentCoroutineContext()[Job] ?: Job()
        return withContext(worker) {
            descriptor.use { pfd ->
                val callbacks = Collector(job, onProgress)
                val status = try {
                    NativeCaptions.nativeTranscribe(
                        pfd.fd, request.startMs, request.endMs, modelFile.absolutePath, request.language, threads, callbacks,
                    )
                } catch (e: UnsatisfiedLinkError) {
                    throw CaptionException(CaptionErrorCode.Unknown, "The caption engine is not available", e)
                }
                ensureActive() // a cancelled caller must not see a result
                if (status != 0) throw failure(status, request)
                Transcript.fromRaw(callbacks.language, callbacks.pieces)
            }
        }
    }

    private fun failure(status: Int, request: TranscribeRequest): CaptionException {
        val code = CaptionErrorCode.fromValue(status)
        val message = when (code) {
            CaptionErrorCode.InvalidArgument -> "There is not enough audio to transcribe (needs at least a second, at most ${MAX_MINUTES} minutes)"
            CaptionErrorCode.IoError -> "Could not read the audio or load the ${request.model.label} model"
            CaptionErrorCode.UnsupportedFormat -> "This clip's audio format is not supported"
            CaptionErrorCode.CodecError -> "The clip's audio could not be decoded or recognised"
            CaptionErrorCode.Cancelled -> "Cancelled"
            else -> "Captions failed (code $status)"
        }
        return CaptionException(code, message)
    }

    private class Collector(private val job: Job, private val report: (Int) -> Unit) : NativeCaptionCallbacks {
        var language: String = ""
        val pieces = ArrayList<TranscriptWord>()

        override fun onProgress(percent: Int): Boolean {
            report(percent)
            return job.isActive
        }

        override fun onLanguage(code: String) {
            language = code
        }

        override fun onWord(text: String, startMs: Long, endMs: Long) {
            pieces += TranscriptWord(text, startMs, endMs)
        }
    }

    private companion object {
        const val MAX_THREADS = 4
        const val MAX_MINUTES = 30
    }
}
