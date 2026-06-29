package com.rk.projects

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rk.exec.ShellUtils
import com.rk.exec.isTerminalInstalled
import com.rk.resources.strings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Detection-only state for a tool (whether it's already present). */
private enum class DepState {
    AVAILABLE,
    INSTALLED,
}

/** A downloadable tool: a display summary, a shell detection command, and a full install command. */
private data class Dep(val name: String, val summary: String, val detectCmd: String, val installCmd: String)

private class DepRow(val dep: Dep) {
    var state by mutableStateOf(DepState.AVAILABLE)
}

/**
 * Best-effort Android SDK install. Installs cmdline-tools, platform-tools, the API 34 platform +
 * build-tools 34 (the template's known-good pin so new projects build first try), AND the latest
 * available platform + build-tools resolved live from sdkmanager — so the SDK is never hard-locked
 * to an old version as Google ships new ones.
 */
private val ANDROID_SDK_INSTALL =
    "set -e; " +
        "export ANDROID_HOME=\"${'$'}HOME/android-sdk\"; " +
        "mkdir -p \"${'$'}ANDROID_HOME/cmdline-tools\"; " +
        "apt-get update -y; apt-get install -y wget unzip openjdk-17-jdk; " +
        "cd \"${'$'}ANDROID_HOME/cmdline-tools\"; " +
        "wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O clt.zip; " +
        "unzip -q -o clt.zip; rm -f clt.zip; rm -rf latest; mv cmdline-tools latest; " +
        "SDKM=\"${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
        "yes | \"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --licenses >/dev/null 2>&1 || true; " +
        "PLAT=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'platforms;android-[0-9]+' | sort -V | tail -1); " +
        "[ -z \"${'$'}PLAT\" ] && PLAT='platforms;android-35'; " +
        "BT=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'build-tools;[0-9.]+' | sort -V | tail -1); " +
        "[ -z \"${'$'}BT\" ] && BT='build-tools;35.0.0'; " +
        "echo \"Installing platform-tools, android-34, build-tools 34, ${'$'}PLAT, ${'$'}BT\"; " +
        "\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" \"platform-tools\" \"platforms;android-34\" \"build-tools;34.0.0\" \"${'$'}PLAT\" \"${'$'}BT\""

/** Special dropdown value meaning "resolve and install the newest NDK available". */
internal const val NDK_LATEST = "Latest"

/** Available NDK choices offered in the dropdown. "Latest" resolves dynamically (never hard-locked). */
internal val NDK_VERSIONS =
    listOf(NDK_LATEST, "27.0.12077973", "26.3.11579264", "26.1.10909125", "25.2.9519653", "23.2.8568313")

/** sdkmanager-based install for an NDK version, or the newest available when [version] is [NDK_LATEST]. */
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

/** Installs the newest CMake available (resolved live), falling back to 3.22.1. */
private val CMAKE_INSTALL =
    "set -e; export ANDROID_HOME=\"${'$'}HOME/android-sdk\"; " +
        "SDKM=\"${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
        "[ -x \"${'$'}SDKM\" ] || { echo 'Android SDK command-line tools not found — install \"Android SDK\" first.'; exit 1; }; " +
        "CM=${'$'}(\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" --list 2>/dev/null | grep -oE 'cmake;[0-9.]+' | sort -V | tail -1); " +
        "[ -z \"${'$'}CM\" ] && CM='cmake;3.22.1'; " +
        "echo \"Installing ${'$'}CM\"; " +
        "\"${'$'}SDKM\" --sdk_root=\"${'$'}ANDROID_HOME\" \"${'$'}CM\""

