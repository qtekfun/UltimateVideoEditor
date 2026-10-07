package com.ultimatevideo.uveditor.debug

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.engine.timeline.EngineStatus
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.SnapshotClip
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTrackType
import com.ultimatevideo.uveditor.engine.timeline.TimelineEngine
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.timeline.ThumbnailCache
import com.ultimatevideo.uveditor.engine.timeline.TimelineSnapshot
import com.ultimatevideo.uveditor.engine.timeline.WaveformCache
import com.ultimatevideo.uveditor.ui.editor.TimelineHost
import com.ultimatevideo.uveditor.ui.theme.UVEditorTheme
import java.io.File

/**
 * Debug harness: a synthetic multitrack timeline on the native canvas. If a media file with audio
 * is pushed to `Android/data/<pkg>/files/demo_media.mp4`, its waveform and thumbnails are generated and drawn.
 */
class TimelineDemoActivity : ComponentActivity() {
    private lateinit var engine: TimelineEngine
    private var status by mutableStateOf("Starting…")
    private var selectedKey: Long = -1
    private var playhead: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = TimelineEngine(
            resources.displayMetrics.density,
            onThumbnailError = { assetKey, result, detail -> status = "thumbnails asset=$assetKey -> $result ($detail)" },
        ) { assetKey, result ->
            status = "waveform asset=$assetKey -> $result"
        }
        pushSnapshot()
        requestDemoWaveform()
        // `--ef zoom <factor>` zooms in at start (adb cannot pinch), e.g. to exercise finer thumbnail levels.
        val zoom = intent.getFloatExtra("zoom", 1f)
        if (zoom != 1f) engine.zoomBy(zoom, 0f)
        // `--ez fit true` presses "Fit" (the time axis only).
        if (intent.getBooleanExtra("fit", false)) engine.fitToContent()

        setContent {
            UVEditorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.safeDrawingPadding()) {
                        Text(text = status, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        TimelineHost(
                            engine = engine,
                            onTap = ::onTimelineTap,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }

    private fun onTimelineTap(hit: TimelineHit) {
        status = "tap ${hit.kind} track=${hit.trackIndex} clip=${hit.clipKey} frame=${hit.frame}"
        when (hit.kind) {
            HitKind.RULER, HitKind.PLAYHEAD -> {
                playhead = hit.frame
                engine.setPlayhead(playhead)
            }
            HitKind.CLIP, HitKind.CLIP_LEFT_EDGE, HitKind.CLIP_RIGHT_EDGE -> {
                selectedKey = hit.clipKey
                pushSnapshot()
            }
            HitKind.EMPTY_TRACK, HitKind.NONE, HitKind.ABOVE_LANES, HitKind.OUTSIDE, HitKind.LANE_HEADER, HitKind.MARKER -> {
                selectedKey = -1
                pushSnapshot()
            }
        }
    }

    private fun pushSnapshot() {
        engine.setSnapshot(buildDemoSnapshot(selectedKey))
    }

    private fun requestDemoWaveform() {
        val media = File(getExternalFilesDir(null), "demo_media.mp4")
        if (!media.isFile) {
            status = "No demo_media.mp4 in ${media.parent}; drawing blocks only"
            return
        }
        val cacheFile = WaveformCache(filesDir).fileFor(DEMO_ASSET_ID)
        // The engine owns the descriptor, so detach it from the ParcelFileDescriptor.
        val fd = ParcelFileDescriptor.open(media, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        engine.requestWaveform(DEMO_ASSET_KEY, fd, cacheFile)
        val thumbFd = ParcelFileDescriptor.open(media, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        engine.requestThumbnails(DEMO_ASSET_KEY, thumbFd, ThumbnailCache(filesDir).dirFor(DEMO_ASSET_ID))
        status = "Extracting waveform and thumbnails…"
    }

    private fun buildDemoSnapshot(selected: Long): TimelineSnapshot {
        val order = listOf(
            SnapshotTrackType.TITLE, SnapshotTrackType.VIDEO, SnapshotTrackType.VIDEO,
            SnapshotTrackType.AUDIO, SnapshotTrackType.AUDIO,
        )
        val tracks = List(intent.getIntExtra("lanes", order.size).coerceIn(1, 40)) { order[it % order.size] }
        val clips = mutableListOf<SnapshotClip>()
        var key = 0L
        // Deterministic pseudo-random layout: ~60 clips across five tracks.
        for (track in tracks.indices) {
            var cursor = if (track % 2 == 0) 0L else 45L
            repeat(12) { i ->
                val duration = 90L + ((i * 37 + track * 53) % 8) * 30L
                val isMedia = tracks[track] != SnapshotTrackType.TITLE
                clips += SnapshotClip(
                    clipKey = ++key,
                    trackIndex = track,
                    assetKey = if (isMedia) DEMO_ASSET_KEY else -1,
                    startFrame = cursor,
                    durationFrames = duration,
                    sourceInFrame = (i * 211L) % 600L,
                    sourceFpsNum = 30,
                    sourceFpsDen = 1,
                    selected = key == selected,
                )
                cursor += duration + if (i % 4 == 3) 60L else 0L
            }
        }
        return TimelineSnapshot(fpsNum = 30, fpsDen = 1, tracks = tracks, clips = clips)
    }

    private companion object {
        const val DEMO_ASSET_ID = "demo"
        const val DEMO_ASSET_KEY = 1L
    }
}
