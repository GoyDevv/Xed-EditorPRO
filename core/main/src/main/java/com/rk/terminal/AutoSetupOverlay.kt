package com.rk.terminal

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.utils.openUrl
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * Full-screen Auto Setup progress screen, shown over the terminal (which runs silently behind it).
 * Shows the current phase, a step description, an end-to-end progress bar, and a live tail of the
 * terminal output so the user can see it's actually working (not stuck). Done → 5s countdown then
 * the app closes; Error → "Report on GitHub".
 */
@Composable
fun AutoSetupOverlay(activity: Activity) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (AutoSetupState.phase) {
            AutoSetupPhase.Done -> DoneContent(activity)
            AutoSetupPhase.Error -> ErrorContent(activity)
            else -> RunningContent()
        }
    }
}

@Composable
private fun phaseLabel(): String =
    when (AutoSetupState.phase) {
        AutoSetupPhase.Downloading -> "Downloading sandbox"
        AutoSetupPhase.Extracting -> "Preparing sandbox"
        AutoSetupPhase.Installing -> "Installing packages"
        else -> "Preparing"
    }

@Composable
private fun RunningContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(strings.auto_setup_running_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = phaseLabel(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        val pct = (AutoSetupState.progress * 100).toInt()
        LinearProgressIndicator(
            progress = { AutoSetupState.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = AutoSetupState.status.ifBlank { stringResource(strings.running) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = "$pct%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }

        // Live terminal output tail.
        val logScroll = rememberScrollState()
        val tail =
            remember(AutoSetupState.log) {
                AutoSetupState.log.lineSequence().filter { it.isNotBlank() }.toList().takeLast(300).joinToString("\n")
            }
        LaunchedEffect(tail) { logScroll.animateScrollTo(logScroll.maxValue) }
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .padding(10.dp)
        ) {
            Text(
                text = tail.ifBlank { "…" },
                modifier = Modifier.fillMaxSize().verticalScroll(logScroll),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = stringResource(strings.auto_setup_running_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DoneContent(activity: Activity) {
    var secondsLeft by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        Settings.auto_setup_completed = true
        AutoSetupState.stop()
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        closeApp(activity)
    }
    CenteredMessage(
        title = stringResource(strings.auto_setup_done_title),
        message = stringResource(strings.auto_setup_done_message),
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = stringResource(strings.auto_setup_closing_in, secondsLeft),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { closeApp(activity) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(strings.auto_setup_close))
        }
    }
}

@Composable
private fun ErrorContent(activity: Activity) {
    CenteredMessage(
        title = stringResource(strings.auto_setup_error_title),
        message = stringResource(strings.auto_setup_error_message),
        accent = MaterialTheme.colorScheme.error,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    AutoSetupState.stop()
                    stopTerminalService(activity)
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
}

@Composable
private fun CenteredMessage(
    title: String,
    message: String,
    accent: androidx.compose.ui.graphics.Color,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(20.dp))
        actions()
    }
}

/** Stop the terminal foreground service so its notification doesn't linger. */
private fun stopTerminalService(activity: Activity) {
    runCatching {
        activity.startService(Intent(activity, SessionService::class.java).apply { action = "ACTION_EXIT" })
    }
}

/** Fully close the app (stops the service + kills the process) so the user restarts fresh. */
private fun closeApp(activity: Activity) {
    AutoSetupState.reset()
    stopTerminalService(activity)
    runCatching { activity.finishAffinity() }
    exitProcess(0)
}
