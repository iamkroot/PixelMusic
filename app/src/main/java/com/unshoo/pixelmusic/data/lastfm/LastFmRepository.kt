package com.unshoo.pixelmusic.data.lastfm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.google.gson.Gson
import com.unshoo.pixelmusic.data.database.LastFmDao
import com.unshoo.pixelmusic.data.database.LastFmPendingScrobbleEntity
import com.unshoo.pixelmusic.data.worker.LastFmScrobbleFlushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LastFmRepository @Inject constructor(
    private val api: LastFmApiService,
    private val lastFmDao: LastFmDao,
    private val workManager: WorkManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LastFmRepo"
        private const val PREFS_NAME = "lastfm_prefs"
        private const val KEY_SESSION_KEY = "session_key"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_AUTH_INVALID = "auth_invalid"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_SECRET = "api_secret"

        private const val MAX_QUEUE_SIZE = 3000
    }

    private val prefs: SharedPreferences = createCredentialPrefs()
    private val queueMutex = Mutex()
    private val gson = Gson()

    @Volatile
    private var cachedSessionKey: String? = prefs.getString(KEY_SESSION_KEY, null)

    private val _accountState = MutableStateFlow(
        LastFmAccountState(
            isConnected = cachedSessionKey != null,
            userName = prefs.getString(KEY_USER_NAME, null),
            needsReauth = prefs.getBoolean(KEY_AUTH_INVALID, false),
            hasCustomCredentials = !prefs.getString(KEY_API_KEY, null).isNullOrBlank()
        )
    )
    val accountState: StateFlow<LastFmAccountState> = _accountState.asStateFlow()

    val pendingScrobbleCount = lastFmDao.countFlow()

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun createCredentialPrefs(): SharedPreferences = try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Timber.e(e, "$TAG: EncryptedSharedPreferences unreadable, deleting and recreating")
        context.deleteSharedPreferences(PREFS_NAME)
        try {
            createEncryptedPrefs()
        } catch (e2: Exception) {
            Timber.e(e2, "$TAG: Encrypted prefs unavailable, falling back to plain")
            context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
        }
    }

    fun hasSession(): Boolean = cachedSessionKey != null

    fun isAuthorized(): Boolean = cachedSessionKey != null && !_accountState.value.needsReauth

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: LastFmAuthUtils.DEFAULT_API_KEY

    private fun getApiSecret(): String = prefs.getString(KEY_API_SECRET, null)?.takeIf { it.isNotBlank() } ?: LastFmAuthUtils.DEFAULT_API_SECRET

    suspend fun enqueueScrobble(scrobble: LastFmPendingScrobbleEntity): Boolean {
        return queueMutex.withLock {
            if (cachedSessionKey == null) return@withLock false
            lastFmDao.insert(scrobble)
            val overflow = lastFmDao.count() - MAX_QUEUE_SIZE
            if (overflow > 0) {
                lastFmDao.deleteOldest(overflow)
                Timber.w("Last.fm queue capped at %d, dropped %d oldest scrobbles", MAX_QUEUE_SIZE, overflow)
            }
            true
        }
    }

    suspend fun connect(
        username: String,
        password: String,
        customApiKey: String? = null,
        customApiSecret: String? = null
    ): Result<String> {
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (trimmedUser.isEmpty() || trimmedPass.isEmpty()) {
            return Result.failure(IllegalArgumentException("Username and password cannot be empty"))
        }

        val effectiveApiKey = customApiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: LastFmAuthUtils.DEFAULT_API_KEY

        val effectiveApiSecret = customApiSecret?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_API_SECRET, null)?.takeIf { it.isNotBlank() }
            ?: LastFmAuthUtils.DEFAULT_API_SECRET

        return try {
            val params = mutableMapOf(
                "method" to "auth.getMobileSession",
                "username" to trimmedUser,
                "password" to trimmedPass,
                "api_key" to effectiveApiKey
            )
            val apiSig = LastFmAuthUtils.generateSignature(params, effectiveApiSecret)
            params["api_sig"] = apiSig

            val response = api.getMobileSession(params)
            val body = response.body()

            if (response.isSuccessful && body?.session != null) {
                val session = body.session
                prefs.edit {
                    putString(KEY_SESSION_KEY, session.key)
                    putString(KEY_USER_NAME, session.name)
                    putBoolean(KEY_AUTH_INVALID, false)
                    if (!customApiKey.isNullOrBlank()) {
                        putString(KEY_API_KEY, customApiKey.trim())
                    }
                    if (!customApiSecret.isNullOrBlank()) {
                        putString(KEY_API_SECRET, customApiSecret.trim())
                    }
                }
                cachedSessionKey = session.key
                _accountState.value = LastFmAccountState(
                    isConnected = true,
                    userName = session.name,
                    needsReauth = false,
                    hasCustomCredentials = !prefs.getString(KEY_API_KEY, null).isNullOrBlank()
                )
                scheduleFlush()
                Result.success(session.name)
            } else {
                val errorCode = parseErrorCode(response)
                val errorMsg = body?.message
                    ?: "Login failed (code: ${response.code()}, Last.fm error: $errorCode)"
                Result.failure(IllegalStateException(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Mobile session authentication failed")
            Result.failure(e)
        }
    }

    suspend fun disconnect() {
        queueMutex.withLock {
            workManager.cancelUniqueWork(LastFmScrobbleFlushWorker.WORK_NAME)
            prefs.edit {
                remove(KEY_SESSION_KEY)
                remove(KEY_USER_NAME)
                remove(KEY_AUTH_INVALID)
                remove(KEY_API_KEY)
                remove(KEY_API_SECRET)
            }
            cachedSessionKey = null
            _accountState.value = LastFmAccountState()
            lastFmDao.clear()
        }
    }

    suspend fun fetchProfileStats(): LastFmProfileStats? {
        val userName = _accountState.value.userName?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val response = api.getUserInfo(user = userName, apiKey = getApiKey())
            if (response.isSuccessful) {
                val playcount = response.body()?.user?.playcount?.toLongOrNull()
                LastFmProfileStats(playcount = playcount)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.d(e, "$TAG: Failed to fetch profile stats")
            null
        }
    }

    suspend fun submitPlayingNow(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationMs: Long?,
        recordingMbid: String?
    ): LastFmSubmitResult {
        val sessionKey = cachedSessionKey ?: return LastFmSubmitResult.AuthFailed
        val apiKey = getApiKey()
        val apiSecret = getApiSecret()

        val params = mutableMapOf(
            "method" to "track.updateNowPlaying",
            "artist" to artistName,
            "track" to trackName,
            "api_key" to apiKey,
            "sk" to sessionKey
        )
        if (!albumName.isNullOrBlank()) {
            params["album"] = albumName
        }
        if (durationMs != null && durationMs > 0) {
            params["duration"] = (durationMs / 1000).toString()
        }
        if (!recordingMbid.isNullOrBlank()) {
            params["mbid"] = recordingMbid
        }

        params["api_sig"] = LastFmAuthUtils.generateSignature(params, apiSecret)

        return try {
            val response = api.updateNowPlaying(params)
            evaluateSubmitResponse(response)
        } catch (e: Exception) {
            Timber.d(e, "$TAG: Now playing submission failed")
            LastFmSubmitResult.TransientError()
        }
    }

    suspend fun submitScrobbles(scrobbles: List<LastFmPendingScrobbleEntity>): LastFmSubmitResult {
        val sessionKey = cachedSessionKey ?: return LastFmSubmitResult.AuthFailed
        if (scrobbles.isEmpty()) return LastFmSubmitResult.Success

        val apiKey = getApiKey()
        val apiSecret = getApiSecret()

        val params = mutableMapOf(
            "method" to "track.scrobble",
            "api_key" to apiKey,
            "sk" to sessionKey
        )

        scrobbles.forEachIndexed { i, scrobble ->
            params["artist[$i]"] = scrobble.artistName
            params["track[$i]"] = scrobble.trackName
            params["timestamp[$i]"] = scrobble.timestamp.toString()
            scrobble.albumName?.takeIf { it.isNotBlank() }?.let { params["album[$i]"] = it }
            scrobble.durationSeconds?.takeIf { it > 0 }?.let { params["duration[$i]"] = it.toString() }
            scrobble.recordingMbid?.takeIf { it.isNotBlank() }?.let { params["mbid[$i]"] = it }
        }

        params["api_sig"] = LastFmAuthUtils.generateSignature(params, apiSecret)

        return try {
            val response = api.scrobble(params)
            evaluateSubmitResponse(response)
        } catch (e: Exception) {
            Timber.d(e, "$TAG: Scrobble batch submission failed, will retry")
            LastFmSubmitResult.TransientError()
        }
    }

    private fun parseErrorCode(response: Response<*>): Int? {
        val body = response.body()
        if (body is LastFmScrobbleResponse && body.error != null) return body.error
        if (body is LastFmNowPlayingResponse && body.error != null) return body.error
        if (body is LastFmSessionResponse && body.error != null) return body.error
        if (body is LastFmUserInfoResponse && body.error != null) return body.error

        return try {
            val errorStr = response.errorBody()?.string()
            if (!errorStr.isNullOrBlank()) {
                gson.fromJson(errorStr, LastFmErrorResponse::class.java)?.error
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun evaluateSubmitResponse(response: Response<*>): LastFmSubmitResult {
        val code = response.code()
        val errorCode = parseErrorCode(response)

        return when {
            response.isSuccessful && errorCode == null -> LastFmSubmitResult.Success
            errorCode == 9 || code == 401 -> {
                markAuthInvalid()
                LastFmSubmitResult.AuthFailed
            }
            errorCode in listOf(11, 16, 29) || code in 500..599 || code == 429 -> {
                val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
                LastFmSubmitResult.TransientError(retryAfter)
            }
            errorCode in listOf(6, 7) || code == 400 -> LastFmSubmitResult.InvalidPayload
            else -> LastFmSubmitResult.TransientError()
        }
    }

    private fun markAuthInvalid() {
        prefs.edit { putBoolean(KEY_AUTH_INVALID, true) }
        _accountState.value = _accountState.value.copy(needsReauth = true)
    }

    fun scheduleFlush() {
        workManager.enqueueUniqueWork(
            LastFmScrobbleFlushWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            LastFmScrobbleFlushWorker.request()
        )
    }

    fun scheduleFlushAfter(delaySeconds: Long) {
        workManager.enqueueUniqueWork(
            LastFmScrobbleFlushWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            LastFmScrobbleFlushWorker.request(initialDelaySeconds = delaySeconds)
        )
    }
}
