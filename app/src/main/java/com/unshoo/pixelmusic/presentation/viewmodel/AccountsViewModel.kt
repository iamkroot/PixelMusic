package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.jellyfin.JellyfinRepository
import com.unshoo.pixelmusic.data.lastfm.LastFmProfileStats
import com.unshoo.pixelmusic.data.lastfm.LastFmRepository
import com.unshoo.pixelmusic.data.listenbrainz.InvalidServerUrlException
import com.unshoo.pixelmusic.data.listenbrainz.ListenBrainzProfileStats
import com.unshoo.pixelmusic.data.listenbrainz.ListenBrainzRepository
import com.unshoo.pixelmusic.data.navidrome.NavidromeRepository
import com.unshoo.pixelmusic.data.preferences.LastFmPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.ListenBrainzPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

private const val PROFILE_STATS_POLL_MS = 20_000L

enum class ExternalServiceAccount {
    NAVIDROME,
    JELLYFIN,
    LISTENBRAINZ,
    LASTFM
}

data class ExternalAccountUiModel(
    val service: ExternalServiceAccount,
    val title: String,
    val accountLabel: String,
    val syncedContentLabel: String,
    val isLoggingOut: Boolean
)

data class ListenBrainzUiModel(
    val userName: String?,
    val pendingCount: Int,
    val needsReauth: Boolean,
    val serverUrl: String?,
    val statsState: ListenBrainzStatsUiState,
    val scrobbleLocal: Boolean,
    val scrobbleNavidrome: Boolean,
    val scrobbleJellyfin: Boolean
)

sealed interface ListenBrainzStatsUiState {
    data object Loading : ListenBrainzStatsUiState
    data object Unavailable : ListenBrainzStatsUiState
    data class Loaded(val stats: ListenBrainzProfileStats) : ListenBrainzStatsUiState
}

sealed interface ListenBrainzConnectState {
    data object Idle : ListenBrainzConnectState
    data object Connecting : ListenBrainzConnectState
    data object Success : ListenBrainzConnectState
    data class Failed(val invalidUrl: Boolean = false) : ListenBrainzConnectState
}

data class LastFmUiModel(
    val userName: String?,
    val pendingCount: Int,
    val needsReauth: Boolean,
    val hasCustomCredentials: Boolean,
    val statsState: LastFmStatsUiState,
    val scrobbleLocal: Boolean,
    val scrobbleNavidrome: Boolean,
    val scrobbleJellyfin: Boolean,
    val nowPlayingEnabled: Boolean
)

sealed interface LastFmStatsUiState {
    data object Loading : LastFmStatsUiState
    data object Unavailable : LastFmStatsUiState
    data class Loaded(val stats: LastFmProfileStats) : LastFmStatsUiState
}

sealed interface LastFmConnectState {
    data object Idle : LastFmConnectState
    data object Connecting : LastFmConnectState
    data object Success : LastFmConnectState
    data class Failed(val message: String? = null) : LastFmConnectState
}

