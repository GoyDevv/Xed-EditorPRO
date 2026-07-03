package com.rk.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.resources.drawables

/**
 * Shared, professional Material 3 building blocks for the toolchain-related full-screen views
 * (Dependency Manager and IDE Configuration). Centralising them here keeps both screens visually
 * consistent: the same header, section cards, rows, status pills and empty/loading states.
 */

// ---- Semantic colours -------------------------------------------------------------------------

internal object ToolchainColors {
    val SuccessContainer = Color(0x1F4CAF50)
    val Success = Color(0xFF43A047)
}

// ---- Top app bar ------------------------------------------------------------------------------

/**
 * A polished header for the full-screen dialogs: a two-line title/subtitle block on the left and
 * refresh / close actions on the right, sitting on a subtly elevated surface.
 */
@Composable
internal fun ToolchainTopBar(
    title: String,
    subtitle: String?,
    refreshEnabled: Boolean,
    closeEnabled: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    subtitleIcon: Int? = null,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (subtitleIcon != null) {
                            Icon(
                                painter = painterResource(subtitleIcon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(enabled = refreshEnabled, onClick = onRefresh) {
                Icon(painterResource(drawables.refresh), contentDescription = "Re-check")
            }
            IconButton(enabled = closeEnabled, onClick = onClose) {
                Icon(painterResource(drawables.close), contentDescription = "Close")
            }
        }
    }
}

// ---- Section card -----------------------------------------------------------------------------

/**
 * A titled group: an icon + coloured title (plus optional caption) followed by a rounded, tonally
 * elevated card that hosts [content] rows.
 */
@Composable
internal fun ToolchainSection(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingBadge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailingBadge?.invoke()
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

// ---- Row --------------------------------------------------------------------------------------

/** A single item inside a [ToolchainSection]: optional leading icon, title/description, trailing widget. */
@Composable
internal fun ToolchainRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    description: String? = null,
    monospaceTitle: Boolean = false,
    titleBadge: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            val titleStyle =
                if (monospaceTitle) MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                else MaterialTheme.typography.bodyLarge
            if (titleBadge != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    titleBadge()
                }
            } else {
                Text(text = title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

// ---- Status pill ------------------------------------------------------------------------------

internal enum class PillTone {
    SUCCESS,
    ERROR,
    INFO,
    PROGRESS,
    NEUTRAL,
}

/** A compact, tonal status chip (e.g. "Installed", "Failed", "Queued", "Installing"). */
@Composable
internal fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier, showSpinner: Boolean = false) {
    val container: Color
    val content: Color
    when (tone) {
        PillTone.SUCCESS -> {
            container = ToolchainColors.SuccessContainer
            content = ToolchainColors.Success
        }
        PillTone.ERROR -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        PillTone.INFO -> {
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.onSecondaryContainer
        }
        PillTone.PROGRESS -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        PillTone.NEUTRAL -> {
            container = MaterialTheme.colorScheme.surfaceContainerHighest
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSpinner) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = content)
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- Full-screen states -----------------------------------------------------------------------

/** Centered spinner + caption used while detecting / loading. */
@Composable
internal fun ToolchainLoading(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Centered icon + message used for terminal-missing / empty states. */
@Composable
internal fun ToolchainMessage(icon: Int, message: String, tint: Color = Color.Unspecified) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint,
            )
        }
    }
}

// ---- Icon mapping -----------------------------------------------------------------------------

/** Best-effort logo/icon for a detected project type. */
internal fun iconForProjectType(type: DetectedProjectType): Int =
    when (type) {
        DetectedProjectType.FABRIC_MOD,
        DetectedProjectType.FORGE_MOD -> drawables.java
        DetectedProjectType.ANDROID -> drawables.android
        DetectedProjectType.GRADLE -> drawables.gradle
        DetectedProjectType.NODE -> drawables.javascript
        DetectedProjectType.PYTHON -> drawables.python
        DetectedProjectType.WEB -> drawables.html
        DetectedProjectType.RUST -> drawables.rust
        DetectedProjectType.GO -> drawables.golang
        DetectedProjectType.UNKNOWN -> drawables.folder_code
    }

/** Best-effort logo/icon for a named dependency in the catalog. */
internal fun iconForDep(name: String): Int =
    when {
        name.startsWith("JDK") -> drawables.java
        name.startsWith("Git") -> drawables.git
        name.startsWith("Android SDK") -> drawables.android
        name.startsWith("Android NDK") -> drawables.android
        name.startsWith("CMake") -> drawables.cmake
        name.startsWith("Node") -> drawables.javascript
        name.startsWith("Python") || name.startsWith("pipx") -> drawables.python
        name.startsWith("Rust") -> drawables.rust
        name.startsWith("Go") -> drawables.golang
        else -> drawables.extension
    }
