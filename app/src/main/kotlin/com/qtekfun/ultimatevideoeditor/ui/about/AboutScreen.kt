package com.qtekfun.ultimatevideoeditor.ui.about

import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import com.qtekfun.ultimatevideoeditor.ui.language.AppLanguages
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
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
import com.qtekfun.ultimatevideoeditor.ui.theme.AppearanceStore
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
import com.qtekfun.ultimatevideoeditor.ui.editor.guide.ToolbarGuideScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Version, licence, privacy, third-party notices, storage and the local crash report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    controller: AboutController,
    appearance: AppearanceStore,
    mediaFolder: com.qtekfun.ultimatevideoeditor.data.interchange.MediaFolderSettings,
    /** The languages the app is translated into (bare codes such as "es"), the one picked (null follows the system) and the picker's action. */
    languages: List<String>,
    selectedLanguage: String?,
    onSelectLanguage: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(controller.snapshot()) }
    var confirmClear by remember { mutableStateOf(false) }
    var tipsMessage by remember { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    var browserMissing by remember { mutableStateOf(false) }
    val unavailable = stringResource(R.string.about_unavailable)
    val privacy = remember { readAsset(context, "legal/PRIVACY.md", unavailable) }
    val notices = remember { readAsset(context, "legal/THIRD_PARTY_NOTICES.md", unavailable) }
    val licence = remember { readAsset(context, "legal/LICENSE.txt", unavailable) }

    if (guideOpen) {
        ToolbarGuideScreen(onClose = { guideOpen = false })
        return
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) } },
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
            Section(stringResource(R.string.about_appearance)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.about_pure_black))
                        Text(
                            stringResource(R.string.about_pure_black_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = appearance.amoled, onCheckedChange = { appearance.amoled = it })
                }
            }
            Section(stringResource(R.string.about_language)) {
                Text(stringResource(R.string.about_language_hint), style = MaterialTheme.typography.bodySmall)
                for (tag in listOf<String?>(null) + languages) {
                    val label = if (tag == null) stringResource(R.string.language_system) else AppLanguages.nativeName(tag)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = tag == selectedLanguage, role = Role.RadioButton, onClick = { onSelectLanguage(tag) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == selectedLanguage, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Section(stringResource(R.string.about_media_folder)) {
                var folderLabel by remember { mutableStateOf(mediaFolder.label()) }
                var hasFolder by remember { mutableStateOf(mediaFolder.treeUri() != null) }
                var folderError by remember { mutableStateOf<UiText?>(null) }
                var layout by remember { mutableStateOf<com.qtekfun.ultimatevideoeditor.data.interchange.LayoutSummary?>(null) }
                // Reading the folder goes through the document provider: keep it off the main thread.
                androidx.compose.runtime.LaunchedEffect(folderLabel, hasFolder) {
                    layout = if (hasFolder) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mediaFolder.summary() } else null
                }
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            mediaFolder.set(uri.toString())
                            folderLabel = mediaFolder.label()
                            hasFolder = true
                            folderError = null
                        } catch (e: SecurityException) {
                            folderError = UiText.res(R.string.folder_access_failed, e.message.orEmpty())
                        }
                    }
                }
                Text(stringResource(R.string.about_media_folder_for_packages))
                Text(
                    stringResource(R.string.about_media_folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        !hasFolder -> stringResource(R.string.about_folder_none)
                        folderLabel != null -> stringResource(R.string.about_folder_label, folderLabel.orEmpty())
                        else -> stringResource(R.string.about_folder_unreadable)
                    },
                )
                layout?.let { summary ->
                    Text(stringResource(R.string.about_files_go_to, summary.path))
                    if (!summary.rootExists) {
                        Text(stringResource(R.string.about_nothing_there), style = MaterialTheme.typography.bodySmall)
                    }
                    for (category in summary.categories) {
                        val isMedia = category.name == com.qtekfun.ultimatevideoeditor.data.interchange.MediaLayout.MEDIA
                        Text(
                            pluralStringResource(if (isMedia) R.plurals.about_category_folders else R.plurals.about_category_files, category.items, category.name, category.items),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (summary.looseFiles > 0) {
                        Text(
                            pluralStringResource(R.plurals.about_loose_files, summary.looseFiles, summary.looseFiles),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                folderError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { picker.launch(null) }) { Text(stringResource(if (hasFolder) R.string.common_change else R.string.common_choose_folder)) }
            }
            Section(stringResource(R.string.about_version)) {
                Text(stringResource(R.string.about_version_line, snapshot.version.display))
                snapshot.engineVersion?.let { Text(stringResource(R.string.about_engine_version, it), style = MaterialTheme.typography.bodySmall) }
            }
            Section(stringResource(R.string.about_licence)) {
                Text(stringResource(R.string.about_licence_body, AboutController.LICENCE_NAME))
                SelectionContainer { Text(stringResource(R.string.about_source_code, snapshot.repositoryUrl), style = MaterialTheme.typography.bodySmall) }
                Expandable(stringResource(R.string.about_full_licence), licence)
            }
            Section(stringResource(R.string.about_privacy)) {
                Text(stringResource(R.string.about_privacy_body))
                Expandable(stringResource(R.string.about_read_privacy), privacy, markdown = true)
            }
            Section(stringResource(R.string.about_third_party)) {
                Expandable(stringResource(R.string.about_notices), notices, markdown = true)
            }
            Section(stringResource(R.string.about_storage)) {
                Text(stringResource(R.string.about_storage_projects, AboutController.formatBytes(snapshot.usage.projectsBytes)))
                Text(stringResource(R.string.about_storage_caches, AboutController.formatBytes(snapshot.usage.cacheBytes)))
                Text(stringResource(R.string.about_storage_proxies, AboutController.formatBytes(snapshot.usage.proxyBytes)))
                OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.about_clear_caches)) }
            }
            Section(stringResource(R.string.about_crash)) {
                val report = snapshot.crashReport
                if (report == null) {
                    Text(stringResource(R.string.about_crash_none))
                } else {
                    Text(stringResource(R.string.about_crash_body))
                    Expandable(stringResource(R.string.about_crash_show), report)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(context, report) }) { Text(stringResource(R.string.common_copy)) }
                        OutlinedButton(onClick = { share(context, report) }) { Text(stringResource(R.string.common_share)) }
                        OutlinedButton(onClick = { controller.deleteCrashReport(); snapshot = controller.snapshot() }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
            Section(stringResource(R.string.about_help)) {
                OutlinedButton(onClick = { guideOpen = true }) { Text(stringResource(R.string.about_toolbar_guide)) }
                Text(stringResource(R.string.about_toolbar_guide_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { browserMissing = !openOnlineGuide(context) }) { Text(stringResource(R.string.about_online_guide)) }
                Text(
                    stringResource(R.string.about_online_guide_hint, AboutController.ONLINE_GUIDE_URL),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (browserMissing) Text(stringResource(R.string.about_no_browser), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { controller.showTipsAgain(); tipsMessage = true }) { Text(stringResource(R.string.about_show_tips)) }
                if (tipsMessage) Text(stringResource(R.string.about_tips_message), style = MaterialTheme.typography.bodySmall)
            }
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.about_clear_title)) },
            text = { Text(stringResource(R.string.about_clear_body)) },
            confirmButton = {
                TextButton(onClick = { controller.clearCaches(); snapshot = controller.snapshot(); confirmClear = false }) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
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
    TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.common_hide) else label) }
    if (!open) return
    SelectionContainer {
        if (markdown) MarkdownView(text) else Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun readAsset(context: Context, path: String, unavailable: String): String =
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault(unavailable)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.about_crash_clip_label), text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.about_crash_share_title)))
}

/** Hands the guide's address to the browser (the user tapped for it); returns false when no app can open it. */
private fun openOnlineGuide(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AboutController.ONLINE_GUIDE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: android.content.ActivityNotFoundException) {
    false
}
