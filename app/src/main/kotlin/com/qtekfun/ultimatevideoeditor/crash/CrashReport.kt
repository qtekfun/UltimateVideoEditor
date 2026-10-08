package com.qtekfun.ultimatevideoeditor.crash

import java.io.File
import java.time.Instant

/** What a crash report may say about the device: nothing that identifies the person or their media. */
data class CrashContext(
    val appVersion: String,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
)

/**
 * Builds the small text report written when the app crashes. It is stored on the device only and leaves it
 * only if the user chooses to share it. It contains the exception types, shortened and scrubbed messages and
 * the stack frames: no project names, no file names, no content URIs.
 */
object CrashReportFormat {
    private const val MAX_MESSAGE = 200
    private const val MAX_FRAMES = 60
    private const val MAX_CAUSES = 5

    private val contentUri = Regex("""content://\S+""")
    private val filePath = Regex("""(?:/(?:storage|sdcard|data|mnt|proc|sys)/[^\s,;:)\]]+)""")
    private val quotedFile = Regex("""[\w .\-()]+\.(?:mp4|mov|mkv|m4a|mp3|wav|aac|jpg|jpeg|png|webp|heic|json|uvbundle|srt|vtt|cube|ttf|otf)\b""", RegexOption.IGNORE_CASE)

    fun format(throwable: Throwable, threadName: String, context: CrashContext, timeMillis: Long): String = buildString {
        appendLine("ultimateVE crash report")
        appendLine("time: ${Instant.ofEpochMilli(timeMillis)}")
        appendLine("version: ${context.appVersion}")
        appendLine("device: ${context.deviceModel}")
        appendLine("android: ${context.androidRelease} (API ${context.sdkInt})")
        appendLine("thread: ${scrub(threadName)}")
        appendLine()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            appendLine(if (depth == 0) describe(current) else "Caused by: ${describe(current)}")
            current.stackTrace.take(MAX_FRAMES).forEach { appendLine("\tat $it") }
            val hidden = current.stackTrace.size - MAX_FRAMES
            if (hidden > 0) appendLine("\t... $hidden more frames")
            current = current.cause?.takeUnless { it === current }
            depth++
        }
        appendLine()
        appendLine("No project, media or personal data is included in this report.")
    }

    internal fun describe(t: Throwable): String {
        val message = t.message?.let(::scrub)?.takeIf { it.isNotBlank() }
        return if (message == null) t.javaClass.name else "${t.javaClass.name}: $message"
    }

    /** Shortens a message and removes anything that looks like a URI, a path or a media file name. */
    internal fun scrub(text: String): String = text
        .replace(contentUri, "content://<removed>")
        .replace(filePath, "<path>")
        .replace(quotedFile, "<file>")
        .replace('\n', ' ')
        .take(MAX_MESSAGE)
}

/**
 * The crash reports on disk: `last-crash.txt` is the latest, `last-crash.1.txt` the one before it, and so on up to
 * [MAX_FILES] files in all (the oldest is dropped). Written atomically so that a second crash cannot leave half a file.
 */
class CrashReportStore(private val dir: File) {
    private val file get() = fileAt(0)

    private fun fileAt(index: Int) = File(dir, if (index == 0) FILE_NAME else "last-crash.$index.txt")

    /** Stores [report] as the latest, moving the earlier ones one place back. */
    fun write(report: String) {
        dir.mkdirs()
        fileAt(MAX_FILES - 1).delete()
        for (index in MAX_FILES - 2 downTo 0) {
            val from = fileAt(index)
            if (from.isFile) check(from.renameTo(fileAt(index + 1))) { "could not rotate the crash reports" }
        }
        replaceLatest(report)
    }

    /** Overwrites the latest report without rotating (used to extend it with a native-exit summary). */
    fun replaceLatest(report: String) {
        dir.mkdirs()
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(report.take(MAX_BYTES))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not store the crash report" }
        }
    }

    fun read(): String? = file.takeIf { it.isFile }?.readText()

    /** Every stored report, newest first. */
    fun readAll(): List<String> = (0 until MAX_FILES).map(::fileAt).filter { it.isFile }.map { it.readText() }

    fun delete() {
        for (index in 0 until MAX_FILES) fileAt(index).delete()
    }

    companion object {
        const val MAX_FILES = 5
        private const val FILE_NAME = "last-crash.txt"
        private const val MAX_BYTES = 64 * 1024
    }
}

/** Installs an uncaught-exception handler that stores a report and then lets the system do what it always did. */
object CrashHandler {
    fun install(store: CrashReportStore, context: CrashContext, clock: () -> Long = System::currentTimeMillis) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failure while reporting must never hide the original crash.
            runCatching { store.write(CrashReportFormat.format(error, thread.name, context, clock())) }
            previous?.uncaughtException(thread, error)
        }
    }
}
