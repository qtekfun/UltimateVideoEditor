package com.ultimatevideo.uveditor.ui.editor.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Every symbol of the editor, drawn with the toolbar's own icon, with its name, what it does and when it is
 * available, grouped by where it sits; then the gestures. Works offline from [ToolbarGuide], and a search box
 * narrows both lists. Shown from About and from the help button of the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarGuideScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    val entries = ToolbarGuide.search(query)
    val gestures = ToolbarGuide.searchGestures(query)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Toolbar guide") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search symbols and gestures") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                item {
                    Text(
                        "Long press any icon in the editor to see its name. Tap the ? at the top to open this guide again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (entries.isEmpty() && gestures.isEmpty()) {
                    item { Text("Nothing matches \"$query\".") }
                }
                for (section in GuideSection.entries) {
                    val inSection = entries.filter { it.section == section }
                    if (inSection.isEmpty()) continue
                    item(key = "h-${section.name}") { SectionHeader(section.title, section.where) }
                    items(inSection, key = { it.id }) { EntryRow(it) }
                }
                if (gestures.isNotEmpty()) {
                    item(key = "h-gestures") { SectionHeader("Gestures", "Things you do with your fingers; they have no button.") }
                    items(gestures, key = { it.id }) { GestureRow(it) }
                }
                item { Box(modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EntryRow(entry: GuideEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${entry.name}. ${entry.help}"
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The same ImageVector the toolbar draws, in the toolbar's content colour.
        Box(
            modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = entry.icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(entry.help, style = MaterialTheme.typography.bodyMedium)
            if (entry.enabledWhen.isNotEmpty()) {
                Text("Available when: ${entry.enabledWhen}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GestureRow(gesture: GuideGesture) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(gesture.title, style = MaterialTheme.typography.titleSmall)
        Text(gesture.help, style = MaterialTheme.typography.bodyMedium)
    }
}
