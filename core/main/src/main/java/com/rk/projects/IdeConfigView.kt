package com.rk.projects

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.rk.exec.isTerminalInstalled
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.utils.toast
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private const val ISSUE_URL = "https://github.com/GoyDevv/Xed-EditorPRO/issues/new"

/** A pending confirmation/warning: title, message, and the action to run on Continue. */
private class Confirm(val title: String, val message: String, val run: () -> Unit)

/**
 * Full-screen IDE Configuration: shows the toolchain versions installed in the sandbox and lets the
 * user switch the active JDK (and pin an NDK for the current project). Python and build-tools are
 * shown for reference. Missing toolchains link to the Dependency Manager or to reporting an issue.
 */
@Composable
fun IdeConfigView(projectRoot: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAndroid = remember { ProjectTypeDetector.detect(projectRoot) == DetectedProjectType.ANDROID }

    var jdks by remember { mutableStateOf<List<IdeConfig.Ver>>(emptyList()) }
    var ndks by remember { mutableStateOf<List<IdeConfig.Ver>>(emptyList()) }
    var pythons by remember { mutableStateOf<List<IdeConfig.Ver>>(emptyList()) }
    var buildTools by remember { mutableStateOf<List<IdeConfig.Ver>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var terminalReady by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    LaunchedEffect(refreshKey) {
        loading = true
        terminalReady = isTerminalInstalled()
        if (terminalReady) {
            jdks = IdeConfig.jdks()
            pythons = IdeConfig.pythons()
            if (isAndroid) {
                ndks = IdeConfig.ndks(projectRoot)
                buildTools = IdeConfig.buildTools()
            }
        }
        loading = false
    }

    fun openDeps() {
        onDismiss()
        DependencyManagerState.open(projectRoot)
    }
    fun reportIssue() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ISSUE_URL.toUri())) }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("IDE Configuration", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(enabled = !loading && !busy, onClick = { refreshKey++ }) {
                        Icon(painterResource(drawables.refresh), contentDescription = "Re-check")
                    }
                    IconButton(enabled = !busy, onClick = onDismiss) {
                        Icon(painterResource(drawables.close), contentDescription = stringResource(strings.close))
                    }
                }
                HorizontalDivider()

                when {
                    loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        }
                    !terminalReady ->
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(strings.tools_no_terminal), color = MaterialTheme.colorScheme.error)
                        }
                    else ->
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            ConfigSection(
                                title = "Java (JDK)",
                                versions = jdks,
                                actionLabel = "Use",
                                enabled = !busy,
                                onAction = { ver ->
                                    confirm = Confirm(
                                        "Switch JDK?",
                                        "Set the active Java (JDK) to ${ver.label}?\n\nThis changes the default JDK for every build in the sandbox, not just this project.",
                                    ) {
                                        busy = true
                                        scope.launch {
                                            val ok = IdeConfig.setJdk(ver.path)
                                            toast(if (ok) "Active JDK: ${ver.label}" else "Couldn't switch JDK")
                                            refreshKey++
                                            busy = false
                                        }
                                    }
                                },
                                onInstall = ::openDeps,
                                onReport = ::reportIssue,
                            )

                            if (isAndroid) {
                                ConfigSection(
                                    title = "Android NDK",
                                    versions = ndks,
                                    actionLabel = "Use for project",
                                    enabled = !busy,
                                    onAction = { ver ->
                                        confirm = Confirm(
                                            "Use this NDK?",
                                            "Pin NDK ${ver.label} to this project?\n\nThis writes ndk.dir into the project's local.properties so builds use this version.",
                                        ) {
                                            busy = true
                                            scope.launch {
                                                val ok = IdeConfig.setProjectNdk(projectRoot, ver.label)
                                                toast(if (ok) "Project NDK: ${ver.label}" else "Couldn't set NDK")
                                                refreshKey++
                                                busy = false
                                            }
                                        }
                                    },
                                    onInstall = ::openDeps,
                                    onReport = ::reportIssue,
                                )
                                ConfigSection(
                                    title = "Android build-tools",
                                    versions = buildTools,
                                    actionLabel = null,
                                    enabled = !busy,
                                    onAction = null,
                                    onInstall = ::openDeps,
                                    onReport = ::reportIssue,
                                )
                            }

                            ConfigSection(
                                title = "Python",
                                versions = pythons,
                                actionLabel = null,
                                enabled = !busy,
                                onAction = null,
                                onInstall = ::openDeps,
                                onReport = ::reportIssue,
                                note = "Tip: use a per-project virtualenv to pick a Python version safely.",
                            )
                        }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.message) },
            confirmButton = {
                TextButton(onClick = {
                    val run = c.run
                    confirm = null
                    run()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(strings.cancel)) } },
        )
    }
}

@Composable
private fun ConfigSection(
    title: String,
    versions: List<IdeConfig.Ver>,
    actionLabel: String?,
    enabled: Boolean,
    onAction: ((IdeConfig.Ver) -> Unit)?,
    onInstall: () -> Unit,
    onReport: () -> Unit,
    note: String? = null,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    note?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (versions.isEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Not installed", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "It isn't set up in your sandbox. Install it, or report an issue if it should be there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    OutlinedButton(enabled = enabled, onClick = onInstall) { Text("Install") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onReport) { Text("Report issue") }
                }
            }
        }
        return
    }
    versions.forEach { ver ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ver.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                ver.active -> Text("Active", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50))
                actionLabel != null && onAction != null ->
                    OutlinedButton(enabled = enabled, onClick = { onAction(ver) }) { Text(actionLabel) }
            }
        }
    }
}
