package com.rk.activities.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect

/**
 * Branded launch animation: "XED PRO" fades in while rising from below and typing out character by
 * character, holds briefly, then zooms toward the viewer and fades away — revealing the main UI
 * behind it for a seamless, merged hand-off. Calls [onFinish] once the animation completes.
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val fullText = "XED PRO"
    var shownChars by remember { mutableIntStateOf(0) }

    // enter: fade + slide-up (0 -> 1). scale: final zoom-in. exit: whole-splash fade-out.
    val enter = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val exit = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // 1) Fade in + rise.
        enter.animateTo(1f, tween(durationMillis = 520, easing = LinearOutSlowInEasing))
        // 2) Type the letters bottom-to-top.
        for (i in 1..fullText.length) {
            shownChars = i
            delay(80)
        }
        // 3) Hold.
        delay(360)
        // 4) Zoom in to fill the screen while fading out; main content is revealed behind.
        coroutineScope {
            launch { scale.animateTo(9f, tween(durationMillis = 620, easing = FastOutSlowInEasing)) }
            exit.animateTo(0f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
        }
        onFinish()
    }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = exit.value }
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fullText.take(shownChars),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 44.sp,
            letterSpacing = 4.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.graphicsLayer {
                    alpha = enter.value
                    // Rise from ~28dp below to its resting position.
                    translationY = (1f - enter.value) * 28.dp.toPx()
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}
