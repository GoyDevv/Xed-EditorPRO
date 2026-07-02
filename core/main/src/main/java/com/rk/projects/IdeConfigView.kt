package com.rk.projects

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val projectType = remember { ProjectTypeDetector.detect(projectRoot) }
    val isAndroid = projectType == DetectedProjectType.ANDROID
    val isGradle =
        projectType == DetectedProjectType.ANDROID ||
            projectType == DetectedProjectType.GRADLE ||
            projectType == DetectedProjectType.FABRIC_MOD ||
            projectType == DetectedProjectType.FORGE_MOD

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
                ToolchainTopBar(
                    title = "IDE Configuration",
                    subtitle = "Sandbox toolchains" + if (isAndroid) "  ·  Android" else "",
                    subtitleIcon = drawables.settings,
                    refreshEnabled = !loading && !busy,
                    closeEnabled = !busy,
                    onRefresh = { refreshKey++ },
                    onClose = onDismiss,
                )

                when {
                    loading -> ToolchainLoading("Reading installed toolchains…")
                    !terminalReady ->
                        ToolchainMessage(
                            icon = drawables.cloud_off,
                            message = stringResource(strings.tools_no_terminal),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    else ->
                        Column(
                            modifier =
                                Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                        ) {
                            if (isGradle) {
                                GradleSettings(root = projectRoot, isAndroid = isAndroid, enabled = !busy)
                            }

                            ConfigSection(
                                icon = drawables.java,
                                title = "Java (JDK)",
                                subtitle = "The active JDK is used for every build in the sandbox.",
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
                                    icon = drawables.android,
                                    title = "Android NDK",
                                    subtitle = "Pin a version to this project via local.properties.",
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
                                    icon = drawables.build,
                                    title = "Android build-tools",
                                    subtitle = "Installed build-tools (managed by the SDK).",
                                    versions = buildTools,
                                    actionLabel = null,
                                    enabled = !busy,
                                    onAction = null,
                                    onInstall = ::openDeps,
                                    onReport = ::reportIssue,
                                )
                            }

                            ConfigSection(
                                icon = drawables.python,
                                title = "Python",
                                subtitle = "Tip: use a per-project virtualenv to pick a Python version safely.",
                                versions = pythons,
                                actionLabel = null,
                                enabled = !busy,
                                onAction = null,
                                onInstall = ::openDeps,
                                onReport = ::reportIssue,
                            )
                        }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(painterResource(drawables.settings), contentDescription = null) },
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
    icon: Int,
    title: String,
    subtitle: String?,
    versions: List<IdeConfig.Ver>,
    actionLabel: String?,
    enabled: Boolean,
    onAction: ((IdeConfig.Ver) -> Unit)?,
    onInstall: () -> Unit,
    onReport: () -> Unit,
) {
    ToolchainSection(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailingBadge = if (versions.isNotEmpty()) ({ StatusPill("${versions.size}", PillTone.NEUTRAL) }) else null,
    ) {
        if (versions.isEmpty()) {
            NotInstalledContent(enabled = enabled, onInstall = onInstall, onReport = onReport)
        } else {
            versions.forEachIndexed { i, ver ->
                if (i > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                ToolchainRow(
                    title = ver.label,
                    monospaceTitle = true,
                    trailing = {
                        when {
                            ver.active -> StatusPill("Active", PillTone.SUCCESS)
                            actionLabel != null && onAction != null ->
                                OutlinedButton(enabled = enabled, onClick = { onAction(ver) }) { Text(actionLabel) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NotInstalledContent(enabled: Boolean, onInstall: () -> Unit, onReport: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(drawables.info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Not installed", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "It isn't set up in your sandbox. Install it, or report an issue if it should be there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = enabled, onClick = onInstall) {
                Icon(painterResource(drawables.download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Install")
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onReport) { Text("Report issue") }
        }
    }
}

/**
 * Per-project Gradle build options: Debug/Release build type, log level (default Info) and any
 * number of extra flags. Every change is saved immediately (per project) via [GradleConfig] and is
 * picked up by the next Run/Sync. A live preview shows the exact command the Run button will use.
 */
@Composable
private fun GradleSettings(root: File, isAndroid: Boolean, enabled: Boolean) {
    var buildType by remember { mutableStateOf(GradleConfig.buildType(root)) }
    var logLevel by remember { mutableStateOf(GradleConfig.logLevel(root)) }
    val flags = remember {
        mutableStateMapOf<String, Boolean>().apply {
            GradleConfig.ADDITIONAL_FLAGS.forEach { put(it.arg, GradleConfig.isFlagEnabled(root, it.arg)) }
        }
    }

    ToolchainSection(
        icon = drawables.build,
        title = "Gradle build type",
        subtitle = "Applied to this project's builds — Android picks assembleDebug / assembleRelease.",
    ) {
        GradleConfig.BuildType.values().forEachIndexed { i, type ->
            if (i > 0) ConfigDivider()
            ToolchainRow(
                title = type.label,
                description = type.description,
                modifier =
                    Modifier.clickable(enabled = enabled) {
                        buildType = type
                        GradleConfig.setBuildType(root, type)
                    },
                trailing = {
                    RadioButton(
                        selected = buildType == type,
                        enabled = enabled,
                        onClick = {
                            buildType = type
                            GradleConfig.setBuildType(root, type)
                        },
                    )
                },
            )
        }
    }

    ToolchainSection(
        icon = drawables.info,
        title = "Gradle log level",
        subtitle = "Verbosity of the build output. Default: Info.",
    ) {
        GradleConfig.LogLevel.values().forEachIndexed { i, level ->
            if (i > 0) ConfigDivider()
            ToolchainRow(
                title = level.label,
                description = level.description,
                modifier =
                    Modifier.clickable(enabled = enabled) {
                        logLevel = level
                        GradleConfig.setLogLevel(root, level)
                    },
                trailing = {
                    RadioButton(
                        selected = logLevel == level,
                        enabled = enabled,
                        onClick = {
                            logLevel = level
                            GradleConfig.setLogLevel(root, level)
                        },
                    )
                },
            )
        }
    }

    ToolchainSection(
        icon = drawables.terminal,
        title = "Gradle additional flags",
        subtitle = "Extra options appended to every Gradle build for this project. Select any number.",
    ) {
        GradleConfig.ADDITIONAL_FLAGS.forEachIndexed { i, flag ->
            if (i > 0) ConfigDivider()
            val checked = flags[flag.arg] == true
            ToolchainRow(
                title = flag.label,
                description = "${flag.arg} — ${flag.description}",
                modifier =
                    Modifier.clickable(enabled = enabled) {
                        val next = !checked
                        flags[flag.arg] = next
                        GradleConfig.setFlag(root, flag.arg, next)
                    },
                trailing = {
                    Checkbox(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = {
                            flags[flag.arg] = it
                            GradleConfig.setFlag(root, flag.arg, it)
                        },
                    )
                },
            )
        }
    }

    // Live preview of the exact command the Run button will execute for this project.
    val task =
        when {
            isAndroid && buildType == GradleConfig.BuildType.RELEASE -> "assembleRelease"
            isAndroid -> "assembleDebug"
            else -> "build"
        }
    val extra =
        buildString {
            if (logLevel.arg.isNotEmpty()) append(logLevel.arg).append(' ')
            GradleConfig.ADDITIONAL_FLAGS.forEach { if (flags[it.arg] == true) append(it.arg).append(' ') }
        }
            .trim()
    GradleCommandPreview("./gradlew $task" + if (extra.isNotEmpty()) " $extra" else "")
}

@Composable
private fun ConfigDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun GradleCommandPreview(command: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(drawables.command_palette),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Effective command",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
