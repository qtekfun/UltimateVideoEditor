package com.ultimatevideo.uveditor.engine.captions

import com.ultimatevideo.uveditor.domain.captions.Transcript

/** Native result codes. Values 1-8 mirror `uv::core::Status`. */
enum class CaptionErrorCode(val value: Int) {
    InvalidArgument(1),
    IoError(3),
    UnsupportedFormat(4),
    CodecError(5),
    Cancelled(8),

    /** Raised on the Kotlin side: the speech model has not been downloaded. */
    ModelMissing(100),

    /** Raised on the Kotlin side: a downloaded model did not match its published checksum or size. */
    ModelCorrupt(101),

    /** Raised on the Kotlin side: the download failed (no network, server error). */
    Download(102),

    /** Raised on the Kotlin side: not enough free space to store the model. */
    NoSpace(103),
    Unknown(-1),
    ;

    companion object {
        fun fromValue(value: Int): CaptionErrorCode = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

/** A failure while preparing the model or transcribing. Never swallowed: it reaches the UI as a message. */
class CaptionException(val errorCode: CaptionErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A ggml speech model fetched on demand (the app does not bundle one). [sha256] is the published
 * checksum of the file at [url]; a download that does not match is discarded.
 */
data class CaptionModel(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val description: String,
)

/** The models offered. Both are multilingual and 5-bit quantised (`q5_1`), which keeps them small on a phone. */
object CaptionModels {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val TINY = CaptionModel(
        id = "tiny",
        label = "Fast",
        fileName = "ggml-tiny-q5_1.bin",
        url = "$BASE_URL/ggml-tiny-q5_1.bin",
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        sizeBytes = 32_152_673L,
        description = "Whisper tiny: quickest, fine for clear speech",
    )

    val BASE = CaptionModel(
        id = "base",
        label = "Balanced",
        fileName = "ggml-base-q5_1.bin",
        url = "$BASE_URL/ggml-base-q5_1.bin",
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        sizeBytes = 59_707_625L,
        description = "Whisper base: better with accents and background noise",
    )

    val ALL: List<CaptionModel> = listOf(BASE, TINY)
    val DEFAULT: CaptionModel = BASE

    fun byId(id: String): CaptionModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** Spoken language to recognise; [code] is the ISO code whisper takes, or `auto` to detect it. */
data class CaptionLanguage(val code: String, val label: String) {
    companion object {
        const val AUTO = "auto"

        val ALL: List<CaptionLanguage> = listOf(
            CaptionLanguage(AUTO, "Detect automatically"),
            CaptionLanguage("en", "English"),
            CaptionLanguage("es", "Spanish"),
            CaptionLanguage("fr", "French"),
            CaptionLanguage("de", "German"),
            CaptionLanguage("it", "Italian"),
            CaptionLanguage("pt", "Portuguese"),
            CaptionLanguage("nl", "Dutch"),
            CaptionLanguage("ca", "Catalan"),
            CaptionLanguage("pl", "Polish"),
            CaptionLanguage("ru", "Russian"),
            CaptionLanguage("uk", "Ukrainian"),
            CaptionLanguage("tr", "Turkish"),
            CaptionLanguage("ar", "Arabic"),
            CaptionLanguage("hi", "Hindi"),
            CaptionLanguage("ja", "Japanese"),
            CaptionLanguage("ko", "Korean"),
            CaptionLanguage("zh", "Chinese"),
        )
    }
}

/** What to transcribe: [startMs, endMs) of the audio behind [assetUri], on the source's own timeline. */
data class TranscribeRequest(
    val assetUri: String,
    val startMs: Long,
    val endMs: Long,
    val model: CaptionModel,
    val language: String = CaptionLanguage.AUTO,
)

/** Turns speech into words. The Android implementation is [WhisperTranscriber]; tests use fakes. */
interface Transcriber {
    /**
     * @param onProgress 0..100, called from a background thread.
     * @throws CaptionException on a missing model, unreadable media or a decode failure.
     * Cancelling the calling coroutine stops the work promptly.
     */
    suspend fun transcribe(request: TranscribeRequest, onProgress: (Int) -> Unit): Transcript
}