data class AccountsUiState(
    val connectedAccounts: List<ExternalAccountUiModel> = emptyList(),
    val disconnectedServices: ImmutableList<ExternalServiceAccount> = persistentListOf(),
    val listenBrainz: ListenBrainzUiModel? = null,
    val lastFm: LastFmUiModel? = null
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val navidromeRepository: NavidromeRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val listenBrainzRepository: ListenBrainzRepository,
    private val listenBrainzPreferences: ListenBrainzPreferencesRepository,
    private val lastFmRepository: LastFmRepository,
    private val lastFmPreferences: LastFmPreferencesRepository
) : ViewModel() {

    private val loggingOutServices = MutableStateFlow<Set<ExternalServiceAccount>>(emptySet())

    private val navidromeStateFlow = combine(
        navidromeRepository.isLoggedInFlow,
        navidromeRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    private val jellyfinStateFlow = combine(
        jellyfinRepository.isLoggedInFlow,
        jellyfinRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val listenBrainzStatsFlow = listenBrainzRepository.accountState
        .map { it.isConnected to it.userName }
        .distinctUntilChanged()
        .flatMapLatest { (connected, userName) ->
            if (!connected || userName.isNullOrBlank()) {
                flowOf(ListenBrainzStatsUiState.Unavailable)
            } else {
                flow<ListenBrainzStatsUiState> {
                    emit(ListenBrainzStatsUiState.Loading)
                    var lastLoaded: ListenBrainzProfileStats? = null
                    while (true) {
                        val stats = listenBrainzRepository.fetchProfileStats() ?: lastLoaded
                        if (stats != null) {
                            lastLoaded = stats
                            emit(ListenBrainzStatsUiState.Loaded(stats))
                        } else {
                            emit(ListenBrainzStatsUiState.Unavailable)
                        }
                        delay(PROFILE_STATS_POLL_MS)
                    }
                }
            }
        }

    private val listenBrainzStateFlow = combine(
        listenBrainzRepository.accountState,
        listenBrainzRepository.pendingListenCount,
        listenBrainzStatsFlow,
        listenBrainzPreferences.scrobbleLocalFlow,
        combine(
            listenBrainzPreferences.scrobbleNavidromeFlow,
            listenBrainzPreferences.scrobbleJellyfinFlow
        ) { navidrome, jellyfin -> navidrome to jellyfin }
    ) { account, pendingCount, statsState, local, remoteToggles ->
        if (account.isConnected) {
            ListenBrainzUiModel(
                userName = account.userName,
                pendingCount = pendingCount,
                needsReauth = account.needsReauth,
                serverUrl = account.serverUrl,
                statsState = statsState,
                scrobbleLocal = local,
                scrobbleNavidrome = remoteToggles.first,
                scrobbleJellyfin = remoteToggles.second
            )
        } else {
            null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lastFmStatsFlow = lastFmRepository.accountState
        .map { it.isConnected to it.userName }
        .distinctUntilChanged()
        .flatMapLatest { (connected, userName) ->
            if (!connected || userName.isNullOrBlank()) {
                flowOf(LastFmStatsUiState.Unavailable)
            } else {
                flow<LastFmStatsUiState> {
                    emit(LastFmStatsUiState.Loading)
                    var lastLoaded: LastFmProfileStats? = null
                    while (true) {
                        val stats = lastFmRepository.fetchProfileStats() ?: lastLoaded
                        if (stats != null) {
                            lastLoaded = stats
                            emit(LastFmStatsUiState.Loaded(stats))
                        } else {
                            emit(LastFmStatsUiState.Unavailable)
                        }
                        delay(PROFILE_STATS_POLL_MS)
                    }
                }
            }
        }

    private val lastFmStateFlow = combine(
        lastFmRepository.accountState,
        lastFmRepository.pendingScrobbleCount,
        lastFmStatsFlow,
        lastFmPreferences.scrobbleLocalFlow,
        combine(
            lastFmPreferences.scrobbleNavidromeFlow,
            lastFmPreferences.scrobbleJellyfinFlow,
            lastFmPreferences.nowPlayingEnabledFlow
        ) { navidrome, jellyfin, nowPlaying -> Triple(navidrome, jellyfin, nowPlaying) }
    ) { account, pendingCount, statsState, local, remoteToggles ->
        if (account.isConnected) {
            LastFmUiModel(
                userName = account.userName,
                pendingCount = pendingCount,
                needsReauth = account.needsReauth,
                hasCustomCredentials = account.hasCustomCredentials,
                statsState = statsState,
                scrobbleLocal = local,
                scrobbleNavidrome = remoteToggles.first,
                scrobbleJellyfin = remoteToggles.second,
                nowPlayingEnabled = remoteToggles.third
            )
        } else {
            null
        }
    }

    private val _listenBrainzConnectState =
        MutableStateFlow<ListenBrainzConnectState>(ListenBrainzConnectState.Idle)
    val listenBrainzConnectState: StateFlow<ListenBrainzConnectState> =
        _listenBrainzConnectState.asStateFlow()

    private val _lastFmConnectState =
        MutableStateFlow<LastFmConnectState>(LastFmConnectState.Idle)
    val lastFmConnectState: StateFlow<LastFmConnectState> =
        _lastFmConnectState.asStateFlow()

    val uiState: StateFlow<AccountsUiState> = combine(
        combine(
            listOf(
                navidromeStateFlow,
                jellyfinStateFlow
            )
        ) { it.toList() },
        listenBrainzStateFlow,
        lastFmStateFlow,
        loggingOutServices
    ) { states, listenBrainz, lastFm, activeLogouts ->
        val (navidromeConnected, navidromePlaylistCount) = states[0]
        val (jellyfinConnected, jellyfinPlaylistCount) = states[1]

        val connectedAccounts = buildList {
            if (navidromeConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.NAVIDROME,
                        title = "Subsonic",
                        accountLabel = navidromeRepository.username
                            ?.takeIf { it.isNotBlank() }
                            ?: "Subsonic account connected",
                        syncedContentLabel = formatCount(
                            count = navidromePlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.NAVIDROME in activeLogouts
                    )
                )
            }
            if (jellyfinConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.JELLYFIN,
                        title = "Jellyfin",
                        accountLabel = jellyfinRepository.username
                            ?.takeIf { it.isNotBlank() }
                            ?: "Jellyfin account connected",
                        syncedContentLabel = formatCount(
                            count = jellyfinPlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.JELLYFIN in activeLogouts
                    )
                )
            }
            if (listenBrainz != null) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.LISTENBRAINZ,
                        title = "ListenBrainz",
                        accountLabel = listenBrainz.userName
                            ?.takeIf { it.isNotBlank() }
                            ?: "ListenBrainz account connected",
                        syncedContentLabel = if (listenBrainz.pendingCount > 0) {
                            formatCount(
                                count = listenBrainz.pendingCount,
                                singular = "queued listen",
                                plural = "queued listens"
                            )
                        } else {
                            "Scrobbling listens as you play"
                        },
                        isLoggingOut = ExternalServiceAccount.LISTENBRAINZ in activeLogouts
                    )
                )
            }
            if (lastFm != null) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.LASTFM,
                        title = "Last.fm",
                        accountLabel = lastFm.userName
                            ?.takeIf { it.isNotBlank() }
                            ?: "Last.fm account connected",
                        syncedContentLabel = if (lastFm.pendingCount > 0) {
                            formatCount(
                                count = lastFm.pendingCount,
                                singular = "queued scrobble",
                                plural = "queued scrobbles"
                            )
                        } else {
                            "Scrobbling plays to Last.fm"
                        },
                        isLoggingOut = ExternalServiceAccount.LASTFM in activeLogouts
                    )
                )
            }
        }

        val disconnectedServices = buildList {
            if (!navidromeConnected) add(ExternalServiceAccount.NAVIDROME)
            if (!jellyfinConnected) add(ExternalServiceAccount.JELLYFIN)
            if (listenBrainz == null) add(ExternalServiceAccount.LISTENBRAINZ)
            if (lastFm == null) add(ExternalServiceAccount.LASTFM)
        }

        AccountsUiState(
            connectedAccounts = connectedAccounts,
            disconnectedServices = disconnectedServices.toImmutableList(),
            listenBrainz = listenBrainz,
            lastFm = lastFm
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun logout(service: ExternalServiceAccount) {
        if (service in loggingOutServices.value) return

        viewModelScope.launch {
            loggingOutServices.update { it + service }
            try {
                runCatching {
                    when (service) {
                        ExternalServiceAccount.NAVIDROME -> navidromeRepository.logout()
                        ExternalServiceAccount.JELLYFIN -> jellyfinRepository.logout()
                        ExternalServiceAccount.LISTENBRAINZ -> listenBrainzRepository.disconnect()
                        ExternalServiceAccount.LASTFM -> lastFmRepository.disconnect()
                    }
                }
            } finally {
                loggingOutServices.update { it - service }
            }
        }
    }

    fun connectListenBrainz(token: String, serverUrl: String) {
        if (_listenBrainzConnectState.value == ListenBrainzConnectState.Connecting) return
        viewModelScope.launch {
            _listenBrainzConnectState.value = ListenBrainzConnectState.Connecting
            val result = listenBrainzRepository.connect(token, serverUrl)
            _listenBrainzConnectState.value = if (result.isSuccess) {
                ListenBrainzConnectState.Success
            } else {
                ListenBrainzConnectState.Failed(
                    invalidUrl = result.exceptionOrNull() is InvalidServerUrlException
                )
            }
        }
    }

    fun resetListenBrainzConnectState() {
        _listenBrainzConnectState.value = ListenBrainzConnectState.Idle
    }

    fun connectLastFm(username: String, password: String, apiKey: String?, apiSecret: String?) {
        if (_lastFmConnectState.value == LastFmConnectState.Connecting) return
        viewModelScope.launch {
            _lastFmConnectState.value = LastFmConnectState.Connecting
            val result = lastFmRepository.connect(username, password, apiKey, apiSecret)
            _lastFmConnectState.value = if (result.isSuccess) {
                LastFmConnectState.Success
            } else {
                LastFmConnectState.Failed(
                    message = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun resetLastFmConnectState() {
        _lastFmConnectState.value = LastFmConnectState.Idle
    }

    fun setListenBrainzScrobbleLocal(enabled: Boolean) {
        viewModelScope.launch { listenBrainzPreferences.setScrobbleLocal(enabled) }
    }

    fun setListenBrainzScrobbleNavidrome(enabled: Boolean) {
        viewModelScope.launch { listenBrainzPreferences.setScrobbleNavidrome(enabled) }
    }

    fun setListenBrainzScrobbleJellyfin(enabled: Boolean) {
        viewModelScope.launch { listenBrainzPreferences.setScrobbleJellyfin(enabled) }
    }

    fun setLastFmScrobbleLocal(enabled: Boolean) {
        viewModelScope.launch { lastFmPreferences.setScrobbleLocal(enabled) }
    }

    fun setLastFmScrobbleNavidrome(enabled: Boolean) {
        viewModelScope.launch { lastFmPreferences.setScrobbleNavidrome(enabled) }
    }

    fun setLastFmScrobbleJellyfin(enabled: Boolean) {
        viewModelScope.launch { lastFmPreferences.setScrobbleJellyfin(enabled) }
    }

    fun setLastFmNowPlaying(enabled: Boolean) {
        viewModelScope.launch { lastFmPreferences.setNowPlayingEnabled(enabled) }
    }

    private fun formatCount(count: Int, singular: String, plural: String): String {
        return if (count == 1) {
            "1 $singular"
        } else {
            "$count $plural"
        }
    }
}
