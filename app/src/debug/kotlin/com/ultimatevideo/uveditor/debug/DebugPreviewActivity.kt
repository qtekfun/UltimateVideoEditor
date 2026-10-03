package com.ultimatevideo.uveditor.debug

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ultimatevideo.uveditor.engine.preview.AssetInfo
import com.ultimatevideo.uveditor.engine.preview.ColorMode
import com.ultimatevideo.uveditor.engine.preview.PreviewEngine
import com.ultimatevideo.uveditor.engine.preview.PreviewException
import com.ultimatevideo.uveditor.ui.preview.PreviewSurface
import com.ultimatevideo.uveditor.ui.theme.UVEditorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug harness: plays a user-picked video (or a file passed with `--es path <file>`) through the
 * native preview pipeline. Extras: `path` (String), `autoplay` (Boolean), `hlg` (Boolean forces HLG mode), `budgetMb` (Int cache budget).
 * adb example: files under /sdcard/Android/data/<package>/files/ are readable by the app.
 */
class DebugPreviewActivity : ComponentActivity() {
    private var engine: PreviewEngine? = null
    private var info by mutableStateOf<AssetInfo?>(null)
    private var status by mutableStateOf("Pick a video")
    private var statsText by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var sliderFrame by mutableStateOf(0f)
    private var hlgForced by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val created = try {
            PreviewEngine.create(
                cacheBudgetBytes = intent.getIntExtra("budgetMb", 1024).toLong() shl 20,
            ) { e -> Log.e(TAG, "native error ${e.errorCode}: ${e.message}"); status = "Error: ${e.message}" }
        } catch (e: PreviewException) {
            Log.e(TAG, "engine init failed", e)
            status = "Init failed: ${e.message}"
            null
        }
        engine = created

        setContent {
            UVEditorTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) openUri(uri)
                }
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val e = engine
                    if (e != null) PreviewSurface(e, Modifier.fillMaxWidth().weight(1f))
                    Text(status)
                    Text(statsText)
                    val max = ((info?.durationFrames ?: 1L) - 1L).coerceAtLeast(1L).toFloat()
                    Slider(
                        value = sliderFrame,
                        valueRange = 0f..max,
                        onValueChange = {
                            sliderFrame = it
                            engine?.seek(ASSET_ID, it.toLong())
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Pick") }
                        Button(onClick = { togglePlay() }, enabled = info != null) { Text(if (playing) "Pause" else "Play") }
                        Button(onClick = { toggleHlg() }, enabled = info != null) { Text(if (hlgForced) "HLG→SDR" else "SDR") }
                    }
                }
            }
        }

        lifecycleScope.launch {
            while (true) {
                engine?.let { s ->
                    val st = s.stats()
                    statsText = "cache ${st.cacheEntries} frames, ${st.cacheUsedBytes shr 20}/${st.cacheBudgetBytes shr 20} MiB, " +
                        "drawn ${st.framesDrawn}, decoded ${st.framesDecoded}, stalls ${st.stalls}"
                    Log.i(TAG, "stats $statsText")
                }
                delay(500)
            }
        }

        intent.getStringExtra("path")?.let { path ->
            hlgForced = intent.getBooleanExtra("hlg", false)
            openFile(File(path), intent.getBooleanExtra("autoplay", false))
        }
    }

    private fun openUri(uri: Uri) {
        val descriptor = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: java.io.FileNotFoundException) {
            status = "Cannot open: ${e.message}"
            null
        } ?: return
        open(descriptor, autoplay = false, label = uri.lastPathSegment ?: "video")
    }

    private fun openFile(file: File, autoplay: Boolean) {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: java.io.FileNotFoundException) {
            status = "Cannot open ${file.path}: ${e.message}"
            return
        }
        open(descriptor, autoplay, file.name)
    }

    private fun open(descriptor: ParcelFileDescriptor, autoplay: Boolean, label: String) {
        val e = engine ?: return
        lifecycleScope.launch {
            playing = false
            val opened = try {
                withContext(Dispatchers.IO) {
                    e.closeAsset(ASSET_ID)
                    e.openAsset(ASSET_ID, descriptor)
                }
            } catch (ex: PreviewException) {
                status = "Open failed (${ex.errorCode}): ${ex.message}"
                return@launch
            }
            info = opened
            sliderFrame = 0f
            status = "$label: ${opened.width}x${opened.height} ${opened.fpsNum}/${opened.fpsDen} fps, " +
                "${opened.durationFrames} frames, transfer=${opened.colorTransfer}"
            if (opened.isHlg) hlgForced = true
            e.setColorMode(ASSET_ID, if (hlgForced) ColorMode.Hlg2020ToSdr709 else ColorMode.Sdr709)
            e.seek(ASSET_ID, 0)
            if (autoplay) {
                e.play(ASSET_ID, 0)
                playing = true
            }
        }
    }

    private fun togglePlay() {
        val e = engine ?: return
        if (playing) {
            e.pause()
        } else {
            e.play(ASSET_ID, sliderFrame.toLong())
        }
        playing = !playing
    }

    private fun toggleHlg() {
        hlgForced = !hlgForced
        engine?.setColorMode(ASSET_ID, if (hlgForced) ColorMode.Hlg2020ToSdr709 else ColorMode.Sdr709)
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "UVPreviewDebug"
        const val ASSET_ID = 1
    }
}
