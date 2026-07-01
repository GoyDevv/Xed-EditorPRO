package com.rk.ai

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.blankj.utilcode.util.ClipboardUtils
import com.rk.resources.drawables
import com.rk.utils.toast
import kotlinx.coroutines.launch

/**
 * Professional Kiro onboarding dialog. Auto-checks on open, then walks Check → Install → Sign in,
 * with a live log, browser/token sign-in, and copyable errors. Drives [KiroSetup].
 */
@Composable
fun KiroSetupDialog(vm: AiViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tokenMode by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(AiPrefs.getKey(AiProviders.KIRO.id)) }
    var showLog by remember { mutableStateOf(false) }
    val logState = rememberLazyListState()

    // Auto-check when the dialog opens.
    LaunchedEffect(Unit) {
        if (KiroSetup.checkStatus == KiroSetup.Status.PENDING && !KiroSetup.running) {
            KiroSetup.check()
        }
    }
    LaunchedEffect(KiroSetup.log.size) {
        if (KiroSetup.log.isNotEmpty()) runCatching { logState.animateScrollToItem(KiroSetup.log.size - 1) }
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { toast("Couldn't open browser") }
    }

    fun finishConnected() {
        vm.selectProvider(AiProviders.KIRO.id)
        vm.refreshModels()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!KiroSetup.running) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(drawables.bolt), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Connect Kiro")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Use Kiro (Claude models) as your AI agent — connected directly, no separate gateway. " +
                        "Sign in once, then select Kiro as the model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))

                // Connected banner.
                if (KiroSetup.loggedIn) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Connected to Kiro. You're ready to chat.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                }

                // Steps.
                Surface(tonalElevation = 1.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        StepRow(
                            "Check",
                            KiroSetup.checkStatus,
                            KiroSetup.cliVersion?.let { "kiro-cli present ($it)" }
                                ?: if (KiroSetup.checkStatus == KiroSetup.Status.DONE && !KiroSetup.cliPresent) "kiro-cli not installed" else "detecting kiro-cli & login",
                        )
                        StepRow(
                            "Install kiro-cli",
                            KiroSetup.installStatus,
                            if (KiroSetup.installStatus == KiroSetup.Status.SKIPPED) "already installed" else "into the Linux sandbox",
                        )
                        StepRow(
                            "Sign in",
                            KiroSetup.loginStatus,
                            "browser sign-in, or use a token",
                        )
                    }
                }

                if (KiroSetup.phase.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    Text(KiroSetup.phase, style = MaterialTheme.typography.labelLarge)
                }
                if (KiroSetup.running) {
                    Spacer(Modifier.size(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // Sign-in link card (only shown during/after login).
                KiroSetup.loginUrl?.let { url ->
                    Spacer(Modifier.size(10.dp))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Finish sign-in in your browser", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton(onClick = { openUrl(url) }) { Text("Open browser") }
                                TextButton(onClick = { ClipboardUtils.copyText("Kiro login", url); toast("Copied") }) { Text("Copy link") }
                            }
                        }
                    }
                }

                // Error.
                KiroSetup.error?.let { err ->
                    Spacer(Modifier.size(10.dp))
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // Token sign-in.
                if (tokenMode) {
                    Spacer(Modifier.size(10.dp))
                    Text("Token sign-in", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Paste a Kiro refresh token (e.g. from a desktop Kiro login). No local CLI needed — " +
                            "best if the CLI can't run on this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Refresh token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        enabled = token.isNotBlank(),
                        onClick = {
                            KiroSetup.useToken(token)
                            toast("Token saved")
                            finishConnected()
                        },
                    ) {
                        Text("Save token & connect")
                    }
                }

                // Collapsible live log.
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide output" else "Show output") }
                    Spacer(Modifier.weight(1f))
                    if (KiroSetup.log.isNotEmpty()) {
                        TextButton(onClick = { ClipboardUtils.copyText("Kiro setup log", KiroSetup.fullLog()); toast("Log copied") }) {
                            Text("Copy log")
                        }
                    }
                }
                if (showLog) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(150.dp)) {
                        LazyColumn(state = logState, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            items(items = KiroSetup.log) { line ->
                                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                KiroSetup.running -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                KiroSetup.loggedIn -> TextButton(onClick = { finishConnected() }) { Text("Done") }
                else -> {
                    val label = if (KiroSetup.loginStatus == KiroSetup.Status.ERROR || KiroSetup.installStatus == KiroSetup.Status.ERROR) "Retry" else "Connect automatically"
                    TextButton(
                        onClick = {
                            scope.launch {
                                vm.selectProvider(AiProviders.KIRO.id)
                                if (KiroSetup.autoConnect()) finishConnected()
                            }
                        }
                    ) {
                        Text(label)
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { tokenMode = !tokenMode }) { Text("Token") }
                TextButton(enabled = !KiroSetup.running, onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun StepRow(label: String, status: KiroSetup.Status, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color =
                when (status) {
                    KiroSetup.Status.DONE, KiroSetup.Status.SKIPPED -> MaterialTheme.colorScheme.primary
                    KiroSetup.Status.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
            modifier = Modifier.size(22.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    KiroSetup.Status.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    KiroSetup.Status.DONE, KiroSetup.Status.SKIPPED ->
                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    KiroSetup.Status.ERROR ->
                        Text("!", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    KiroSetup.Status.PENDING ->
                        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
