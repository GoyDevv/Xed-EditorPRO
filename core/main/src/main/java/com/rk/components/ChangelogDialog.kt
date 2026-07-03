package com.rk.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rk.resources.drawables
import com.rk.settings.Preference

/**
 * The "What's new" content shown once on launch after an update. Bump [VERSION] and refresh
 * [highlights] each release; the per-version key means it re-appears for the new version.
 */
object ChangelogInfo {
    const val VERSION = "4.2.1"

    val highlights =
        listOf(
            "Redesigned launch screen with a loading progress bar and smoother hand-off.",
            "Full redesign of the Dependency Manager and IDE Configuration screens.",
            "Per-project Gradle options: build type, log level and extra flags (--stacktrace, …).",
            "Create Project revamped — a bottom sheet with icon-based templates and a live package name.",
            "New \"Projects & Repositories\" browser in the + menu, with type, size and dates.",
            "Real, buildable Fabric / Forge / Android templates with auto-resolved versions.",
            "Android SDK picker with version names, automatic Gradle sync, and a first-build heads-up.",
            "Import files straight into a folder from the file tree.",
            "The Terminal now opens in the selected project folder.",
        )

    private fun seenKey() = "changelog_seen_$VERSION"

    fun shouldShow(): Boolean = !Preference.getBoolean(seenKey(), false)

    fun markSeen() = Preference.setBoolean(seenKey(), true)
}

/**
 * One-time changelog dialog. Re-appears on every launch until the user ticks "Never show again for
 * this version" and dismisses it.
 */
@Composable
fun ChangelogDialog(onClose: () -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }

    fun close() {
        if (dontShowAgain) ChangelogInfo.markSeen()
        onClose()
    }

    AlertDialog(
        onDismissRequest = { close() },
        icon = { Icon(painterResource(drawables.bolt), contentDescription = null) },
        title = { Text("What's new in ${ChangelogInfo.VERSION}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ChangelogInfo.highlights.forEach { line ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { dontShowAgain = !dontShowAgain }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Never show again for this version", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { close() }) { Text("Got it") } },
    )
}
