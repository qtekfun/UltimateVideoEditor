package com.ultimatevideo.uveditor.domain

/**
 * The colour space a project is composited and exported in. [id] is the string stored in
 * `project.json` (`settings.colorSpace`).
 */
enum class ProjectColorSpace(val id: String, val label: String, val isHdr: Boolean) {
    REC709_SDR("Rec709-SDR", "SDR Rec.709", false),
    REC2020_HLG("Rec2020-HLG", "HDR HLG Rec.2020", true),
    ;

    companion object {
        /** Unknown or missing values read as SDR, which is what every project before HDR was. */
        fun fromId(id: String?): ProjectColorSpace = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: REC709_SDR
    }
}

/** Transfer characteristic of a media file, as far as colour conversion cares. */
enum class SourceColorSpace {
    SDR,
    HLG,
    PQ,
    ;

    /**
     * The value the native engine takes for "this source" (`render::ColorMode`, any target): the
     * engine picks the conversion from it and the colour space it renders in.
     */
    val nativeModeValue: Int
        get() = when (this) {
            SDR -> 0
            HLG -> 1
            PQ -> 4
        }

    companion object {
        /** Reads the `colorSpace` string of a media asset ("Rec709-SDR", "Rec2020-HLG", "Rec2020-PQ"). */
        fun fromId(id: String?): SourceColorSpace = when {
            id == null -> SDR
            id.contains("HLG", ignoreCase = true) -> HLG
            id.contains("PQ", ignoreCase = true) || id.contains("2084") -> PQ
            else -> SDR
        }
    }
}
