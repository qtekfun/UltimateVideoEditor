package com.qtekfun.ultimatevideoeditor.ui.editor

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.Marker
import com.qtekfun.ultimatevideoeditor.domain.MarkerColor
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.ShotList
import com.qtekfun.ultimatevideoeditor.ui.library.markerColour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The flag list: every manual flag in time order, grouped by colour, with a tick for "shot". It is the project's shot list for
 * recording B-roll: filter it (only pending, some colours) and share it as checkbox text or save it as CSV. A tap on a row
 * moves the playhead there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlagListSheet(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var onlyPending by remember { mutableStateOf(true) }
    // Colours the person switched off; empty means all (flags without a colour are the null entry).
    var hidden by remember { mutableStateOf(emptySet<MarkerColor?>()) }
    val manual = state.timeline.markers.filter { it.kind == MarkerKind.MANUAL }
    val colours: List<MarkerColor?> = (MarkerColor.entries + listOf<MarkerColor?>(null)).filter { c -> manual.any { it.color == c } }
    val filter = ShotList.Filter(onlyPending, colours.filter { it !in hidden }.toSet().takeIf { hidden.isNotEmpty() })
    val shown = ShotList.select(state.timeline.markers, filter)
    val pending = manual.count { !it.done }

    val title = stringResource(R.string.flags_text_title, state.projectName)
    val timecode = { frame: Long -> formatTimecode(frame, state.fps) }
    val groupNames = MarkerColor.entries.associateWith { stringResource(markerColourName(it)).replaceFirstChar { c -> c.uppercase() } } +
        (null to stringResource(R.string.flags_no_colour))
    val csvHeader = listOf(R.string.flags_csv_done, R.string.flags_csv_colour, R.string.flags_csv_time, R.string.flags_csv_name, R.string.flags_csv_note)
        .map { stringResource(it) }
    val saveFailed = stringResource(R.string.flags_save_failed)
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val csv = ShotList.csv(csvHeader, shown, timecode) { groupNames[it].orEmpty() }
            scope.launch {
                val ok = withContext(Dispatchers.IO) { writeText(context, uri, csv) }
                if (!ok) Toast.makeText(context, saveFailed, Toast.LENGTH_LONG).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onIntent(MarkerIntent.CloseList) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.flags_list_title), style = MaterialTheme.typography.titleMedium)
                    Text(pluralStringResource(R.plurals.flags_pending_count, pending, pending), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onIntent(MarkerIntent.CloseList) }) { Text(stringResource(R.string.ed_2a_done)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = onlyPending, onClick = { onlyPending = !onlyPending }, label = { Text(stringResource(R.string.flags_only_pending)) })
                for (colour in colours) {
                    val name = groupNames[colour].orEmpty()
                    FilterChip(
                        selected = colour !in hidden,
                        onClick = { hidden = if (colour in hidden) hidden - colour else hidden + colour },
                        label = {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(colour?.let { markerColour(it) } ?: MaterialTheme.colorScheme.outline))
                        },
                        modifier = Modifier.described(name),
                    )
                }
            }
            if (shown.isEmpty()) {
                Text(stringResource(R.string.flags_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (group in ShotList.grouped(shown)) {
                        item(key = "group-${group.color}") {
                            Text(
                                groupNames[group.color].orEmpty() + " (" + group.flags.size + ")",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(group.flags, key = { it.id }) { flag -> FlagRow(flag, timecode(flag.frame.value), onIntent) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                OutlinedButton(
                    enabled = shown.isNotEmpty(),
                    onClick = { shareText(context, ShotList.text(title, shown, timecode) { groupNames[it].orEmpty() }) },
                ) { Text(stringResource(R.string.flags_share)) }
                OutlinedButton(
                    enabled = shown.isNotEmpty(),
                    onClick = { saveCsv.launch(state.projectName.ifBlank { "flags" } + ".csv") },
                ) { Text(stringResource(R.string.flags_save_csv)) }
            }
        }
    }
}

@Composable
private fun FlagRow(flag: Marker, time: String, onIntent: (EditorIntent) -> Unit) {
    val goTo = stringResource(R.string.flags_go_to)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = goTo) { onIntent(MarkerIntent.GoTo(flag.id)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = flag.done,
            onCheckedChange = { onIntent(MarkerIntent.SetDone(flag.id, it)) },
            modifier = Modifier.described(stringResource(R.string.flags_mark_shot)),
        )
        Box(Modifier.size(12.dp).clip(CircleShape).background(flag.color?.let { markerColour(it) } ?: MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 4.dp, bottom = 4.dp)) {
            Text(flag.name ?: flag.note ?: stringResource(R.string.flags_unnamed, time), style = MaterialTheme.typography.bodyLarge)
            if (flag.name != null && flag.note != null) Text(flag.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private fun writeText(context: Context, uri: android.net.Uri, text: String): Boolean = try {
    val stream = context.contentResolver.openOutputStream(uri)
    if (stream == null) false else stream.use { it.write(text.toByteArray(Charsets.UTF_8)); true }
} catch (e: IOException) {
    false
}
