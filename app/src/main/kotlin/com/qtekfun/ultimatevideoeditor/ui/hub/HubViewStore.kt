package com.qtekfun.ultimatevideoeditor.ui.hub

import android.content.SharedPreferences

/** What the Projects screen remembers about how the list looks: layout, sort key and direction. */
data class HubViewPrefs(
    val mode: HubViewMode = HubViewMode.LIST,
    val sort: ProjectSort = ProjectSort.LAST_EDITED,
    val ascending: Boolean = ProjectSort.LAST_EDITED.defaultAscending,
)

/** Where [HubViewPrefs] are kept. Local to the device, like the editor layout. */
interface HubViewStore {
    fun load(): HubViewPrefs

    fun save(prefs: HubViewPrefs)
}

/** Remembers nothing; used by tests and as the fallback when preferences are unavailable. */
object NoHubViewStore : HubViewStore {
    override fun load() = HubViewPrefs()

    override fun save(prefs: HubViewPrefs) = Unit
}

/** The stored names back to values; anything unknown (an older or newer version's value) means the default. */
fun hubViewModeOf(name: String?): HubViewMode = HubViewMode.entries.firstOrNull { it.name == name } ?: HubViewMode.LIST

fun projectSortOf(name: String?): ProjectSort = ProjectSort.entries.firstOrNull { it.name == name } ?: ProjectSort.LAST_EDITED

/** Preferences-backed store, the same mechanism as the editor layout store (`PrefsLayoutStore`). */
class PrefsHubViewStore(private val prefs: SharedPreferences) : HubViewStore {
    override fun load(): HubViewPrefs {
        val sort = projectSortOf(prefs.getString(KEY_SORT, null))
        return HubViewPrefs(
            mode = hubViewModeOf(prefs.getString(KEY_MODE, null)),
            sort = sort,
            ascending = if (prefs.contains(KEY_ASCENDING)) prefs.getBoolean(KEY_ASCENDING, sort.defaultAscending) else sort.defaultAscending,
        )
    }

    override fun save(prefs: HubViewPrefs) {
        this.prefs.edit()
            .putString(KEY_MODE, prefs.mode.name)
            .putString(KEY_SORT, prefs.sort.name)
            .putBoolean(KEY_ASCENDING, prefs.ascending)
            .apply()
    }

    companion object {
        const val FILE = "hub_view"
        private const val KEY_MODE = "mode"
        private const val KEY_SORT = "sort"
        private const val KEY_ASCENDING = "ascending"
    }
}
