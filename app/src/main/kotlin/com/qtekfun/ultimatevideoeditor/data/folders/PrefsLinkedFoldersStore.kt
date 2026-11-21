package com.qtekfun.ultimatevideoeditor.data.folders

import android.content.SharedPreferences

/** The linked folders in local preferences. */
class PrefsLinkedFoldersStore(private val prefs: SharedPreferences) : LinkedFoldersStore {
    override fun load(): List<LinkedFolder> = LinkedFoldersCodec.decode(prefs.getString(KEY, null))

    override fun save(folders: List<LinkedFolder>) {
        prefs.edit().putString(KEY, LinkedFoldersCodec.encode(folders)).apply()
    }

    companion object {
        const val FILE = "linked_folders"
        private const val KEY = "folders"
    }
}
