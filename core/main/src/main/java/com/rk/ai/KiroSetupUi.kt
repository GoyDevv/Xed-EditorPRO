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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.blankj.utilcode.util.ClipboardUtils
import com.rk.utils.toast
import kotlinx.coroutines.launch

/**
 * Automatic Kiro setup dialog: installs kiro-cli + logs in, with a step list, a live output view,
 * a progress bar, browser/token login, and a copyable log/error. Drives [KiroSetup].
 */
@Composable
fun KiroSetupDialog(vm: AiViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tokenMode by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(AiPrefs.getKey(AiProviders.KIRO.id)) }
    val logState = rememberLazyListState()

    LaunchedEffect(KiroSetup.log.size) {
        if (KiroSetup.log.isNotEmpty()) logState.animateScrollToItem(KiroSetup.log.size - 1)
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { toast("Couldn't open browser") }
    }

    AlertDialog(
        onDismissRequest = { if (!KiroSetup.running) onDismiss() },
        title = { Text("Set up Kiro") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                StepRow("1. Install kiro-cli", KiroSetup.installStatus)
                StepRow("2. Sign in", KiroSetup.loginStatus)
                Spacer(Modifier.size(8.dp))

                if (KiroSetup.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(4.dp))
                    Text(KiroSetup.phase, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.size(8.dp))
                }

                // Live output (small scrollable view).
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                ) {
                    SelectionContainer {
                        LazyColumn(state = logState, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            if (KiroSetup.log.isEmpty()) {
                                item {
                                    Text(
                                        "This installs kiro-cli in the Linux sandbox and signs you in. " +
                                            "Output appears here.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(items = KiroSetup.log) { line ->
                                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                KiroSetup.loginUrl?.let { url ->
                    Spacer(Modifier.size(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Sign-in link ready",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { openUrl(url) }) { Text("Open browser") }
                            TextButton(onClick = { ClipboardUtils.copyText("Kiro login", url); toast("Copied") }) {
                                Text("Copy link")
                            }
                        }
                    }
                }

                KiroSetup.error?.let { err ->
                    Spacer(Modifier.size(8.dp))
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (tokenMode) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Kiro refresh token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        enabled = token.isNotBlank(),
                        onClick = {
                            KiroSetup.useToken(token)
                            vm.selectProvider(AiProviders.KIRO.id)
                            vm.refreshModels()
                            toast("Token saved")
                            onDismiss()
                        },
                    ) {
                        Text("Save token & finish")
                    }
                }
            }
        },
        confirmButton = {
            if (!KiroSetup.running) {
                val label =
                    when {
                        KiroSetup.installStatus == KiroSetup.Status.DONE && KiroSetup.loginStatus == KiroSetup.Status.ERROR ->
                            "Retry sign-in"
                        KiroSetup.installStatus == KiroSetup.Status.DONE -> "Sign in"
                        else -> "Install & sign in"
                    }
                TextButton(
                    onClick = {
                        scope.launch {
                            vm.selectProvider(AiProviders.KIRO.id)
                            val installed =
                                if (KiroSetup.installStatus == KiroSetup.Status.DONE) true else KiroSetup.runInstall()
                            if (installed) {
                                val ok = KiroSetup.runLogin()
                                if (ok) {
                                    vm.refreshModels()
                                    onDismiss()
                                }
                            }
                        }
                    }
                ) {
                    Text(label)
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { tokenMode = !tokenMode }) { Text("Use token") }
                TextButton(
                    onClick = {
                        ClipboardUtils.copyText("Kiro setup log", KiroSetup.fullLog())
                        toast("Log copied")
                    }
                ) {
                    Text("Copy log")
                }
                TextButton(enabled = !KiroSetup.running, onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun StepRow(label: String, status: KiroSetup.Status) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        when (status) {
            KiroSetup.Status.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            KiroSetup.Status.DONE ->
                Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            KiroSetup.Status.ERROR ->
                Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            KiroSetup.Status.PENDING ->
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (status == KiroSetup.Status.PENDING) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}
