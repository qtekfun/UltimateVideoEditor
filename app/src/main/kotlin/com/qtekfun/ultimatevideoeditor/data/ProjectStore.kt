package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto

/** The slice of [ProjectRepository] the editor needs; lets tests substitute an in-memory store. */
interface ProjectStore {
    /** @throws ProjectError on any failure. */
    suspend fun load(id: String): ProjectDto

    /** @throws ProjectError on any failure. */
    suspend fun save(project: ProjectDto)

    /**
     * Records how many library files could not be read, for the Projects screen, without rewriting the project (so its
     * place in the list, ordered by last modification, does not change). Does nothing when [missingMedia] is what the
     * list already shows. Stores that have no listing keep the default.
     * @throws ProjectError if the status cannot be written.
     */
    suspend fun saveMediaStatus(id: String, missingMedia: Int) = Unit
}
