package com.rk.activities.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branded IDE launch screen on a dark backdrop: the "XED PRO" wordmark and a "by GoyDevv" tagline
 * fade/scale in, a loading progress bar fills while the app loads behind us, then the whole screen
 * gently zooms and fades away to reveal the ready UI.
 *
 * The progress bar is gated on [awaitAppIdle]: it can only complete once the app's heavy first
 * composition is done and the main thread is idle again — so the whole animation plays at a steady
 * 60fps with no jank, and the reveal is instant.
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val intro = remember { Animatable(0f) } // fade/scale-in of the wordmark block
    val progress = remember { Animatable(0f) } // loading progress 0..1
    val zoom = remember { Animatable(1f) } // final subtle zoom
    val exit = remember { Animatable(1f) } // whole-screen fade-out

    val bg = Color(0xFF0D1117)
    val fg = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B949E)
    val track = Color(0xFF21262D)

    LaunchedEffect(Unit) {
        launch { intro.animateTo(1f, tween(durationMillis = 560, easing = FastOutSlowInEasing)) }
        // Creep the bar to ~90% while the app loads; hold there until the main thread is idle.
        coroutineScope {
            val creep = launch { runCatching { progress.animateTo(0.9f, tween(durationMillis = 2600, easing = LinearEasing)) } }
            awaitAppIdle()
            creep.cancel()
        }
        // App is ready → complete the bar, brief hold, then hand off.
        progress.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
        delay(240)
        coroutineScope {
            launch { zoom.animateTo(1.08f, tween(durationMillis = 460, easing = FastOutSlowInEasing)) }
            exit.animateTo(0f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
        }
        onFinish()
    }

    val status =
        when {
            progress.value >= 1f -> "Ready"
            progress.value >= 0.75f -> "Almost ready…"
            progress.value >= 0.4f -> "Loading editor…"
            else -> "Preparing workspace…"
        }

    Box(
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }.background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.graphicsLayer {
                    val a = intro.value
                    alpha = a
                    val s = (0.92f + 0.08f * a) * zoom.value
                    scaleX = s
                    scaleY = s
                    translationY = (1f - a) * 20.dp.toPx()
                },
        ) {
            Text(
                text = "XED PRO",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                fontSize = 46.sp,
                letterSpacing = 6.sp,
                color = fg,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "by GoyDevv",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 3.sp,
                color = muted,
            )
            Spacer(Modifier.height(40.dp))
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = fg,
                trackColor = track,
            )
            Spacer(Modifier.height(14.dp))
            Text(text = status, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = muted)
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
