package com.rk.runner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.ClipboardUtils
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.utils.toast

/** Compact monospace style for the full output — tight line spacing so log lines read as one block. */
private val OutputTextStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)

/** Compact monospace style for the collapsed bar's latest line. */
private val LatestLineStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.sp)

/**
 * Floating build/run view shown at the bottom of the editor while a background build runs.
 *
 * Collapsed it's a slim "glass" bar (drag handle + label + live progress + latest output line).
 * Swiping up — or tapping it — springs it open (with a soft bounce) to ~half the screen, revealing
 * the full, selectable/copyable output; swiping down from the handle closes it. While the keyboard
 * is open it docks flush to the bottom above the IME; otherwise it floats with rounded corners. The
 * editor behind it is blurred while expanded (see MainContent). Backed by [RunOutputState].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunOutputView(modifier: Modifier = Modifier) {    if (!RunOutputState.isActive) return

    val expanded = RunOutputState.expanded
    val imeVisible = WindowInsets.isImeVisible

    val configuration = LocalConfiguration.current
    val expandedHeight = (configuration.screenHeightDp * 0.5f).dp
    val contentHeight by
        animateDpAsState(
            targetValue = if (expanded) expandedHeight else 0.dp,
            // Snappy yet smooth with a subtle settle — symmetric for open and close.
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
            label = "runOutputHeight",
        )

    val scrollState = rememberScrollState()
    val hScrollState = rememberScrollState()
    LaunchedEffect(RunOutputState.output, expanded, RunOutputState.autoScroll) {
        if (expanded && RunOutputState.autoScroll) scrollState.animateScrollTo(scrollState.maxValue)
    }

    val floating = !imeVisible
    val corner = if (floating) 20.dp else 0.dp

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = if (floating) 8.dp else 0.dp, vertical = if (floating) 8.dp else 0.dp),
        shape =
            RoundedCornerShape(
                topStart = corner,
                topEnd = corner,
                bottomStart = corner,
                bottomEnd = corner,
            ),
        // Frosted-glass-ish translucent container; sits above the blurred editor when expanded.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { _, dy -> total += dy },
                        onDragEnd = {
                            if (total < -40f) RunOutputState.expanded = true
                            else if (total > 40f) RunOutputState.expanded = false
                        },
                    )
                }
        ) {
            // Drag handle ("____").
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

            Header(expanded)

            if (RunOutputState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                HorizontalDivider()
            }

            Column(modifier = Modifier.fillMaxWidth().height(contentHeight)) {
                if (contentHeight > 0.dp) {
                    SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Text(
                            text = RunOutputState.output,
                            modifier =
                                Modifier.fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .horizontalScroll(hScrollState)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = OutputTextStyle,
                            softWrap = false,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Scroll-lock toggle: when on, the view auto-scrolls to the newest output.
                        TextButton(onClick = { RunOutputState.autoScroll = !RunOutputState.autoScroll }) {
                            Icon(
                                painter = painterResource(drawables.arrow_downward),
                                contentDescription = stringResource(strings.autoscroll),
                                tint =
                                    if (RunOutputState.autoScroll) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (RunOutputState.autoScroll) strings.autoscroll_on else strings.autoscroll_off
                                )
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
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

@Composable
private fun Header(expanded: Boolean) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { RunOutputState.expanded = !RunOutputState.expanded }
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (RunOutputState.isRunning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            val ok = RunOutputState.exitCode == 0
            Icon(
                painter = painterResource(drawables.run),
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
            // Animated latest line (fades between updates).
            AnimatedContent(
                targetState =
                    RunOutputState.latestLine.ifBlank {
                        if (RunOutputState.isRunning) stringResource(strings.running)
                        else stringResource(strings.finished)
                    },
                // New output line slides up while the old one slides out — a smooth streaming feel.
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                },
                label = "latestLine",
            ) { line ->
                Text(
                    text = line,
                    style = LatestLineStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (RunOutputState.isRunning) {
            IconButton(onClick = { RunOutputState.stop() }) {
                Icon(painter = painterResource(drawables.stop), contentDescription = stringResource(strings.stop))
            }
        } else {
            IconButton(onClick = { RunOutputState.dismiss() }) {
                Icon(painter = painterResource(drawables.close), contentDescription = stringResource(strings.dismiss))
            }
        }

        IconButton(onClick = { RunOutputState.expanded = !expanded }) {
            Icon(
                painter = painterResource(if (expanded) drawables.chevron_down else drawables.chevron_up),
                contentDescription = stringResource(if (expanded) strings.collapse else strings.expand),
            )
        }
    }
}
