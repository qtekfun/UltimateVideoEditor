package com.ultimatevideo.uveditor.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders [MarkdownBlocks] as ordinary Compose text; the About screen is not the timeline, so Compose is fine here. */
@Composable
internal fun MarkdownView(text: String) {
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    styled(block.spans, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp),
                )
                is MdBlock.Paragraph -> Text(styled(block.spans, codeBackground), style = MaterialTheme.typography.bodySmall)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (12 * block.depth).dp)) {
                    Text(block.marker, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(22.dp))
                    Text(styled(block.spans, codeBackground), style = MaterialTheme.typography.bodySmall)
                }
                is MdBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                MdBlock.Rule -> HorizontalDivider()
                is MdBlock.Table -> TableView(block, codeBackground)
            }
        }
    }
}

@Composable
private fun TableView(table: MdBlock.Table, codeBackground: androidx.compose.ui.graphics.Color) {
    Column {
        TableRow(table.header, header = true, codeBackground)
        HorizontalDivider()
        for (row in table.rows) TableRow(row, header = false, codeBackground)
    }
}

@Composable
private fun TableRow(cells: List<List<MdSpan>>, header: Boolean, codeBackground: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (cell in cells) {
            Text(
                styled(cell, codeBackground),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun styled(spans: List<MdSpan>, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
    }
}
