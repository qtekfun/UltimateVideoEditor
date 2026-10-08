package com.qtekfun.ultimatevideoeditor.data.interchange

import java.io.IOException
import java.io.OutputStream

/** Thrown by [ProjectBundle.write] when its observer asked to stop; the caller removes the partial file. */
class BundleWriteCancelled : IOException("The backup was cancelled")

/**
 * Thrown by an import when its observer asked to stop. A [CancellationException] so that nothing treats it as a failure; the
 * importer removes what it had made (scratch folder, copied footage) on the way out, so a cancelled import leaves nothing.
 */
class ImportCancelled : java.util.concurrent.CancellationException("The import was cancelled")

/** Names an import reports for the last step (project item, written and moved into place), so the words can say "finishing". */
object ImportSteps {
    const val FINISHING = "finishing"
}

/** What an import will move, known from the zip's directory before the first byte: [totalBytes] of payload in [mediaCount] media files. */
data class BundlePlan(val totalBytes: Long, val mediaCount: Int)

/** What kind of entry the writer is on. */
enum class BundleItemKind { PROJECT, THUMBNAIL, RESOURCE, MEDIA }

/**
 * Watches [ProjectBundle.write] (a backup) or an import (`ProjectRepository.import`, which reports the same way while it unpacks): it is told the total up front, each entry that starts and every chunk of bytes, and it can ask
 * the writer to stop. Called on the writing thread, so implementations must be cheap (the throttling lives in the implementation).
 */
interface BundleWriteObserver {
    /** Before anything is written: the payload bytes that will go in (project data, pictures, LUTs, fonts, media) and the media count. */
    fun onStart(totalBytes: Long, mediaCount: Int) {}

    /** An entry starts. [mediaIndex] counts media files from 1 and is 0 for other kinds. */
    fun onItem(kind: BundleItemKind, name: String, mediaIndex: Int) {}

    /** [count] more payload bytes were handed to the zip. */
    fun onBytes(count: Long) {}

    /** True once the user pressed Cancel; the writer then throws [BundleWriteCancelled] at the next chunk. */
    fun isCancelled(): Boolean = false

    companion object {
        val NONE: BundleWriteObserver = object : BundleWriteObserver {}
    }
}

/** An entry as written: its name and its uncompressed size, which the check of the finished file compares with the zip's directory. */
data class WrittenEntry(val name: String, val size: Long)

/** Counts the bytes that reach the output, i.e. the size of the finished file. */
internal class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
