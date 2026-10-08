package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.engine.timeline.EngineStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the native thumbnail failures into one concise message per media file. The native side reports by asset key only, so
 * the editor registers each file it hands over; a failure is logged with the file's name and uri and the underlying detail,
 * and shown to the user once per file for as long as the process lives (opening the editor again does not repeat it).
 * Thread-safe: failures arrive on the native worker thread.
 */
internal class ThumbnailFailures(
    private val projectId: String,
    private val log: (String) -> Unit,
    private val show: (String) -> Unit,
    private val alreadyShown: MutableSet<String> = shownInThisProcess,
) {
    private class Source(val name: String, val uri: String)

    private val sources = ConcurrentHashMap<Long, Source>()

    fun register(assetKey: Long, name: String, uri: String) {
        sources[assetKey] = Source(name, uri)
    }

    fun onFailure(assetKey: Long, status: EngineStatus, detail: String) {
        val source = sources[assetKey]
        log("thumbnails failed: asset=$assetKey name=${source?.name ?: "?"} uri=${source?.uri ?: "?"} status=$status detail=$detail")
        // An unknown key cannot be told apart from another one, so it is keyed by the key itself.
        val identity = "$projectId|${source?.uri ?: "key-$assetKey"}"
        if (!alreadyShown.add(identity)) return
        show(message(source?.name, status))
    }

    companion object {
        private val shownInThisProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun message(name: String?, status: EngineStatus): String =
            if (name == null) "Could not generate a filmstrip ($status)" else "No filmstrip for $name ($status)"
    }
}
