package io.github.xiaoaiswitch

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun SettingsMotion(
    page: String,
    parent: (String) -> String?,
    backEnabled: Boolean,
    onBack: () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val state = remember { SeekableTransitionState(page) }
    val transition = rememberTransition(state, label = "settings page")
    val savedPages = rememberSaveableStateHolder()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val previous = parent(page)
    var dragging by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = backEnabled && previous != null) { events ->
        if (previous == null) return@PredictiveBackHandler
        try {
            events.collect { event ->
                if (!dragging) {
                    dragging = true
                    if (state.currentState != page || state.targetState != page) state.snapTo(page)
                }
                state.seekTo(event.progress, previous)
            }
            onBack()
        } finally {
            dragging = false
        }
    }

    LaunchedEffect(page, dragging) {
        if (dragging) return@LaunchedEffect
        if (state.currentState == page && state.targetState != page) {
            // Reverse the seeked transition on cancellation without changing the destination.
            val fraction = state.fraction
            val started = withFrameNanos { it }
            var remaining = fraction
            while (remaining > 0f) {
                remaining = withFrameNanos { frame -> (fraction - (frame - started) / 220_000_000f).coerceAtLeast(0f) }
                state.seekTo(remaining)
            }
            state.snapTo(page)
        } else {
            state.animateTo(page)
        }
    }

    transition.AnimatedContent(modifier = Modifier.fillMaxSize(), transitionSpec = {
        val returning = parent(initialState) == targetState
        val motion = tween<IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
        val fade = tween<Float>(durationMillis = 220, easing = LinearEasing)
        val sign = if (returning) -direction else direction
        (slideInHorizontally(motion) { it / 6 * sign } + fadeIn(fade)) togetherWith
            (slideOutHorizontally(motion) { -it / 6 * sign } + fadeOut(fade))
    }) { screen ->
        savedPages.SaveableStateProvider(screen) {
            Box(
                Modifier.fillMaxSize().then(if (screen == page) Modifier else Modifier.semantics { hideFromAccessibility() }),
            ) { content(screen) }
        }
    }
}
