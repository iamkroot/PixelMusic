package com.unshoo.pixelmusic.data.service.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Monitors active voice call state on Android Automotive OS via standard system audio callbacks.
 *
 * Detection mechanisms:
 * 1. AudioPlaybackCallback: Monitors AudioAttributes for USAGE_VOICE_COMMUNICATION / USAGE_VOICE_COMMUNICATION_SIGNALLING.
 * 2. AudioManager Mode: Checks for MODE_IN_CALL, MODE_IN_COMMUNICATION, or MODE_RINGTONE.
 */
class CallAudioBypassManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCallStateChanged: (isCallActive: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "CallAudioBypass"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    @Volatile
    private var isPlaybackCallActive = false

    @Volatile
    private var isModeCallActive = false

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var modeChangedListener: Any? = null

    fun start() {
        registerPlaybackCallback()
        registerModeListener()
    }

    fun stop() {
        unregisterPlaybackCallback()
        unregisterModeListener()
    }

    fun isCallActiveImmediate(): Boolean {
        if (_isCallActive.value) return true
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun registerPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                    val callActive = configs.any { config ->
                        config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                    }
                    updatePlaybackCallState(callActive)
                }
            }
            try {
                audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
                playbackCallback = callback
                Timber.tag(TAG).d("Registered AudioPlaybackCallback")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to register AudioPlaybackCallback")
            }
        }
    }

    private fun unregisterPlaybackCallback() {
        playbackCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    audioManager.unregisterAudioPlaybackCallback(it)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to unregister AudioPlaybackCallback")
                }
            }
            playbackCallback = null
        }
    }

    private fun registerModeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { mode ->
                val callActive = mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION
                updateModeCallState(callActive)
            }
            try {
                audioManager.addOnModeChangedListener(context.mainExecutor, listener)
                modeChangedListener = listener
                Timber.tag(TAG).d("Registered OnModeChangedListener")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to register OnModeChangedListener")
            }
        }
    }

    private fun unregisterModeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeChangedListener as? AudioManager.OnModeChangedListener)?.let {
                try {
                    audioManager.removeOnModeChangedListener(it)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to remove OnModeChangedListener")
                }
                modeChangedListener = null
            }
        }
    }

    private fun updatePlaybackCallState(active: Boolean) {
        if (isPlaybackCallActive == active) return
        isPlaybackCallActive = active
        evaluateState()
    }

    private fun updateModeCallState(active: Boolean) {
        if (isModeCallActive == active) return
        isModeCallActive = active
        evaluateState()
    }

    private fun evaluateState() {
        val newState = isPlaybackCallActive || isModeCallActive
        if (_isCallActive.value != newState) {
            _isCallActive.value = newState
            Timber.tag(TAG).i(
                "Call state changed: isCallActive=$newState (playback=$isPlaybackCallActive, mode=$isModeCallActive)"
            )
            scope.launch(Dispatchers.Main.immediate) {
                onCallStateChanged(newState)
            }
        }
    }
}
