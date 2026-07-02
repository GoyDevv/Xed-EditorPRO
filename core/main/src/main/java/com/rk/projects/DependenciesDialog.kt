package com.rk.projects

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.rk.exec.ShellUtils
import com.rk.exec.isTerminalInstalled
import com.rk.resources.drawables
import com.rk.resources.strings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DepState { AVAILABLE, INSTALLED }

private data class Dep(val name: String, val summary: String, val detectCmd: String, val installCmd: String)

private class DepRow(val dep: Dep) {
    var state by mutableStateOf(DepState.AVAILABLE)
}

/** A pending install confirmation/warning. */
private class DepConfirm(val title: String, val message: String, val run: () -> Unit)

private val ANDROID_SDK_INSTALL =
    "set -e; " +
        "export ANDROID_HOME=\"${'$'}HOME/android-sdk\"; " +
        "mkdir -p \"${'$'}ANDROID_HOME/cmdline-tools\"; " +
        "apt-get update -y; apt-get install -y wget unzip openjdk-17-jdk; " +
        "cd \"${'$'}ANDROID_HOME/cmdline-tools\"; " +
        "wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O clt.zip; " +
        "unzip -q -o clt.zip; rm -f clt.zip; rm -rf latest; mv cmdline-tools latest; " +
        "SDKM=\"${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
        "yes | \"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --licenses >/dev/null 2>&1 || true; " +
        "PLAT=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'platforms;android-[0-9]+' | sort -V | tail -1); " +
        "[ -z \"${'$'}PLAT\" ] && PLAT='platforms;android-35'; " +
        "BT=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'build-tools;[0-9.]+' | sort -V | tail -1); " +
        "[ -z \"${'$'}BT\" ] && BT='build-tools;35.0.0'; " +
        "echo \"Installing platform-tools, android-34, build-tools 34, ${'$'}PLAT, ${'$'}BT\"; " +
        "\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" \"platform-tools\" \"platforms;android-34\" \"build-tools;34.0.0\" \"${'$'}PLAT\" \"${'$'}BT\""

internal const val NDK_LATEST = "Latest"

internal val NDK_VERSIONS =
    listOf(NDK_LATEST, "27.0.12077973", "26.3.11579264", "26.1.10909125", "25.2.9519653", "23.2.8568313")

private fun ndkInstallCmd(version: String): String {
    val resolve =
        if (version == NDK_LATEST) {
            "PKG=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'ndk;[0-9.]+' | sort -V | tail -1); " +
                "[ -z \"${'$'}PKG\" ] && PKG='ndk;26.3.11579264'; "
        } else {
            "PKG='ndk;$version'; "
        }
    return "set -e; export ANDROID_HOME=\"${'$'}HOME/android-sdk\"; " +
        "SDKM=\"${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
        "[ -x \"${'$'}SDKM\" ] || { echo 'Android SDK command-line tools not found — install \"Android SDK\" first.'; exit 1; }; " +
        "yes | \"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --licenses >/dev/null 2>&1 || true; " +
        resolve +
        "echo \"Installing ${'$'}PKG\"; " +
        "\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" \"${'$'}PKG\""
}

private val CMAKE_INSTALL =
    "set -e; export ANDROID_HOME=\"${'$'}HOME/android-sdk\"; " +
        "SDKM=\"${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
        "[ -x \"${'$'}SDKM\" ] || { echo 'Android SDK command-line tools not found — install \"Android SDK\" first.'; exit 1; }; " +
        "CM=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'cmake;[0-9.]+' | sort -V | tail -1); " +
        "[ -z \"${'$'}CM\" ] && CM='cmake;3.22.1'; " +
        "echo \"Installing ${'$'}CM\"; " +
        "\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" \"${'$'}CM\""

