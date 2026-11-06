package com.unshoo.pixelmusic.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CallAudioBypassPolicyTest {

    @Test
    fun computeCallBypassDuckedVolume_attenuatesProportionallyWithoutInversion() {
        // Normal full volume (1.0) -> 0.25
        assertThat(computeCallBypassDuckedVolume(1.0f)).isEqualTo(0.25f)

        // Moderate volume (0.8) -> 0.20
        assertThat(computeCallBypassDuckedVolume(0.8f)).isEqualTo(0.20f)

        // Quiet volume (0.04) -> 0.01 (proportionally quieter, no volume jump)
        assertThat(computeCallBypassDuckedVolume(0.04f)).isEqualTo(0.01f)

        // Zero volume (0.0) -> 0.0
        assertThat(computeCallBypassDuckedVolume(0.0f)).isEqualTo(0.0f)

        // High volume clamped to 0.40 max
        assertThat(computeCallBypassDuckedVolume(2.0f)).isEqualTo(0.25f)
    }

    @Test
    fun shouldBypassTransientAudioFocusLoss_onlyWhenEnabledAndCallActive() {
        // Disabled bypass setting always rejects bypass
        assertThat(
            shouldBypassTransientAudioFocusLoss(
                bypassEnabled = false,
                isNavBypassActive = false,
                isCallActive = true
            )
        ).isFalse()

        // Enabled, but no call active and not currently in bypass
        assertThat(
            shouldBypassTransientAudioFocusLoss(
                bypassEnabled = true,
                isNavBypassActive = false,
                isCallActive = false
            )
        ).isFalse()

        // Enabled and call is detected as active
        assertThat(
            shouldBypassTransientAudioFocusLoss(
                bypassEnabled = true,
                isNavBypassActive = false,
                isCallActive = true
            )
        ).isTrue()

        // Enabled and bypass is already actively routing to Nav
        assertThat(
            shouldBypassTransientAudioFocusLoss(
                bypassEnabled = true,
                isNavBypassActive = true,
                isCallActive = false
            )
        ).isTrue()
    }
}
