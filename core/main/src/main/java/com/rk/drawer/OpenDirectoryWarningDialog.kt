package com.rk.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import com.rk.settings.Settings
import kotlinx.coroutines.delay

/**
 * Caution shown before the system folder picker. Explains that on Android 11+ manually-opened
 * directories hit permission errors in the terminal and that such issues aren't supported. The OK
 * button is locked for 30 seconds (with a live countdown) so the user actually reads it; there's a
 * Cancel button and a "Never show this again" toggle.
 */
@Composable
fun OpenDirectoryWarningDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    var neverAgain by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(30) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    AlertDialog(
        onDismissRequest = {}, // force an explicit OK/Cancel choice
        title = { Text(stringResource(strings.open_dir_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(strings.open_dir_warning_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverAgain, onCheckedChange = { neverAgain = it })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(strings.never_show_again), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = secondsLeft == 0,
                onClick = {
                    if (neverAgain) Settings.open_dir_warning_dismissed = true
                    onConfirm()
                },
            ) {
                Text(
                    if (secondsLeft > 0) stringResource(strings.open_dir_warning_ok_timer, secondsLeft)
                    else stringResource(strings.ok)
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(strings.cancel)) } },
    )
}
