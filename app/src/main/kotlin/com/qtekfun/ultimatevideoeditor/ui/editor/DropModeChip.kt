package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.DropChoice
import com.qtekfun.ultimatevideoeditor.domain.DropKind

/**
 * Shown over the timeline while a clip is dragged to a place where it would insert or overwrite: the action that releasing does
 * is lit, the other one is a tap away (also with a second finger anywhere on the timeline). "Auto" means the position decides.
 */
@Composable
internal fun DropModeChip(effective: DropKind?, choice: DropChoice, onFlip: () -> Unit, modifier: Modifier = Modifier) {
    val inserting = effective == DropKind.INSERT
    val modeDescription = stringResource(if (inserting) R.string.ed_2a_drop_mode_insert else R.string.ed_2a_drop_mode_overwrite)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 6.dp,
        modifier = modifier
            .described(modeDescription)
            .clickable(role = Role.Button, onClick = onFlip),
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Segment(stringResource(R.string.ed_2a_insert), lit = inserting)
            Segment(stringResource(R.string.ed_2a_overwrite), lit = !inserting)
            if (choice == DropChoice.AUTO) {
                Text(stringResource(R.string.ed_2a_auto), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
            }
        }
    }
}

@Composable
private fun Segment(label: String, lit: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (lit) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (lit) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