private fun catalogFor(type: DetectedProjectType): List<Dep> {
    fun apt(pkgs: String) = "apt-get update -y && apt-get install -y $pkgs"
    val jdk21 = Dep("JDK 21", "openjdk-21-jdk", "ls -d /usr/lib/jvm/java-21* >/dev/null 2>&1", apt("openjdk-21-jdk"))
    val jdk17 = Dep("JDK 17", "openjdk-17-jdk", "ls -d /usr/lib/jvm/java-17* >/dev/null 2>&1", apt("openjdk-17-jdk"))
    val git = Dep("Git", "git", "command -v git >/dev/null 2>&1", apt("git"))
    return when (type) {
        DetectedProjectType.FABRIC_MOD,
        DetectedProjectType.FORGE_MOD,
        DetectedProjectType.GRADLE -> listOf(jdk21, jdk17, git)
        DetectedProjectType.ANDROID ->
            listOf(
                jdk17,
                jdk21,
                git,
                Dep(
                    "Android SDK",
                    "cmdline-tools · platform-tools · platform + build-tools (large)",
                    "test -x \"${'$'}HOME/android-sdk/platform-tools/adb\"",
                    ANDROID_SDK_INSTALL,
                ),
                Dep(
                    "Android NDK",
                    "Native Development Kit (choose versions below)",
                    "ls \"${'$'}HOME/android-sdk/ndk\"/*/source.properties >/dev/null 2>&1",
                    ndkInstallCmd(NDK_LATEST),
                ),
                Dep(
                    "CMake",
                    "native C/C++ builds",
                    "ls \"${'$'}HOME/android-sdk/cmake\"/*/bin/cmake >/dev/null 2>&1",
                    CMAKE_INSTALL,
                ),
            )
        DetectedProjectType.NODE ->
            listOf(Dep("Node.js & npm", "nodejs npm", "command -v node >/dev/null 2>&1", apt("nodejs npm")))
        DetectedProjectType.PYTHON ->
            listOf(
                Dep(
                    "Python 3",
                    "python3 python3-pip python3-venv",
                    "command -v python3 >/dev/null 2>&1",
                    apt("python3 python3-pip python3-venv"),
                ),
                Dep("pipx", "pipx", "command -v pipx >/dev/null 2>&1", apt("pipx")),
            )
        DetectedProjectType.RUST ->
            listOf(Dep("Rust (cargo)", "rustc cargo", "command -v cargo >/dev/null 2>&1", apt("rustc cargo")))
        DetectedProjectType.GO -> listOf(Dep("Go", "golang-go", "command -v go >/dev/null 2>&1", apt("golang-go")))
        DetectedProjectType.WEB,
        DetectedProjectType.UNKNOWN -> emptyList()
    }
}

/**
 * Full-screen Dependency Manager. Detects the project type, checks (in the sandbox) exactly what is
 * already installed vs what can be installed, offers per-version NDK installs, and streams a live
 * log with overall progress while installing (via the background [DependencyInstallService]).
 */
