package com.ultimatevideo.uveditor.domain.templates

import com.ultimatevideo.uveditor.domain.Timeline

/** What a placeholder accepts. */
enum class PlaceholderKind(val label: String) {
    /** A video clip with picture. */
    VIDEO("Video"),

    /** A video clip or a photo. */
    VIDEO_OR_PHOTO("Video or photo"),

    /** A photo. */
    PHOTO("Photo"),

    /** Music or any audio. */
    AUDIO("Audio"),
}

/**
 * A slot of a template that the user fills with their own media. [id] is also the slot's key in the template
 * timeline: the clip whose asset id is `slot:<id>` is this placeholder. [frames] is how long the slot is in project
 * frames; a shorter file shortens it (down to [minFrames], below which the user is told it is too short) and a
 * longer one is trimmed to it.
 */
data class Placeholder(
    val id: String,
    val name: String,
    val kind: PlaceholderKind,
    val frames: Long,
    val minFrames: Long = 1,
    /** A slot the user may leave empty (it is then removed and the gap closes). */
    val optional: Boolean = false,
) {
    init {
        require(id.isNotBlank() && ':' !in id) { "a placeholder id must be non-blank and contain no colon" }
        require(frames > 0 && minFrames in 1..frames) { "a placeholder needs 1 <= minFrames <= frames" }
    }

    /** The asset id the slot's clip carries in the template timeline. */
    val slotAssetId: String get() = SLOT_PREFIX + id

    companion object {
        const val SLOT_PREFIX = "slot:"

        /** The placeholder id a template clip's asset id stands for, or null if it is a real asset. */
        fun idOfSlotAsset(assetId: String?): String? = assetId?.takeIf { it.startsWith(SLOT_PREFIX) }?.removePrefix(SLOT_PREFIX)
    }
}

/**
 * A project without media: the project settings, the whole timeline (titles, stickers, effects, transitions,
 * markers, tracks) and the [placeholders] whose clips stand where the user's media will go. The media itself is
 * never part of a template, so sharing one shares structure only.
 */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val width: Int,
    val height: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val colorSpace: String,
    val timeline: Timeline,
    val placeholders: List<Placeholder>,
) {
    /** Reason the template cannot be used, or null when it is sound. */
    fun problem(): String? {
        if (id.isBlank()) return "a template needs an id"
        if (name.isBlank()) return "a template needs a name"
        if (width <= 0 || height <= 0 || fpsNum <= 0 || fpsDen <= 0) return "the template's size and frame rate must be positive"
        val violations = timeline.invariantViolations()
        if (violations.isNotEmpty()) return "the template's timeline is invalid: ${violations.first()}"
        val ids = placeholders.map { it.id }
        if (ids.size != ids.toSet().size) return "placeholder ids must be unique"
        val slotClips = timeline.tracks.flatMap { it.clips }.mapNotNull { Placeholder.idOfSlotAsset(it.assetId) }
        if (slotClips.toSet() != ids.toSet() || slotClips.size != ids.size) return "every placeholder needs exactly one slot clip in the timeline and vice versa"
        return null
    }

    fun placeholder(id: String): Placeholder? = placeholders.firstOrNull { it.id == id }
}
