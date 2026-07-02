package com.rk.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.exec.isTerminalInstalled
import com.rk.resources.drawables
import com.rk.resources.strings
import java.io.File

/**
 * Bottom sheet that gathers everything needed to scaffold a project and emits a [ProjectConfig].
 *
 * All projects are created inside the terminal sandbox home (exec-capable), so the toolchain
 * (gradle/npm/python) can actually run from where the project lives. There is no longer a
 * Documents/XED option: Android shared storage is mounted noexec and ignores Unix permissions, so
 * nothing buildable could run there anyway.
 *
 * @param projectsDir the terminal sandbox home in which the project folder is created.
 * @param onCreate invoked with the validated config when the user taps Create.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProjectDialog(projectsDir: File, onDismiss: () -> Unit, onCreate: (ProjectConfig) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ProjectTemplate.NONE) }
    var modLoader by remember { mutableStateOf(ModLoader.FABRIC) }
    var packageName by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var modId by remember { mutableStateOf("") }
    var modDescription by remember { mutableStateOf("") }
    var modVersion by remember { mutableStateOf("1.0.0") }
    var minecraftVersion by remember { mutableStateOf("") }
    var jdkVersion by remember { mutableStateOf("21") }
    var sdkVersion by remember { mutableStateOf("") }
    var initGit by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Minecraft versions: show fallback immediately, then replace with the live Mojang list.
    var mcVersions by remember { mutableStateOf(MinecraftVersions.FALLBACK) }
    var loadingVersions by remember { mutableStateOf(false) }
    LaunchedEffect(template) {
        if (template == ProjectTemplate.MINECRAFT_MOD) {
            if (minecraftVersion.isBlank()) minecraftVersion = mcVersions.first()
            loadingVersions = true
            mcVersions = MinecraftVersions.fetchReleases()
            if (minecraftVersion !in mcVersions) minecraftVersion = mcVersions.first()
            loadingVersions = false
        }
    }

    // Android SDK API levels: fallback first, then the live list from Google's SDK repository.
    var sdkVersions by remember { mutableStateOf(AndroidSdkVersions.FALLBACK) }
    var loadingSdk by remember { mutableStateOf(false) }
    LaunchedEffect(template) {
        if (template == ProjectTemplate.ANDROID_COMPOSE) {
            if (sdkVersion.isBlank()) sdkVersion = sdkVersions.first()
            loadingSdk = true
            sdkVersions = AndroidSdkVersions.fetchApiLevels()
            if (sdkVersion !in sdkVersions) sdkVersion = sdkVersions.first()
            loadingSdk = false
        }
    }

    val parentDir = projectsDir
    val trimmedName = name.trim()

    // Live detection of the toolchain the chosen template needs (Python/Node/JDK).
    var toolState by remember { mutableStateOf<ToolState>(ToolState.None) }
    LaunchedEffect(template, jdkVersion) {
        val cfg = ProjectConfig(name = "check", template = template, parentDir = parentDir, jdkVersion = jdkVersion)
        val tools = ProjectDependencies.requiredTools(cfg)
        when {
            tools.isEmpty() -> toolState = ToolState.None
            !isTerminalInstalled() -> toolState = ToolState.NoTerminal
            else -> {
                toolState = ToolState.Checking
                val missing = ProjectDependencies.missingTools(tools)
                toolState = if (missing.isEmpty()) ToolState.Ready else ToolState.Missing(missing.map { it.name })
            }
        }
    }

    val nameError: String? =
        when {
            trimmedName.isEmpty() -> null
            trimmedName.contains('/') || trimmedName.contains('\\') || trimmedName == "." || trimmedName == ".." ->
                stringResource(strings.invalid_project_name_err)
            File(parentDir, trimmedName).exists() -> stringResource(strings.project_exists_err)
            else -> null
        }

    val canCreate = trimmedName.isNotEmpty() && nameError == null

    // Live-resolved package/applicationId, recomputed as the name/author/package fields change.
    val resolvedPkg =
        ProjectConfig(
                name = trimmedName.ifBlank { "app" },
                template = template,
                parentDir = parentDir,
                packageName = packageName.trim(),
                author = author.trim(),
            )
            .resolvedPackageName()
    val autoPkg =
        ProjectConfig(
                name = trimmedName.ifBlank { "app" },
                template = template,
                parentDir = parentDir,
                packageName = "",
                author = author.trim(),
            )
            .resolvedPackageName()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---- Header -------------------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(template.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(strings.create_project),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(strings.create_project_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Template chooser ---------------------------------------------------------------
            ToolchainSection(icon = drawables.widgets, title = stringResource(strings.project_template)) {
                ProjectTemplate.entries.forEachIndexed { i, t ->
                    if (i > 0) SheetDivider()
                    ToolchainRow(
                        icon = t.iconRes,
                        title = t.displayName,
                        description = t.description,
                        modifier = Modifier.clickable { template = t },
                        trailing = { RadioButton(selected = template == t, onClick = { template = t }) },
                    )
                }
            }

            // ---- Details ------------------------------------------------------------------------
            FormSectionHeader(icon = drawables.edit, title = "Project details")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(strings.project_name)) },
                leadingIcon = { Icon(painterResource(drawables.folder_code), contentDescription = null) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- Minecraft-specific options -----------------------------------------------------
            if (template.showsMinecraftOptions) {
                FormSectionHeader(icon = drawables.coffee, title = stringResource(strings.mod_loader))
                LabeledDropdown(
                    label = stringResource(strings.mod_loader),
                    selected = modLoader.displayName,
                    options = ModLoader.entries.map { it.displayName },
                    onSelect = { display -> modLoader = ModLoader.entries.first { it.displayName == display } },
                )
                LabeledDropdown(
                    label =
                        stringResource(strings.minecraft_version) +
                            if (loadingVersions) " (${stringResource(strings.loading_versions)})" else "",
                    selected = minecraftVersion.ifBlank { mcVersions.first() },
                    options = mcVersions,
                    onSelect = { minecraftVersion = it },
                )
                OutlinedTextField(
                    value = modId,
                    onValueChange = { modId = it },
                    singleLine = true,
                    label = { Text(stringResource(strings.mod_id)) },
                    placeholder = { Text(trimmedName.lowercase().replace(Regex("[^a-z0-9_]"), "_")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modDescription,
                    onValueChange = { modDescription = it },
                    label = { Text(stringResource(strings.mod_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modVersion,
                    onValueChange = { modVersion = it },
                    singleLine = true,
                    label = { Text(stringResource(strings.mod_version)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Package + author + JDK (Minecraft + Android) -----------------------------------
            if (template.showsPackageName) {
                FormSectionHeader(icon = drawables.folder_code, title = stringResource(strings.package_name))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    singleLine = true,
                    label = { Text(stringResource(strings.package_name)) },
                    // Real-time hint: shows exactly what will be used when left blank.
                    placeholder = { Text(autoPkg) },
                    leadingIcon = { Icon(painterResource(drawables.folder_code), contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    singleLine = true,
                    label = { Text(stringResource(strings.author_name)) },
                    leadingIcon = { Icon(painterResource(drawables.person), contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledDropdown(
                    label = stringResource(strings.jdk_version),
                    selected = jdkVersion,
                    options = listOf("8", "11", "17", "21"),
                    onSelect = { jdkVersion = it },
                )

                // Android compile/target SDK — a live list from Google's SDK repository (newest first).
                if (template == ProjectTemplate.ANDROID_COMPOSE) {
                    LabeledDropdown(
                        label =
                            "Android SDK (API level)" +
                                if (loadingSdk) " (${stringResource(strings.loading_versions)})" else "",
                        selected = "API " + sdkVersion.ifBlank { sdkVersions.first() },
                        options = sdkVersions.map { "API $it" },
                        onSelect = { display -> sdkVersion = display.removePrefix("API ").trim() },
                    )
                }

                // Live preview of the resolved applicationId / package.
                PackagePreview(
                    resolved = resolvedPkg,
                    autoGenerated = packageName.isBlank(),
                    showEmptyHint = packageName.isBlank() && author.isBlank(),
                )
            }

            // ---- Options ------------------------------------------------------------------------
            FormSectionHeader(icon = drawables.settings, title = "Options")
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            painter = painterResource(drawables.git),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(strings.git_init))
                    }
                    Switch(checked = initGit, onCheckedChange = { initGit = it })
                }
            }

            // ---- Status + location --------------------------------------------------------------
            when (val state = toolState) {
                ToolState.None -> {}
                ToolState.Checking ->
                    ToolStatusRow(drawables.info, stringResource(strings.tools_checking), MaterialTheme.colorScheme.onSurfaceVariant)
                ToolState.Ready ->
                    ToolStatusRow(drawables.info, stringResource(strings.tools_installed), MaterialTheme.colorScheme.primary)
                ToolState.NoTerminal ->
                    ToolStatusRow(drawables.cloud_off, stringResource(strings.tools_no_terminal), MaterialTheme.colorScheme.error)
                is ToolState.Missing ->
                    ToolStatusRow(
                        drawables.download,
                        stringResource(strings.tools_missing) + " " + state.names.joinToString(", "),
                        MaterialTheme.colorScheme.tertiary,
                    )
            }
            ToolStatusRow(
                icon = drawables.folder,
                text =
                    stringResource(strings.project_location) + ": " +
                        File(parentDir, trimmedName.ifBlank { "<name>" }).absolutePath,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Actions ------------------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text(stringResource(strings.cancel))
                }
                Button(
                    enabled = canCreate,
                    onClick = {
                        onCreate(
                            ProjectConfig(
                                name = trimmedName,
                                template = template,
                                parentDir = parentDir,
                                packageName = packageName.trim(),
                                author = author.trim(),
                                modLoader = if (template.showsMinecraftOptions) modLoader else null,
                                modId = modId.trim(),
                                modDescription = modDescription.trim(),
                                modVersion = modVersion.trim().ifBlank { "1.0.0" },
                                minecraftVersion = minecraftVersion.trim(),
                                jdkVersion = jdkVersion.trim().ifBlank { "21" },
                                sdkVersion = if (template == ProjectTemplate.ANDROID_COMPOSE) sdkVersion.trim() else "",
                                initGit = initGit,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) {
                    Icon(painterResource(drawables.add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(strings.create))
                }
            }
        }
    }
}

/** A small section header (leading icon + primary-coloured title) used above form fields. */
@Composable
private fun FormSectionHeader(icon: Int, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SheetDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Live-updating preview of the package / applicationId that will be written. */
@Composable
private fun PackagePreview(resolved: String, autoGenerated: Boolean, showEmptyHint: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                text = stringResource(strings.package_name_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = resolved,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = if (autoGenerated) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            if (showEmptyHint) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(strings.package_name_empty_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ToolStatusRow(icon: Int, text: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledDropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** UI state for the live toolchain check shown in the create sheet. */
private sealed interface ToolState {
    data object None : ToolState

    data object Checking : ToolState

    data object Ready : ToolState

    data object NoTerminal : ToolState

    data class Missing(val names: List<String>) : ToolState
}