@Composable
fun DependenciesView(projectRoot: File, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var detecting by remember { mutableStateOf(true) }
    var terminalReady by remember { mutableStateOf(true) }
    var projectType by remember { mutableStateOf(DetectedProjectType.UNKNOWN) }
    val installedNdk = remember { mutableStateListOf<String>() }

    val rows = remember { mutableStateListOf<DepRow>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val busy = DependencyInstaller.running
    var refreshKey by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<DepConfirm?>(null) }

    suspend fun runCheck() {
        detecting = true
        if (!DependencyInstaller.running) DependencyInstaller.status.clear()
        val type = withContext(Dispatchers.IO) { ProjectTypeDetector.detect(projectRoot) }
        projectType = type
        terminalReady = isTerminalInstalled()
        rows.clear()
        rows.addAll(catalogFor(type).map { DepRow(it) })
        if (terminalReady) {
            rows.forEach { row ->
                val installed =
                    withContext(Dispatchers.IO) {
                        ShellUtils.runUbuntu(command = arrayOf("bash", "-lc", row.dep.detectCmd), timeoutSeconds = 15L).exitCode == 0
                    }
                row.state = if (installed) DepState.INSTALLED else DepState.AVAILABLE
            }
            if (type == DetectedProjectType.ANDROID) {
                val out =
                    withContext(Dispatchers.IO) {
                        ShellUtils.runUbuntu(command = arrayOf("bash", "-lc", "ls \"${'$'}HOME/android-sdk/ndk\" 2>/dev/null"), timeoutSeconds = 15L).output
                    }
                installedNdk.clear()
                installedNdk.addAll(out.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        detecting = false
    }

    LaunchedEffect(projectRoot.absolutePath, refreshKey) { runCheck() }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun ensureNotificationsThen(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        action()
    }

    fun installSelected() {
        val sel =
            rows.filter {
                it.dep.name != "Android NDK" &&
                    selected[it.dep.name] == true &&
                    it.state != DepState.INSTALLED &&
                    DependencyInstaller.status[it.dep.name].let { s -> s == null || s == DepInstallStatus.FAILED }
            }
        if (sel.isNotEmpty()) {
            confirm = DepConfirm(
                "Install dependencies?",
                "Install ${sel.size} item(s): ${sel.joinToString(", ") { it.dep.name }}.\n\nThis downloads packages over your connection — it can be large and take a while. It keeps running in the background.",
            ) {
                ensureNotificationsThen {
                    DependencyInstallService.start(context, ArrayList(sel.map { it.dep.name }), ArrayList(sel.map { it.dep.installCmd }))
                }
            }
        }
    }

    fun installNdk(version: String) {
        confirm = DepConfirm(
            "Install NDK $version?",
            "Download and install this NDK version? The NDK is large (often several hundred MB). It keeps running in the background.",
        ) {
            ensureNotificationsThen {
                DependencyInstallService.start(context, arrayListOf("Android NDK $version"), arrayListOf(ndkInstallCmd(version)))
            }
        }
    }

    val hasSelection =
        rows.any {
            it.dep.name != "Android NDK" &&
                selected[it.dep.name] == true &&
                it.state != DepState.INSTALLED &&
                DependencyInstaller.status[it.dep.name].let { s -> s == null || s == DepInstallStatus.FAILED }
        }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(strings.dependencies) + if (projectType != DetectedProjectType.UNKNOWN) "  ·  ${projectType.label}" else "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(enabled = !busy && !detecting, onClick = { refreshKey++ }) {
                        Icon(painterResource(drawables.refresh), contentDescription = "Re-check")
                    }
                    IconButton(enabled = !busy, onClick = onDismiss) {
                        Icon(painterResource(drawables.close), contentDescription = stringResource(strings.close))
                    }
                }
                HorizontalDivider()

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        detecting ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(strings.detecting_project))
                                }
                            }
                        !terminalReady ->
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(strings.tools_no_terminal), color = MaterialTheme.colorScheme.error)
                            }
                        else -> {
                            val installed = rows.filter { it.state == DepState.INSTALLED }
                            val available = rows.filter { it.state != DepState.INSTALLED && it.dep.name != "Android NDK" }
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                                if (rows.isEmpty()) {
                                    Text(stringResource(strings.no_dependencies_needed))
                                }
                                if (installed.isNotEmpty()) {
                                    SectionHeader("Installed")
                                    installed.forEach { InstalledRow(it) }
                                    Spacer(Modifier.height(12.dp))
                                }
                                if (available.isNotEmpty()) {
                                    SectionHeader("Available to install")
                                    available.forEach { AvailableRow(it, selected, busy) }
                                    Spacer(Modifier.height(12.dp))
                                }
                                if (projectType == DetectedProjectType.ANDROID) {
                                    SectionHeader("Android NDK")
                                    Text(
                                        "Install and keep multiple NDK versions side by side. Projects pick the version they need.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    NDK_VERSIONS.forEach { version -> NdkVersionRow(version, installedNdk, busy) { installNdk(version) } }
                                }
                            }
                        }
                    }
                }

                // Bottom install bar + live log
                HorizontalDivider()
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(strings.installing) + if (DependencyInstaller.currentName.isNotBlank()) "  ·  ${DependencyInstaller.currentName}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (DependencyInstaller.downloadInfo.isNotBlank()) {
                                Text(DependencyInstaller.downloadInfo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { DependencyInstaller.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        LiveLog()
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        enabled = !detecting && !busy && terminalReady && hasSelection,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { installSelected() },
                    ) {
                        Text(stringResource(if (busy) strings.installing else strings.download))
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
                }) { Text(stringResource(strings.download)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(strings.cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun InstalledRow(row: DepRow) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.dep.name, style = MaterialTheme.typography.bodyLarge)
            Text(row.dep.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(strings.dep_installed), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50))
    }
}

@Composable
private fun AvailableRow(row: DepRow, selected: MutableMap<String, Boolean>, busy: Boolean) {
    val svc = DependencyInstaller.status[row.dep.name]
    val selectable = !busy && (svc == null || svc == DepInstallStatus.FAILED)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected[row.dep.name] == true, enabled = selectable, onCheckedChange = { selected[row.dep.name] = it })
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.dep.name, style = MaterialTheme.typography.bodyLarge)
            Text(row.dep.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (svc) {
            DepInstallStatus.INSTALLING -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
            DepInstallStatus.DONE -> Text(stringResource(strings.dep_installed), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50))
            DepInstallStatus.FAILED -> Text(stringResource(strings.dep_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            DepInstallStatus.PENDING -> Text(stringResource(strings.dep_queued), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            null -> {}
        }
    }
}

@Composable
private fun NdkVersionRow(version: String, installedNdk: List<String>, busy: Boolean, onInstall: () -> Unit) {
    val isInstalled = version != NDK_LATEST && installedNdk.contains(version)
    val svc = DependencyInstaller.status["Android NDK $version"]
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(if (version == NDK_LATEST) "Latest (newest available)" else version, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            svc == DepInstallStatus.INSTALLING -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
            isInstalled || svc == DepInstallStatus.DONE ->
                Text(stringResource(strings.dep_installed), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50))
            else -> OutlinedButton(enabled = !busy, onClick = onInstall) { Text(stringResource(strings.download)) }
        }
    }
}

@Composable
private fun LiveLog() {
    val scroll = rememberScrollState()
    LaunchedEffect(DependencyInstaller.log.size) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(180.dp)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(10.dp)) {
            DependencyInstaller.log.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (DependencyInstaller.log.isEmpty()) {
                Text(DependencyInstaller.latestLine.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
