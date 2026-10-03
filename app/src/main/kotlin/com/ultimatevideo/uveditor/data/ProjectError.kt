package com.ultimatevideo.uveditor.data

/** Every failure of the project store is one of these; nothing is swallowed. */
sealed class ProjectError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound(val projectId: String) : ProjectError("Project '$projectId' was not found")

    class InvalidName(reason: String) : ProjectError(reason)

    class InvalidId(val projectId: String) : ProjectError("'$projectId' is not a valid project id")

    class Corrupt(detail: String, cause: Throwable? = null) :
        ProjectError("Project file is corrupt: $detail", cause)

    class UnsupportedVersion(val found: Int, val supported: Int) :
        ProjectError("Project schema version $found is newer than supported version $supported")

    class Io(detail: String, cause: Throwable) : ProjectError("Storage error: $detail", cause)
}
