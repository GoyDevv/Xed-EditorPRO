package com.rk.activities.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branded launch animation on a dark backdrop: each letter of "XED PRO" fades in and rises with a
 * staggered wave, the word springs to its resting scale, holds briefly, then zooms toward the viewer
 * and fades out — revealing the main UI behind it. Uses the app's default font; all motion is driven
 * by [Animatable] + graphicsLayer (GPU, no recomposition) for smooth ~60fps playback.
 *
 * Crucially, the animation only starts **after the whole app has finished loading** behind the
 * (opaque) splash: [awaitAppIdle] waits until the main thread is producing frames smoothly again, so
 * the heavy first composition never competes with the animation. That keeps the launch at a steady
 * 60fps with no jank or theme flashes.
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val text = "XED PRO"

    val reveal = remember { Animatable(0f) } // drives the per-letter stagger (0..1 across letters)
    val scale = remember { Animatable(0.82f) } // settle scale, then final zoom
    val exit = remember { Animatable(1f) } // whole-splash fade-out

    val bg = Color(0xFF0D1117)
    val fg = Color(0xFFE6EDF3)

    LaunchedEffect(Unit) {
        // Let the full app compose and lay out behind us first. We block the animation until the
        // main thread has recovered (a short burst of quick frames), so the motion below runs on an
        // idle thread — no stutter, no visible theme/content flashing through.
        awaitAppIdle()
        reveal.animateTo(1f, tween(durationMillis = 720, easing = LinearOutSlowInEasing))
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        delay(320)
        coroutineScope {
            launch { scale.animateTo(7f, tween(durationMillis = 540, easing = FastOutSlowInEasing)) }
            exit.animateTo(0f, tween(durationMillis = 540, easing = FastOutSlowInEasing))
        }
        onFinish()
    }

    Box(
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }.background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
            val n = text.length
            text.forEachIndexed { i, ch ->
                Text(
                    text = ch.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    letterSpacing = 6.sp,
                    color = fg,
                    modifier =
                        Modifier.graphicsLayer {
                            // Per-letter progress: staggered so letters cascade in left-to-right.
                            val t = (reveal.value * n - i).coerceIn(0f, 1f)
                            alpha = t
                            translationY = (1f - t) * 22.dp.toPx()
                        },
                )
            }
        }
    }
}

/**
 * Suspends until the app behind the splash has finished its heavy first composition — detected by a
 * run of consecutive frames that render quickly (i.e. the main thread is idle again). Capped by
 * [maxFrames] so we always proceed even on very slow starts.
 */
private suspend fun awaitAppIdle(maxFrames: Int = 120, requiredSmooth: Int = 3, thresholdMs: Long = 22) {
    var lastFrame = 0L
    var smooth = 0
    var count = 0
    while (smooth < requiredSmooth && count < maxFrames) {
        val now = withFrameNanos { it }
        if (lastFrame != 0L) {
            val deltaMs = (now - lastFrame) / 1_000_000L
            if (deltaMs in 1..thresholdMs) smooth++ else smooth = 0
        }
        lastFrame = now
        count++
    }
}
