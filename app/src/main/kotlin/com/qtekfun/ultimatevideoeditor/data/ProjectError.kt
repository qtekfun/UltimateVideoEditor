package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.UserFacing

/**
 * Every failure of the project store is one of these; nothing is swallowed. The message is English, for logs and tests of the
 * store; the words for the person are [userText].
 */
sealed class ProjectError(message: String, cause: Throwable? = null) : Exception(message, cause), UserFacing {
    class NotFound(val projectId: String) : ProjectError("Project '$projectId' was not found") { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_not_found, projectId)
    }

    class InvalidName(override val userText: UiText) : ProjectError("invalid project name")

    class DuplicateName(val name: String) : ProjectError("A project named \"$name\" already exists") { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_duplicate_name, name)
    }

    class InvalidId(val projectId: String) : ProjectError("'$projectId' is not a valid project id") { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_invalid_id, projectId)
    }

    class Corrupt(val detail: String, cause: Throwable? = null) :
        ProjectError("Project file is corrupt: $detail", cause) { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_corrupt, detail)
    }

    class UnsupportedVersion(val found: Int, val supported: Int) :
        ProjectError("Project schema version $found is newer than supported version $supported") { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_unsupported_version, found, supported)
    }

    class Io(val detail: String, cause: Throwable) : ProjectError("Storage error: $detail", cause) { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_io, detail)
    }

    /** The import has footage to unpack and the user has not chosen a media folder yet. */
    class MediaFolderRequired : ProjectError("Choose a folder for the media of this package first") { // i18n-ok: log text
        override val userText: UiText = UiText.res(R.string.pe_media_folder_required)
    }

    /** A project bundle could not be read; [userText] says why in the words of the person. */
    class Bundle(override val userText: UiText, cause: Throwable? = null) : ProjectError("bundle could not be imported", cause)
}
