package com.qtekfun.ultimatevideoeditor.ui.hub

// Pure reducers of the Projects screen's list state (selection, search, sort, layout). The view model only calls them.

/** Applies the remembered layout and order. */
internal fun HubState.withView(prefs: HubViewPrefs) = copy(viewMode = prefs.mode, sort = prefs.sort, sortAscending = prefs.ascending)

/** Picks a sort key; a new key starts in its natural direction, the same key again changes nothing. */
internal fun HubState.withSort(key: ProjectSort) =
    if (key == sort) this else copy(sort = key, sortAscending = key.defaultAscending)

/** Opens the search field, or closes it and forgets the text. */
internal fun HubState.toggledSearch() = if (searchOpen) copy(searchOpen = false, query = "") else copy(searchOpen = true)

/** Long press: selection mode starts with [id] ticked (an unknown id is ignored). */
internal fun HubState.enterSelection(id: String) = if (projects.any { it.id == id }) copy(selected = setOf(id)) else this

/** A tap in selection mode ticks or unticks [id]; unticking the last one leaves selection mode. Outside selection mode it does nothing. */
internal fun HubState.toggled(id: String): HubState = when {
    !selecting || projects.none { it.id == id } -> this
    id in selected -> copy(selected = selected - id)
    else -> copy(selected = selected + id)
}

/** Ticks every project that is listed (a search narrows what "all" means). */
internal fun HubState.selectAllVisible(): HubState =
    if (selecting) copy(selected = visibleProjects.mapTo(LinkedHashSet()) { it.id }) else this

internal fun HubState.exitSelection() = copy(selected = emptySet())

/** Drops ticks of projects that are gone (deleted, or a refresh found them missing). */
internal fun HubState.prunedSelection(): HubState {
    if (selected.isEmpty()) return this
    val present = projects.mapTo(HashSet()) { it.id }
    return copy(selected = selected.filterTo(LinkedHashSet()) { it in present })
}