private fun catalogFor(type: DetectedProjectType): List<Dep> {
    fun apt(pkgs: String) = "apt-get install -y $pkgs"
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
                    "cmdline-tools · platform-tools · android-35/34 · build-tools 35/34 (large)",
                    "test -d \"${'$'}HOME/android-sdk/platform-tools\"",
                    ANDROID_SDK_INSTALL,
                ),
                // installCmd is overridden with the version picked in the dropdown (see doInstall).
                Dep(
                    "Android NDK",
                    "Native Development Kit (choose version below)",
                    "test -d \"${'$'}HOME/android-sdk/ndk\"",
                    ndkInstallCmd(NDK_VERSIONS.first()),
                ),
                Dep(
                    "CMake",
                    "cmake;3.22.1 (native C/C++ builds)",
                    "test -d \"${'$'}HOME/android-sdk/cmake\"",
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
 * Lists the tools a project needs, shows installed vs available, and installs the selected ones via
 * the background [DependencyInstallService] (a foreground service, so it keeps running and shows a
 * progress notification even if the user leaves the app). Notification permission is requested
 * first (Android 13+). The dialog cannot be dismissed while an install is running.
 */
@Composable
fun DependenciesDialog(projectRoot: File, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var detecting by remember { mutableStateOf(true) }
    var typeLabel by remember { mutableStateOf("") }
    var terminalReady by remember { mutableStateOf(true) }
    var projectType by remember { mutableStateOf(DetectedProjectType.UNKNOWN) }
    var ndkVersion by remember { mutableStateOf(NDK_VERSIONS.first()) }

    val rows = remember { mutableStateListOf<DepRow>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    val busy = DependencyInstaller.running

    LaunchedEffect(projectRoot.absolutePath) {
        detecting = true
        if (!DependencyInstaller.running) DependencyInstaller.status.clear()
        val type = withContext(Dispatchers.IO) { ProjectTypeDetector.detect(projectRoot) }
        typeLabel = type.label
        projectType = type
        terminalReady = isTerminalInstalled()
        rows.clear()
        rows.addAll(catalogFor(type).map { DepRow(it) })
        if (terminalReady) {
            rows.forEach { row ->
                val installed =
                    withContext(Dispatchers.IO) {
                        ShellUtils.runUbuntu(command = arrayOf("bash", "-lc", row.dep.detectCmd), timeoutSeconds = 15L)
                            .exitCode == 0
                    }
                row.state = if (installed) DepState.INSTALLED else DepState.AVAILABLE
            }
        }
        detecting = false
    }

    val doInstall: () -> Unit = {
        val sel =
            rows.filter {
                selected[it.dep.name] == true &&
                    it.state != DepState.INSTALLED &&
                    DependencyInstaller.status[it.dep.name].let { s -> s == null || s == DepInstallStatus.FAILED }
            }
        if (sel.isNotEmpty()) {
            val names = ArrayList(sel.map { it.dep.name })
            val commands =
                ArrayList(
                    sel.map { row ->
                        // The NDK row installs the version chosen in the dropdown.
                        if (row.dep.name == "Android NDK") ndkInstallCmd(ndkVersion) else row.dep.installCmd
                    }
                )
            DependencyInstallService.start(context, names, commands)
        }
    }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { doInstall() }

    val onDownloadClicked: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            doInstall()
        }
    }

    val hasSelection =
        rows.any {
            selected[it.dep.name] == true &&
                it.state != DepState.INSTALLED &&
                DependencyInstaller.status[it.dep.name].let { s -> s == null || s == DepInstallStatus.FAILED }
        }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(stringResource(strings.dependencies) + if (typeLabel.isNotBlank()) "  ·  $typeLabel" else "")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    detecting ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(stringResource(strings.detecting_project))
                        }
                    !terminalReady ->
                        Text(stringResource(strings.tools_no_terminal), color = MaterialTheme.colorScheme.error)
                    rows.isEmpty() -> Text(stringResource(strings.no_dependencies_needed))
                    else -> rows.forEach { row -> DepRowItem(row, selected, busy) }
                }

                // NDK version picker (Android only).
                if (!detecting && projectType == DetectedProjectType.ANDROID && terminalReady) {
                    NdkVersionPicker(selected = ndkVersion, enabled = !busy, onSelect = { ndkVersion = it })
                }

                if (busy) {
                    Spacer(Modifier.size(4.dp))
                    LinearProgressIndicator(
                        progress = { DependencyInstaller.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text =
                            stringResource(strings.installing) +
                                if (DependencyInstaller.currentName.isNotBlank()) "  ·  ${DependencyInstaller.currentName}"
                                else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Real-time, single-line output of the running install.
                    Text(
                        text = DependencyInstaller.latestLine.ifBlank { "…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !detecting && !busy && terminalReady && hasSelection,
                onClick = onDownloadClicked,
            ) {
                Text(stringResource(if (busy) strings.installing else strings.download))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(strings.close)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NdkVersionPicker(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(strings.ndk_version)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NDK_VERSIONS.forEach { version ->
                DropdownMenuItem(
                    text = { Text(version) },
                    onClick = {
                        onSelect(version)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DepRowItem(row: DepRow, selected: MutableMap<String, Boolean>, busy: Boolean) {    val svc = DependencyInstaller.status[row.dep.name]
    val selectable = !busy && row.state == DepState.AVAILABLE && (svc == null || svc == DepInstallStatus.FAILED)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = selected[row.dep.name] == true,
            enabled = selectable,
            onCheckedChange = { selected[row.dep.name] = it },
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.dep.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = row.dep.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))

        if (svc == DepInstallStatus.INSTALLING) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            val installed = row.state == DepState.INSTALLED || svc == DepInstallStatus.DONE
            val failed = svc == DepInstallStatus.FAILED
            val queued = svc == DepInstallStatus.PENDING
            Text(
                text =
                    when {
                        installed -> stringResource(strings.dep_installed)
                        failed -> stringResource(strings.dep_failed)
                        queued -> stringResource(strings.dep_queued)
                        else -> stringResource(strings.dep_available)
                    },
                style = MaterialTheme.typography.labelMedium,
                color =
                    when {
                        installed -> Color(0xFF4CAF50)
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
            )
        }
    }
}
