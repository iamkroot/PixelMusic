package com.unshoo.pixelmusic.presentation.utils

import android.view.KeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RotaryNavigationControllerTest {

    private lateinit var focusManager: FocusManager
    private lateinit var controller: RotaryNavigationController

    @BeforeEach
    fun setUp() {
        focusManager = mockk(relaxed = true)
        controller = RotaryNavigationController().apply {
            this.focusManager = this@RotaryNavigationControllerTest.focusManager
        }
    }

    @Test
    fun `rotate clockwise moves focus to Next`() {
        every { focusManager.moveFocus(FocusDirection.Next) } returns true

        val handled = controller.onRotate(clockwise = true)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Next) }
    }

    @Test
    fun `rotate clockwise falls back to Down if Next returns false`() {
        every { focusManager.moveFocus(FocusDirection.Next) } returns false
        every { focusManager.moveFocus(FocusDirection.Down) } returns true

        val handled = controller.onRotate(clockwise = true)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Next) }
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Down) }
    }

    @Test
    fun `rotate counter-clockwise moves focus to Previous`() {
        every { focusManager.moveFocus(FocusDirection.Previous) } returns true

        val handled = controller.onRotate(clockwise = false)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Previous) }
    }

    @Test
    fun `rotate counter-clockwise falls back to Up if Previous returns false`() {
        every { focusManager.moveFocus(FocusDirection.Previous) } returns false
        every { focusManager.moveFocus(FocusDirection.Up) } returns true

        val handled = controller.onRotate(clockwise = false)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Previous) }
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Up) }
    }

    @Test
    fun `rotate returns false when at boundary without reversing direction`() {
        every { focusManager.moveFocus(FocusDirection.Previous) } returns false
        every { focusManager.moveFocus(FocusDirection.Up) } returns false

        val handled = controller.onRotate(clockwise = false)

        assertFalse(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Previous) }
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Up) }
        verify(exactly = 0) { focusManager.moveFocus(FocusDirection.Next) }
        verify(exactly = 0) { focusManager.moveFocus(FocusDirection.Down) }
    }

    @Test
    fun `nudge up moves focus to Up`() {
        every { focusManager.moveFocus(FocusDirection.Up) } returns true

        val handled = controller.onNudge(KeyEvent.KEYCODE_DPAD_UP)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Up) }
    }

    @Test
    fun `nudge down moves focus to Down`() {
        every { focusManager.moveFocus(FocusDirection.Down) } returns true

        val handled = controller.onNudge(KeyEvent.KEYCODE_DPAD_DOWN)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Down) }
    }

    @Test
    fun `nudge left moves focus to Left`() {
        every { focusManager.moveFocus(FocusDirection.Left) } returns true

        val handled = controller.onNudge(KeyEvent.KEYCODE_DPAD_LEFT)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Left) }
    }

    @Test
    fun `nudge right moves focus to Right`() {
        every { focusManager.moveFocus(FocusDirection.Right) } returns true

        val handled = controller.onNudge(KeyEvent.KEYCODE_DPAD_RIGHT)

        assertTrue(handled)
        verify(exactly = 1) { focusManager.moveFocus(FocusDirection.Right) }
    }

    @Test
    fun `unsupported nudge keyCode returns false`() {
        val handled = controller.onNudge(KeyEvent.KEYCODE_A)

        assertFalse(handled)
    }

    @Test
    fun `rotate returns false when focusManager is null`() {
        controller.focusManager = null

        val handled = controller.onRotate(clockwise = true)

        assertFalse(handled)
    }
}
