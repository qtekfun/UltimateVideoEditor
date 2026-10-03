package com.ultimatevideo.uveditor.data

import android.content.Context

/**
 * Remembers which project is open in the editor so that, if the app is killed or crashes, the hub can
 * offer to reopen it. The marker is cleared when the user leaves the editor normally.
 */
interface SessionStore {
    fun markOpen(projectId: String)

    fun markClosed()

    /** The project that was open when the app last stopped without leaving the editor, if any. */
    fun unfinishedProjectId(): String?
}

class PreferencesSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    // commit(), not apply(): the marker exists for the case where the process dies right after.
    override fun markOpen(projectId: String) {
        prefs.edit().putString(KEY_OPEN, projectId).commit()
    }

    override fun markClosed() {
        prefs.edit().remove(KEY_OPEN).commit()
    }

    override fun unfinishedProjectId(): String? = prefs.getString(KEY_OPEN, null)

    private companion object {
        const val KEY_OPEN = "openProjectId"
    }
}
