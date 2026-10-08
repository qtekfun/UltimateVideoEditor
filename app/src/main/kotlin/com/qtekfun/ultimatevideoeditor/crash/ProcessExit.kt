package com.qtekfun.ultimatevideoeditor.crash

import java.io.File
import java.time.Instant

/**
 * One earlier exit of this app's process as the system recorded it (`ApplicationExitInfo`). [trace] is the
 * start of the tombstone or ANR trace the system keeps for crashes it could not hand to our Java handler.
 */
class ProcessExit(
    val reason: Int,
    val timestampMillis: Long,
    val importance: Int,
    val pid: Int,
    val description: String?,
    val trace: ByteArray?,
)

/** Where the exits come from; the Android implementation reads `ActivityManager`, tests use a fake. */
fun interface ProcessExitSource {
    fun recent(): List<ProcessExit>
}

/** The `ApplicationExitInfo.REASON_*` values we care about, spelled out so the logic runs on the JVM. */
object ExitReason {
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7

    /** Native crashes, ANRs and kills by signal never reach the Java uncaught-exception handler. */
    fun isUnhandledByJava(reason: Int): Boolean = reason == SIGNALED || reason == CRASH_NATIVE || reason == ANR || reason == INITIALIZATION_FAILURE

    fun name(reason: Int): String = when (reason) {
        SIGNALED -> "signalled (killed by a signal)"
        LOW_MEMORY -> "low memory"
        CRASH -> "java crash"
        CRASH_NATIVE -> "native crash"
        ANR -> "not responding (ANR)"
        INITIALIZATION_FAILURE -> "initialisation failure"
        else -> "reason $reason"
    }
}

/**
 * Pulls the readable parts out of a tombstone. On current Android versions the trace stream of a native crash is
 * a protocol buffer: the function and library names sit in it as plain text, so a scan for printable runs recovers
 * the frames without a parser. Only strings that look like a signal line, a library or a C++ symbol are kept, and
 * every one is scrubbed like any other report text (no paths, no media names).
 */
object TombstoneSummary {
    private const val MIN_RUN = 6
    private const val MAX_ENTRIES = 40
    private const val MAX_BYTES = 8 * 1024
    private val interesting = Regex("""(::|\.so\b|SIG[A-Z]+|signal \d+|null pointer|Abort message|Fatal)""")

    fun extract(trace: ByteArray?): List<String> {
        if (trace == null || trace.isEmpty()) return emptyList()
        val bytes = if (trace.size > MAX_BYTES * 8) trace.copyOf(MAX_BYTES * 8) else trace
        // First read the stream as protobuf: a text field is a tag byte, a varint length and that many printable
        // bytes, so the tag and the length are never mistaken for the first characters of a symbol. Anything that
        // is not protobuf (older Android versions, plain text) falls back to scanning for printable runs.
        val raw = printableRuns(bytes)
        val proto = protobufStrings(bytes)
        val rawInteresting = raw.filter { interesting.containsMatchIn(it) }
        val explained = rawInteresting.count { run -> proto.any { run.contains(it) } }
        // The protobuf reading is used when it accounts for most of the readable symbols, otherwise it is not protobuf.
        val runs = if (proto.isNotEmpty() && rawInteresting.isNotEmpty() && explained * 10 >= rawInteresting.size * 7) proto else raw
        val seen = LinkedHashSet<String>()
        var size = 0
        for (run in runs) {
            if (!interesting.containsMatchIn(run)) continue
            val line = CrashReportFormat.scrub(run).trim()
            if (line.isEmpty() || !seen.add(line)) continue
            size += line.length + 1
            if (seen.size > MAX_ENTRIES || size > MAX_BYTES) break
        }
        return seen.toList()
    }

    /** Text fields of a protobuf stream: wire type 2, a varint length of at least [MIN_RUN], all bytes printable. */
    internal fun protobufStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < bytes.size - 1) {
            val tag = bytes[i].toInt() and 0xFF
            if (tag and 7 == 2) {
                var length = 0
                var shift = 0
                var j = i + 1
                var complete = false
                while (j < bytes.size && j <= i + 3) {
                    val b = bytes[j].toInt() and 0xFF
                    length = length or ((b and 0x7F) shl shift)
                    j++
                    if (b and 0x80 == 0) { complete = true; break }
                    shift += 7
                }
                if (complete && length >= MIN_RUN && j + length <= bytes.size && printable(bytes, j, length)) {
                    out.add(String(bytes, j, length, Charsets.ISO_8859_1))
                    i = j + length
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun printable(bytes: ByteArray, from: Int, length: Int): Boolean {
        for (k in from until from + length) {
            val c = bytes[k].toInt() and 0xFF
            if (c !in 0x20..0x7E) return false
        }
        return true
    }

    private fun printableRuns(bytes: ByteArray): List<String> {
        val runs = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_RUN) runs.add(current.toString())
            current.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) current.append(c.toChar()) else flush()
        }
        flush()
        return runs
    }
}

/**
 * The Java crash handler cannot see a segmentation fault or an ANR, so after one the user would find nothing in
 * "Last crash report". On start this reads what the system recorded about the previous process exit and, for a
 * native crash, an ANR or a kill by signal that was not reported yet, adds a short local summary to the report.
 * Nothing leaves the device and no project or media information is included.
 */
class ProcessExitRecorder(
    private val source: ProcessExitSource,
    private val store: CrashReportStore,
    private val handled: File,
    private val context: CrashContext,
) {
    /** Returns true when a new summary was written. */
    fun recordNewExits(): Boolean {
        val lastHandled = runCatching { handled.takeIf { it.isFile }?.readText()?.trim()?.toLong() }.getOrNull() ?: 0L
        val exits = runCatching { source.recent() }.getOrDefault(emptyList())
        val newest = exits.maxOfOrNull { it.timestampMillis } ?: return false
        val pending = exits
            .filter { it.timestampMillis > lastHandled && ExitReason.isUnhandledByJava(it.reason) }
            .sortedByDescending { it.timestampMillis }
        // Remember how far we looked even when nothing needed reporting, so the same exits are never reread.
        runCatching {
            handled.parentFile?.mkdirs()
            handled.writeText(newest.toString())
        }
        val latest = pending.firstOrNull() ?: return false

        val summary = summarise(latest)
        val previous = store.read()
        store.write(if (previous.isNullOrBlank()) summary else "$summary\n${"-".repeat(40)}\nEarlier report:\n$previous")
        return true
    }

    internal fun summarise(exit: ProcessExit): String = buildString {
        appendLine("ultimateVE native exit report")
        appendLine("time: ${Instant.ofEpochMilli(exit.timestampMillis)}")
        appendLine("version (now installed): ${context.appVersion}")
        appendLine("device: ${context.deviceModel}")
        appendLine("android: ${context.androidRelease} (API ${context.sdkInt})")
        appendLine("exit: ${ExitReason.name(exit.reason)}")
        appendLine("importance: ${exit.importance}")
        exit.description?.let { appendLine("system note: ${CrashReportFormat.scrub(it)}") }
        val frames = TombstoneSummary.extract(exit.trace)
        if (frames.isNotEmpty()) {
            appendLine()
            appendLine("trace (symbols found in the system's trace):")
            frames.forEach { appendLine("\t$it") }
        }
        appendLine()
        appendLine("No project, media or personal data is included in this report.")
    }
}
