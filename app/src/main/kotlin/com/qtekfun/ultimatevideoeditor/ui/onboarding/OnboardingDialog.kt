package com.qtekfun.ultimatevideoeditor.ui.onboarding

import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Three dismissible tip cards, shown once on first launch. [onFinished] runs when the user skips or finishes,
 * and the caller then remembers that the tips were seen.
 */
@Composable
fun OnboardingTips(onFinished: () -> Unit) {
    var state by remember { mutableStateOf(OnboardingState()) }
    if (state.finished) {
        onFinished()
        return
    }
    val tip = Tips.all[state.index]
    AlertDialog(
        onDismissRequest = { state = state.dismiss() },
        title = { Text(tip.title.asString()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tip.body.asString(), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .described(stringResource(R.string.ed_s3_tip_of, state.index + 1, state.count)),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.ed_s3_tip_counter, state.index + 1, state.count), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state = state.next() }) { Text(if (state.isLast) stringResource(R.string.ed_2a_done) else stringResource(R.string.ed_s3_next)) }
        },
        dismissButton = {
            Row {
                if (state.index > 0) TextButton(onClick = { state = state.previous() }) { Text(stringResource(R.string.common_back)) }
                TextButton(onClick = { state = state.dismiss() }) { Text(stringResource(R.string.ed_s3_skip)) }
            }
        },
    )
}
