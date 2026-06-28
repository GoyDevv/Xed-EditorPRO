package com.rk.runner

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blankj.utilcode.util.ClipboardUtils
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.utils.toast

/**
 * Floating build/run progress view shown at the bottom of the editor.
 *
 * Collapsed it is a single bar showing the run label, a live progress indicator and the latest
 * output line. Tapping it (or the chevron) smoothly expands it to cover roughly half the screen and
 * reveals the full, scrollable and selectable (copyable) terminal output. The output persists after
 * the process finishes and is replaced only when the next run starts. Backed by [RunOutputState].
 */
@Composable
fun RunOutputView(modifier: Modifier = Modifier) {
    if (!RunOutputState.isActive) return

    var expanded by rememberSaveable { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val expandedHeight = (configuration.screenHeightDp * 0.5f).dp
    val contentHeight by
        animateDpAsState(targetValue = if (expanded) expandedHeight else 0.dp, label = "runOutputHeight")

    val scrollState = rememberScrollState()

    // Keep the expanded view pinned to the newest output as it streams in.
    LaunchedEffect(RunOutputState.output, expanded) {
        if (expanded) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header / collapsed bar
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (RunOutputState.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(drawables.run),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = RunOutputState.label.ifBlank { stringResource(strings.build_output) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            RunOutputState.latestLine.ifBlank {
                                if (RunOutputState.isRunning) stringResource(strings.running)
                                else stringResource(strings.finished)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (RunOutputState.isRunning) {
                    IconButton(onClick = { RunOutputState.stop() }) {
                        Icon(
                            painter = painterResource(drawables.stop),
                            contentDescription = stringResource(strings.stop),
                        )
                    }
                } else {
                    IconButton(onClick = { RunOutputState.dismiss() }) {
                        Icon(
                            painter = painterResource(drawables.close),
                            contentDescription = stringResource(strings.dismiss),
                        )
                    }
                }

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        painter =
                            painterResource(if (expanded) drawables.chevron_down else drawables.chevron_up),
                        contentDescription =
                            stringResource(if (expanded) strings.collapse else strings.expand),
                    )
                }
            }

            if (RunOutputState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                HorizontalDivider()
            }

            // Expandable output area
            Column(modifier = Modifier.fillMaxWidth().height(contentHeight)) {
                if (contentHeight > 0.dp) {
                    SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Text(
                            text = RunOutputState.output,
                            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                ClipboardUtils.copyText("Build output", RunOutputState.output)
                                toast(strings.copied)
                            }
                        ) {
                            Icon(
                                painter = painterResource(drawables.copy),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(strings.copy))
                        }
                    }
                }
            }
        }
    }
}
