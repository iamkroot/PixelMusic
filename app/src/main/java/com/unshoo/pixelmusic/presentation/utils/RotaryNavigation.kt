package com.unshoo.pixelmusic.presentation.utils

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller bridging hardware rotary events (rotation, nudging, center click)
 * to Jetpack Compose focus navigation.
 */
class RotaryNavigationController {
    var focusManager: FocusManager? = null

    /**
     * Called when the rotary knob is turned.
     * Prioritizes sequential navigation (Next for clockwise / Previous for counter-clockwise)
     * so that multi-column grids follow natural reading order and horizontal carousels stay
     * within bounds, with fallback to spatial navigation (Down/Up).
     * @param clockwise true if turned clockwise (scroll down / next), false if counter-clockwise (scroll up / previous)
     * @return true if focus movement succeeded
     */
    fun onRotate(clockwise: Boolean): Boolean {
        val fm = focusManager ?: return false
        val sequentialDirection = if (clockwise) FocusDirection.Next else FocusDirection.Previous
        val spatialDirection = if (clockwise) FocusDirection.Down else FocusDirection.Up
        return fm.moveFocus(sequentialDirection) || fm.moveFocus(spatialDirection)
    }

    /**
     * Called when the rotary knob is nudged / tilted in one of 4 directions.
     */
    fun onNudge(keyCode: Int): Boolean {
        val fm = focusManager ?: return false
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
            else -> return false
        }
        return fm.moveFocus(direction)
    }
}

val LocalRotaryController = compositionLocalOf<RotaryNavigationController?> { null }

/**
 * Registers the calling composition's FocusManager as the active focus target
 * for rotary events.
 */
@Composable
fun RegisterRotaryFocusManager() {
    val rotaryController = LocalRotaryController.current ?: return
    val focusManager = LocalFocusManager.current
    DisposableEffect(rotaryController, focusManager) {
        rotaryController.focusManager = focusManager
        onDispose {
            if (rotaryController.focusManager === focusManager) {
                rotaryController.focusManager = null
            }
        }
    }
}

/**
 * Renders an automotive-grade high-contrast focus indicator border when the composable
 * holds input focus. Only attaches the border when focused or animating to prevent idle draw overhead.
 */
fun Modifier.rotaryFocusHighlight(
    shape: Shape,
    interactionSource: MutableInteractionSource,
    borderWidth: Dp = 2.5.dp,
    borderColor: Color? = null
): Modifier = composed {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val activeBorderColor = borderColor ?: MaterialTheme.colorScheme.primary
    val animatedColor by animateColorAsState(
        targetValue = if (isFocused) activeBorderColor else activeBorderColor.copy(alpha = 0f),
        animationSpec = tween(durationMillis = 150),
        label = "rotaryFocusBorder"
    )

    if (isFocused || animatedColor.alpha > 0.01f) {
        this.border(
            width = borderWidth,
            color = animatedColor,
            shape = shape
        )
    } else {
        this
    }
}

/**
 * Convenience modifier to make any custom composable focusable by rotary/keyboard,
 * handle Enter/Center click and long-press key events reliably, and draw a focus ring.
 */
fun Modifier.rotaryFocusableItem(
    shape: Shape,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    borderWidth: Dp = 2.5.dp,
    borderColor: Color? = null,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val context = LocalContext.current
    val longPressTimeout = remember(context) {
        ViewConfiguration.getLongPressTimeout().toLong()
    }
    val coroutineScope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var isLongPressHandled by remember { mutableStateOf(false) }

    this
        .onFocusChanged { focusState ->
            if (!focusState.isFocused) {
                longPressJob?.cancel()
                longPressJob = null
                isLongPressHandled = false
            }
        }
        .rotaryFocusHighlight(
            shape = shape,
            interactionSource = interactionSource,
            borderWidth = borderWidth,
            borderColor = borderColor
        )
        .focusable(
            enabled = enabled,
            interactionSource = interactionSource
        )
        .onKeyEvent { keyEvent ->
            if (!enabled) return@onKeyEvent false

            val isCenterOrEnter = when (keyEvent.key) {
                Key.DirectionCenter,
                Key.Enter,
                Key.NumPadEnter,
                Key.Spacebar -> true
                else -> false
            }

            if (!isCenterOrEnter) return@onKeyEvent false

            when (keyEvent.type) {
                KeyEventType.KeyDown -> {
                    if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                        isLongPressHandled = false
                        longPressJob?.cancel()
                        if (onLongPress != null) {
                            longPressJob = coroutineScope.launch {
                                delay(longPressTimeout)
                                isLongPressHandled = true
                                onLongPress()
                            }
                        }
                    }
                    true // Consume KeyDown so parent container doesn't scroll
                }
                KeyEventType.KeyUp -> {
                    longPressJob?.cancel()
                    longPressJob = null
                    if (isLongPressHandled) {
                        isLongPressHandled = false
                    } else {
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
}
