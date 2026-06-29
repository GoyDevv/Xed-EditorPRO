package com.rk.terminal

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.utils.openUrl
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * Full-screen overlay shown on top of the terminal while Auto Setup runs. The terminal keeps
 * working behind the scrim; the user sees a clean live progress bar instead of raw output.
 *
 *  - Running → progress bar + current step.
 *  - Done    → success message + 5s countdown, then the app is closed so the user can restart.
 *  - Error   → message + "Report on GitHub" (prefilled issue) and a Close button.
 */
@Composable
fun AutoSetupOverlay(activity: Activity) {
    Box(
        modifier =
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (AutoSetupState.phase) {
                    AutoSetupPhase.Running,
                    AutoSetupPhase.Idle -> RunningContent()
                    AutoSetupPhase.Done -> DoneContent(activity)
                    AutoSetupPhase.Error -> ErrorContent(activity)
                }
            }
        }
    }
}

@Composable
private fun RunningContent() {
    Text(
        text = stringResource(strings.auto_setup_running_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    val status = AutoSetupState.status.ifBlank { stringResource(strings.running) }
    Text(
        text = status,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { AutoSetupState.progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "${(AutoSetupState.progress * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(strings.auto_setup_running_hint),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DoneContent(activity: Activity) {
    var secondsLeft by remember { mutableIntStateOf(5) }

    LaunchedEffect(Unit) {
        // Mark setup complete and stop the terminal session before closing.
        Settings.auto_setup_completed = true
        AutoSetupState.stop()
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        closeApp(activity)
    }

    Text(
        text = stringResource(strings.auto_setup_done_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(strings.auto_setup_done_message),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(strings.auto_setup_closing_in, secondsLeft),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Button(onClick = { closeApp(activity) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(strings.auto_setup_close))
    }
}

@Composable
private fun ErrorContent(activity: Activity) {
    Text(
        text = stringResource(strings.auto_setup_error_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = stringResource(strings.auto_setup_error_message),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            modifier = Modifier.weight(1f),
            onClick = {
                AutoSetupState.stop()
                AutoSetupState.reset()
                activity.finish()
            },
        ) {
            Text(stringResource(strings.auto_setup_close), maxLines = 1)
        }
        Button(
            modifier = Modifier.weight(1f),
            onClick = { activity.openUrl(AutoSetup.issueUrl(AutoSetupState.log)) },
        ) {
            Text(stringResource(strings.auto_setup_report), maxLines = 1)
        }
    }
}

/** Fully close the app (finishes the whole task and kills the process) so the user restarts fresh. */
private fun closeApp(activity: Activity) {
    AutoSetupState.reset()
    runCatching { activity.finishAffinity() }
    exitProcess(0)
}
