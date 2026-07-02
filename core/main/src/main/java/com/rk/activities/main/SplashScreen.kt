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
 */
@Composable
fun SplashScreen(onReady: () -> Unit = {}, onFinish: () -> Unit) {
    val text = "XED PRO"

    val reveal = remember { Animatable(0f) } // drives the per-letter stagger (0..1 across letters)
    val scale = remember { Animatable(0.82f) } // settle scale, then final zoom
    val exit = remember { Animatable(1f) } // whole-splash fade-out

    val bg = Color(0xFF0D1117)
    val fg = Color(0xFFE6EDF3)

    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(durationMillis = 760, easing = LinearOutSlowInEasing))
        // Intro is on screen — let the (heavy) main UI start composing behind us now.
        onReady()
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        delay(360)
        coroutineScope {
            launch { scale.animateTo(7f, tween(durationMillis = 560, easing = FastOutSlowInEasing)) }
            exit.animateTo(0f, tween(durationMillis = 560, easing = FastOutSlowInEasing))
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
