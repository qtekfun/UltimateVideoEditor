package com.ultimatevideo.uveditor.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import com.ultimatevideo.uveditor.ui.theme.AppearanceStore
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ultimatevideo.uveditor.ui.editor.guide.ToolbarGuideScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Version, licence, privacy, third-party notices, storage and the local crash report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    controller: AboutController,
    appearance: AppearanceStore,
    mediaFolder: com.ultimatevideo.uveditor.data.interchange.MediaFolderSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(controller.snapshot()) }
    var confirmClear by remember { mutableStateOf(false) }
    var tipsMessage by remember { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    var browserMissing by remember { mutableStateOf(false) }
    val privacy = remember { readAsset(context, "legal/PRIVACY.md") }
    val notices = remember { readAsset(context, "legal/THIRD_PARTY_NOTICES.md") }
    val licence = remember { readAsset(context, "legal/LICENSE.txt") }

    if (guideOpen) {
        ToolbarGuideScreen(onClose = { guideOpen = false })
        return
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About ultimateVE") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        // Lines stay readable on a 2800 px tablet: the text column is capped and centred.
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Appearance") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pure black backgrounds")
                        Text(
                            "The app is always dark. This makes the backgrounds black instead of dark grey, which saves power on OLED screens.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = appearance.amoled, onCheckedChange = { appearance.amoled = it })
                }
            }
            Section("Media folder") {
                var folderLabel by remember { mutableStateOf(mediaFolder.label()) }
                var hasFolder by remember { mutableStateOf(mediaFolder.treeUri() != null) }
                var folderError by remember { mutableStateOf<String?>(null) }
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            mediaFolder.set(uri.toString())
                            folderLabel = mediaFolder.label()
                            hasFolder = true
                            folderError = null
                        } catch (e: SecurityException) {
                            folderError = "Could not keep access to that folder: ${e.message}"
                        }
                    }
                }
                Text("Media folder for imported packages")
                Text(
                    "Footage that comes inside a LumaFusion package is copied here, so you can see and manage the files (a USB drive or SD card works too). Deleting a project never deletes these files.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        !hasFolder -> "No folder chosen yet: you are asked when you import a package."
                        folderLabel != null -> "Folder: $folderLabel"
                        else -> "A folder is set but cannot be read now (drive unplugged or access removed). Choose it again."
                    },
                )
                folderError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { picker.launch(null) }) { Text(if (hasFolder) "Change" else "Choose folder") }
            }
            Section("Version") {
                Text("ultimateVE ${snapshot.version.display}")
            }
            Section("Licence") {
                Text("${AboutController.LICENCE_NAME}. Free software: you can redistribute it and change it under the terms of the licence, with no warranty.")
                SelectionContainer { Text("Source code: ${snapshot.repositoryUrl}", style = MaterialTheme.typography.bodySmall) }
                Expandable("Full licence text", licence)
            }
            Section("Privacy") {
                Text("Nothing leaves your device: no network permission, no accounts, no analytics, no crash-reporting service.")
                Expandable("Read the privacy statement", privacy, markdown = true)
            }
            Section("Third-party software") {
                Expandable("Notices", notices, markdown = true)
            }
            Section("Storage") {
                Text("Projects: ${AboutController.formatBytes(snapshot.usage.projectsBytes)}")
                Text("Caches (waveforms, thumbnails, analysis): ${AboutController.formatBytes(snapshot.usage.cacheBytes)}")
                Text("Proxy copies: ${AboutController.formatBytes(snapshot.usage.proxyBytes)} (manage them in the proxy sheet of the editor)")
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear caches") }
            }
            Section("Last crash report") {
                val report = snapshot.crashReport
                if (report == null) {
                    Text("No crash has been recorded.")
                } else {
                    Text("The app stored this report on your device when it last crashed. It holds no project or media names. It is only shared if you tap Share.")
                    Expandable("Show report", report)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(context, report) }) { Text("Copy") }
                        OutlinedButton(onClick = { share(context, report) }) { Text("Share") }
                        OutlinedButton(onClick = { controller.deleteCrashReport(); snapshot = controller.snapshot() }) { Text("Delete") }
                    }
                }
            }
            Section("Help") {
                OutlinedButton(onClick = { guideOpen = true }) { Text("Toolbar guide") }
                Text("What every symbol of the editor does. It is part of the app and works offline.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { browserMissing = !openOnlineGuide(context) }) { Text("Online guide") }
                Text(
                    "Opens the full user guide in your browser (${AboutController.ONLINE_GUIDE_URL}). The app itself stays offline: it loads nothing, your browser does.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (browserMissing) Text("No browser was found on this device.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { controller.showTipsAgain(); tipsMessage = true }) { Text("Show tips again") }
                if (tipsMessage) Text("The tips will appear the next time you open the project list.", style = MaterialTheme.typography.bodySmall)
            }
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear caches?") },
            text = { Text("Waveforms, thumbnails and analysis results are rebuilt when needed. Projects and media are not touched.") },
            confirmButton = {
                TextButton(onClick = { controller.clearCaches(); snapshot = controller.snapshot(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Expandable(label: String, text: String, markdown: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else label) }
    if (!open) return
    SelectionContainer {
        if (markdown) MarkdownView(text) else Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun readAsset(context: Context, path: String): String =
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("(unavailable)")

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ultimateVE crash report", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share crash report"))
}

/** Hands the guide's address to the browser (the user tapped for it); returns false when no app can open it. */
private fun openOnlineGuide(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AboutController.ONLINE_GUIDE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: android.content.ActivityNotFoundException) {
    false
}
