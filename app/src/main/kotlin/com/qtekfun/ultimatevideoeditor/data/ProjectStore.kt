package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto

/** The slice of [ProjectRepository] the editor needs; lets tests substitute an in-memory store. */
interface ProjectStore {
    /** @throws ProjectError on any failure. */
    suspend fun load(id: String): ProjectDto

    /** @throws ProjectError on any failure. */
    suspend fun save(project: ProjectDto)
}
