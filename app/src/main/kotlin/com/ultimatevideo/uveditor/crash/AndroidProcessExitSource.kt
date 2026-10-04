package com.ultimatevideo.uveditor.crash

import android.app.ActivityManager
import android.content.Context

/** Reads this app's earlier process exits from the system (API 30+; the app's minSdk is 33). Local only. */
class AndroidProcessExitSource(private val context: Context, private val limit: Int = 5) : ProcessExitSource {
    override fun recent(): List<ProcessExit> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, limit).map { info ->
            ProcessExit(
                reason = info.reason,
                timestampMillis = info.timestamp,
                importance = info.importance,
                pid = info.pid,
                description = info.description,
                trace = readTrace(info),
            )
        }
    }

    private fun readTrace(info: android.app.ApplicationExitInfo): ByteArray? {
        if (!ExitReason.isUnhandledByJava(info.reason)) return null
        return runCatching {
            info.traceInputStream?.use { stream ->
                val buffer = ByteArray(MAX_TRACE_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_TRACE_BYTES = 64 * 1024
    }
}
