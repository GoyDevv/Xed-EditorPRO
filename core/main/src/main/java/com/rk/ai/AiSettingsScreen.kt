package com.rk.ai

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rk.components.SettingsItem
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.utils.toast
import kotlinx.coroutines.launch

/**
 * Settings screen to manage the AI agent: provider, API key (per provider), custom base URL,
 * default model, and resetting tool permissions. Reads/writes [AiPrefs] directly.
 */
@Composable
fun AiSettingsScreen() {
    val scope = rememberCoroutineScope()

    var providerId by remember { mutableStateOf(AiPrefs.selectedProviderId) }
    val provider = AiProviders.byId(providerId)

    var showProviderPicker by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    // Force recomposition after edits.
    var rev by remember { mutableStateOf(0) }

    PreferenceLayout(label = "AI Agent") {
        @Suppress("UNUSED_EXPRESSION") rev // subscribe so edits below refresh the descriptions

        PreferenceGroup(heading = "Provider") {
            SettingsItem(
                label = "Provider",
                description = provider.label,
                showSwitch = false,
                default = false,
                sideEffect = { showProviderPicker = true },
            )
            if (provider.editableBaseUrl) {
                SettingsItem(
                    label = "Base URL",
                    description = AiPrefs.customBaseUrl.ifBlank { "(not set — e.g. https://host/v1)" },
                    showSwitch = false,
                    default = false,
                    sideEffect = { showBaseUrlDialog = true },
                )
            }
            SettingsItem(
                label = "API key",
                description = if (AiPrefs.hasKey(providerId)) "•••••••• (set)" else "Not set",
                showSwitch = false,
                default = false,
                sideEffect = { showKeyDialog = true },
            )
        }

        PreferenceGroup(heading = "Model") {
            SettingsItem(
                label = "Default model",
                description = AiPrefs.selectedModel.ifBlank { provider.defaultModel.ifBlank { "(auto)" } },
                showSwitch = false,
                default = false,
                sideEffect = { showModelDialog = true },
            )
        }

        PreferenceGroup(heading = "Permissions") {
            SettingsItem(
                label = "Reset tool permissions",
                description = "Ask again before every tool (read/write/run).",
                showSwitch = false,
                default = false,
                sideEffect = {
                    AiTools.names.forEach { AiPrefs.setPolicy(it, AiPrefs.Policy.ASK) }
                    toast("Tool permissions reset")
                },
            )
        }
    }

    if (showProviderPicker) {
        AlertDialog(
            onDismissRequest = { showProviderPicker = false },
            title = { Text("Select provider") },
            text = {
                androidx.compose.foundation.layout.Column {
                    AiProviders.all.forEach { p ->
                        TextButton(
                            onClick = {
                                providerId = p.id
                                AiPrefs.selectedProviderId = p.id
                                rev++
                                showProviderPicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(p.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showProviderPicker = false }) { Text("Close") } },
        )
    }

    if (showKeyDialog) {
        InputDialog(
            title = "API key for ${provider.label}",
            initial = AiPrefs.getKey(providerId),
            label = "API key",
            onVerify = { value, done ->
                scope.launch {
                    runCatching { AiClient.listModels(provider, value.trim()) }
                        .onSuccess {
                            AiPrefs.setKey(providerId, value)
                            rev++
                            toast("Verified — ${it.size} models")
                            done()
                        }
                        .onFailure { toast("Failed: ${it.message}") }
                }
            },
            onSave = { value ->
                AiPrefs.setKey(providerId, value)
                rev++
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }

    if (showBaseUrlDialog) {
        InputDialog(
            title = "Base URL",
            initial = AiPrefs.customBaseUrl,
            label = "https://host/v1",
            onSave = {
                AiPrefs.customBaseUrl = it
                rev++
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false },
        )
    }

    if (showModelDialog) {
        InputDialog(
            title = "Default model",
            initial = AiPrefs.selectedModel,
            label = "model id (e.g. gpt-4o-mini)",
            onSave = {
                AiPrefs.selectedModel = it.trim()
                rev++
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false },
        )
    }
}

@Composable
private fun InputDialog(
    title: String,
    initial: String,
    label: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onVerify: ((String, done: () -> Unit) -> Unit)? = null,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (onVerify != null) {
                    TextButton(onClick = { onVerify(value) { onDismiss() } }) { Text("Verify & save") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
