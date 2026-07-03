package com.rk.projects

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.rk.exec.ShellUtils
import com.rk.exec.isTerminalInstalled
import com.rk.resources.drawables
import com.rk.resources.strings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DepState { AVAILABLE, INSTALLED }

private data class Dep(
    val name: String,
    val summary: String,
    val detectCmd: String,
    val installCmd: String,
    val uninstallCmd: String = "",
)

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
    fun aptRm(pkgs: String) = "apt-get remove -y $pkgs; apt-get autoremove -y"
    val jdk21 =
        Dep("JDK 21", "openjdk-21-jdk", "ls -d /usr/lib/jvm/java-21* >/dev/null 2>&1", apt("openjdk-21-jdk"), aptRm("openjdk-21-jdk"))
    val jdk17 =
        Dep("JDK 17", "openjdk-17-jdk", "ls -d /usr/lib/jvm/java-17* >/dev/null 2>&1", apt("openjdk-17-jdk"), aptRm("openjdk-17-jdk"))
    val git = Dep("Git", "git", "command -v git >/dev/null 2>&1", apt("git"), aptRm("git"))
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
                    "rm -rf \"${'$'}HOME/android-sdk\"",
                ),
                Dep(
                    "Android NDK",
                    "Native Development Kit (choose versions below)",
                    "ls \"${'$'}HOME/android-sdk/ndk\"/*/source.properties >/dev/null 2>&1",
                    ndkInstallCmd(NDK_LATEST),
                    "rm -rf \"${'$'}HOME/android-sdk/ndk\"",
                ),
                Dep(
                    "CMake",
                    "native C/C++ builds",
                    "ls \"${'$'}HOME/android-sdk/cmake\"/*/bin/cmake >/dev/null 2>&1",
                    CMAKE_INSTALL,
                    "rm -rf \"${'$'}HOME/android-sdk/cmake\"",
                ),
            )
        DetectedProjectType.NODE ->
            listOf(Dep("Node.js & npm", "nodejs npm", "command -v node >/dev/null 2>&1", apt("nodejs npm"), aptRm("nodejs npm")))
        DetectedProjectType.PYTHON ->
            listOf(
                Dep(
                    "Python 3",
                    "python3 python3-pip python3-venv",
                    "command -v python3 >/dev/null 2>&1",
                    apt("python3 python3-pip python3-venv"),
                    aptRm("python3-pip python3-venv"),
                ),
                Dep("pipx", "pipx", "command -v pipx >/dev/null 2>&1", apt("pipx"), aptRm("pipx")),
            )
        DetectedProjectType.RUST ->
            listOf(Dep("Rust (cargo)", "rustc cargo", "command -v cargo >/dev/null 2>&1", apt("rustc cargo"), aptRm("rustc cargo")))
        DetectedProjectType.GO ->
            listOf(Dep("Go", "golang-go", "command -v go >/dev/null 2>&1", apt("golang-go"), aptRm("golang-go")))
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
    val scope = rememberCoroutineScope()

    var detecting by remember { mutableStateOf(true) }
    var terminalReady by remember { mutableStateOf(true) }
    var projectType by remember { mutableStateOf(DetectedProjectType.UNKNOWN) }
    val installedNdk = remember { mutableStateListOf<String>() }

    val rows = remember { mutableStateListOf<DepRow>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val uninstalling = remember { mutableStateListOf<String>() }
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

    fun selectableRows() =
        rows.filter {
            it.dep.name != "Android NDK" &&
                it.state != DepState.INSTALLED &&
                DependencyInstaller.status[it.dep.name].let { s -> s == null || s == DepInstallStatus.FAILED }
        }

    fun installSelected() {
        val sel = selectableRows().filter { selected[it.dep.name] == true }
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

    fun uninstall(dep: Dep) {
        if (dep.uninstallCmd.isBlank() || uninstalling.contains(dep.name)) return
        confirm =
            DepConfirm(
                "Uninstall ${dep.name}?",
                "This removes ${dep.name} from the sandbox. You can reinstall it any time from here.",
            ) {
                uninstalling.add(dep.name)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ShellUtils.runUbuntu(command = arrayOf("bash", "-lc", dep.uninstallCmd), timeoutSeconds = 300L)
                    }
                    uninstalling.remove(dep.name)
                    refreshKey++
                }
            }
    }

    val selectedCount = selectableRows().count { selected[it.dep.name] == true }
    val hasSelection = selectedCount > 0

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ToolchainTopBar(
                    title = stringResource(strings.dependencies),
                    subtitle = if (projectType != DetectedProjectType.UNKNOWN) projectType.label else null,
                    subtitleIcon = iconForProjectType(projectType),
                    refreshEnabled = !busy && !detecting,
                    closeEnabled = !busy,
                    onRefresh = { refreshKey++ },
                    onClose = onDismiss,
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        detecting -> ToolchainLoading(stringResource(strings.detecting_project))
                        !terminalReady ->
                            ToolchainMessage(
                                icon = drawables.cloud_off,
                                message = stringResource(strings.tools_no_terminal),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        else -> {
                            val installed = rows.filter { it.state == DepState.INSTALLED }
                            val available = rows.filter { it.state != DepState.INSTALLED && it.dep.name != "Android NDK" }
                            Column(
                                modifier =
                                    Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 16.dp),
                            ) {
                                if (rows.isEmpty()) {
                                    EmptyDepsCard()
                                }

                                if (installed.isNotEmpty()) {
                                    ToolchainSection(
                                        icon = drawables.build,
                                        title = "Installed",
                                        subtitle = "Ready to use in the sandbox",
                                        trailingBadge = { StatusPill("${installed.size}", PillTone.SUCCESS) },
                                    ) {
                                        installed.forEachIndexed { i, row ->
                                            if (i > 0) RowDivider()
                                            InstalledRow(row, uninstalling.contains(row.dep.name)) { uninstall(row.dep) }
                                        }
                                    }
                                }

                                if (available.isNotEmpty()) {
                                    ToolchainSection(
                                        icon = drawables.download,
                                        title = "Available to install",
                                        subtitle = "Select the tools you need, then install",
                                    ) {
                                        available.forEachIndexed { i, row ->
                                            if (i > 0) RowDivider()
                                            AvailableRow(row, selected, busy)
                                        }
                                    }
                                }

                                if (projectType == DetectedProjectType.ANDROID) {
                                    ToolchainSection(
                                        icon = drawables.android,
                                        title = "Android NDK",
                                        subtitle = "Install multiple versions side by side — projects pick the one they need.",
                                    ) {
                                        NDK_VERSIONS.forEachIndexed { i, version ->
                                            if (i > 0) RowDivider()
                                            NdkVersionRow(version, installedNdk, busy) { installNdk(version) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom install bar + live log
                HorizontalDivider()
                Surface(tonalElevation = 2.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (busy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(strings.installing) +
                                        if (DependencyInstaller.currentName.isNotBlank()) "  ·  ${DependencyInstaller.currentName}" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (DependencyInstaller.downloadInfo.isNotBlank()) {
                                    Text(
                                        DependencyInstaller.downloadInfo,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { DependencyInstaller.progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            LiveLog()
                            Spacer(Modifier.height(12.dp))
                        }
                        Button(
                            enabled = !detecting && !busy && terminalReady && hasSelection,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            onClick = { installSelected() },
                        ) {
                            Icon(painterResource(drawables.download), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text =
                                    when {
                                        busy -> stringResource(strings.installing)
                                        hasSelection -> "${stringResource(strings.download)}  ($selectedCount)"
                                        else -> stringResource(strings.download)
                                    }
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(painterResource(drawables.download), contentDescription = null) },
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
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun EmptyDepsCard() {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(drawables.info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(stringResource(strings.no_dependencies_needed), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun InstalledRow(row: DepRow, removing: Boolean, onUninstall: () -> Unit) {
    ToolchainRow(
        icon = iconForDep(row.dep.name),
        title = row.dep.name,
        titleBadge = {
            Text(
                text = "• " + stringResource(strings.dep_installed),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = ToolchainColors.Success,
                maxLines = 1,
            )
        },
        description = row.dep.summary,
        trailing = {
            when {
                removing -> StatusPill("Removing…", PillTone.PROGRESS, showSpinner = true)
                row.dep.uninstallCmd.isNotBlank() ->
                    OutlinedButton(onClick = onUninstall) {
                        Icon(painterResource(drawables.close), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Uninstall")
                    }
            }
        },
    )
}

@Composable
private fun AvailableRow(row: DepRow, selected: MutableMap<String, Boolean>, busy: Boolean) {
    val svc = DependencyInstaller.status[row.dep.name]
    val selectable = !busy && (svc == null || svc == DepInstallStatus.FAILED)
    ToolchainRow(
        modifier =
            Modifier.clickable(enabled = selectable) {
                selected[row.dep.name] = !(selected[row.dep.name] == true)
            },
        icon = iconForDep(row.dep.name),
        title = row.dep.name,
        description = row.dep.summary,
        trailing = {
            when (svc) {
                DepInstallStatus.INSTALLING -> StatusPill(stringResource(strings.installing), PillTone.PROGRESS, showSpinner = true)
                DepInstallStatus.DONE -> StatusPill(stringResource(strings.dep_installed), PillTone.SUCCESS)
                DepInstallStatus.FAILED -> StatusPill(stringResource(strings.dep_failed), PillTone.ERROR)
                DepInstallStatus.PENDING -> StatusPill(stringResource(strings.dep_queued), PillTone.INFO)
                null ->
                    Checkbox(
                        checked = selected[row.dep.name] == true,
                        enabled = selectable,
                        onCheckedChange = { selected[row.dep.name] = it },
                    )
            }
        },
    )
}

@Composable
private fun NdkVersionRow(version: String, installedNdk: List<String>, busy: Boolean, onInstall: () -> Unit) {
    val isInstalled = version != NDK_LATEST && installedNdk.contains(version)
    val svc = DependencyInstaller.status["Android NDK $version"]
    ToolchainRow(
        icon = drawables.android,
        title = if (version == NDK_LATEST) "Latest NDK" else "NDK $version",
        titleBadge =
            if (isInstalled || svc == DepInstallStatus.DONE) {
                {
                    Text(
                        text = "• " + stringResource(strings.dep_installed),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = ToolchainColors.Success,
                        maxLines = 1,
                    )
                }
            } else {
                null
            },
        description =
            if (version == NDK_LATEST) "Installs the newest available NDK release" else "Native Development Kit $version",
        trailing = {
            when {
                svc == DepInstallStatus.INSTALLING -> StatusPill(stringResource(strings.installing), PillTone.PROGRESS, showSpinner = true)
                isInstalled || svc == DepInstallStatus.DONE -> {}
                else ->
                    OutlinedButton(enabled = !busy, onClick = onInstall) {
                        Icon(painterResource(drawables.download), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(strings.download))
                    }
            }
        },
    )
}

@Composable
private fun LiveLog() {
    val scroll = rememberScrollState()
    LaunchedEffect(DependencyInstaller.log.size) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().height(180.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
            DependencyInstaller.log.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (DependencyInstaller.log.isEmpty()) {
                Text(
                    DependencyInstaller.latestLine.ifBlank { "…" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
