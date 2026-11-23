package com.qtekfun.ultimatevideoeditor.ui.editor

// Tuning values shared by the editor features (the ViewModel and the handlers under ui/editor/*).

internal const val MIN_TARGET_LUFS = -40.0
internal const val MAX_TARGET_LUFS = -5.0
internal const val MIN_NOISE_SAMPLE_MICROS = 100_000L
internal const val ID_LENGTH = 8
internal const val DEFAULT_SAVE_DEBOUNCE_MILLIS = 500L
internal const val SAVE_RETRY_MILLIS = 5_000L
internal const val MAX_SAVE_RETRIES = 3
internal const val SNAP_THRESHOLD_FRAMES = 8L
internal const val NO_ASSET_KEY = -1L
internal const val DEFAULT_TITLE_TEXT = "Title"
internal const val TITLE_DEFAULT_MICROS = 3_000_000L
internal const val STICKER_DEFAULT_MICROS = 3_000_000L
internal const val PHOTO_DEFAULT_MICROS = 5_000_000L
internal const val HOVER_MICROS = 3_000_000L
internal const val DROP_LANE_ID = "track-v-new"
internal const val TRANSITION_DEFAULT_MICROS = 1_000_000L
internal const val FREEZE_DEFAULT_MICROS = 2_000_000L
internal const val PLAY_TICK_MILLIS = 16L
internal const val NANOS_PER_MICRO = 1_000L
internal const val MICROS_PER_SECOND = 1_000_000.0
internal const val MIN_CLIP_SCALE = 0.05
internal const val MARKER_TOGGLE_RADIUS_FRAMES = 2L
internal const val BEAT_WINDOW_PADDING_MICROS = 8_000_000L
