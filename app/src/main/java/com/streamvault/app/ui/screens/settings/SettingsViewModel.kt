package com.streamvault.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ExportBackupCommand
import com.streamvault.domain.usecase.ExportBackupResult
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.domain.usecase.SyncProviderCommand
import com.streamvault.domain.usecase.SyncProviderResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProviderWarningAction {
    EPG,
    MOVIES,
    SERIES
}

enum class ProviderSyncSelection {
    ALL,
    FAST,
    TV,
    MOVIES,
    SERIES,
    EPG
}

private data class SettingsPreferenceSnapshot(
    val providers: List<Provider>,
    val activeProviderId: Long?,
    val parentalControlLevel: Int,
    val hasParentalPin: Boolean,
    val appLanguage: String,
    val preferredAudioLanguage: String,
    val playerPlaybackSpeed: Float,
    val subtitleTextScale: Float,
    val subtitleTextColor: Int,
    val subtitleBackgroundColor: Int,
    val wifiMaxVideoHeight: Int?,
    val ethernetMaxVideoHeight: Int?,
    val lastSpeedTestMegabits: Double?,
    val lastSpeedTestTimestamp: Long?,
    val lastSpeedTestTransport: String?,
    val lastSpeedTestRecommendedHeight: Int?,
    val lastSpeedTestEstimated: Boolean,
    val isIncognitoMode: Boolean,
    val useXtreamTextClassification: Boolean,
    val liveTvChannelMode: LiveTvChannelMode,
    val liveTvCategoryFilters: List<String>,
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode,
    val liveChannelNumberingMode: ChannelNumberingMode,
    val vodViewMode: VodViewMode,
    val preventStandbyDuringPlayback: Boolean
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    application: Application,
    private val providerRepository: ProviderRepository,
    private val categoryRepository: CategoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val internetSpeedTestRunner: InternetSpeedTestRunner,
    private val backupManager: BackupManager,
    private val recordingManager: RecordingManager,
    private val syncManager: SyncManager,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val playbackHistoryRepository: com.streamvault.domain.repository.PlaybackHistoryRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val syncProvider: SyncProvider,
    private val epgSourceRepository: com.streamvault.domain.repository.EpgSourceRepository
) : ViewModel() {
    private val appContext = application
    private val exportBackup = ExportBackup(backupManager)
    private val importBackup = ImportBackup(backupManager)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val activeProviderIdFlow = providerRepository.getActiveProvider().map { it?.id }

    init {
        viewModelScope.launch {
            combine(
                providerRepository.getProviders(),
                activeProviderIdFlow,
                preferencesRepository.parentalControlLevel,
                preferencesRepository.hasParentalPin
            ) { providers, activeId, level, hasParentalPin ->
                SettingsPreferenceSnapshot(
                    providers = providers,
                    activeProviderId = activeId,
                    parentalControlLevel = level,
                    hasParentalPin = hasParentalPin,
                    appLanguage = "system",
                    preferredAudioLanguage = "auto",
                    playerPlaybackSpeed = 1f,
                    subtitleTextScale = 1f,
                    subtitleTextColor = 0xFFFFFFFF.toInt(),
                    subtitleBackgroundColor = 0x80000000.toInt(),
                    wifiMaxVideoHeight = null,
                    ethernetMaxVideoHeight = null,
                    lastSpeedTestMegabits = null,
                    lastSpeedTestTimestamp = null,
                    lastSpeedTestTransport = null,
                    lastSpeedTestRecommendedHeight = null,
                    lastSpeedTestEstimated = false,
                    isIncognitoMode = false,
                    useXtreamTextClassification = true,
                    liveTvChannelMode = LiveTvChannelMode.PRO,
                    liveTvCategoryFilters = emptyList(),
                    liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
                    liveChannelNumberingMode = ChannelNumberingMode.GROUP,
                    vodViewMode = VodViewMode.MODERN,
                    preventStandbyDuringPlayback = true
                )
            }.combine(preferencesRepository.appLanguage) { snapshot, language ->
                snapshot.copy(appLanguage = language)
            }.combine(preferencesRepository.preferredAudioLanguage) { snapshot, preferredAudioLanguage ->
                snapshot.copy(preferredAudioLanguage = preferredAudioLanguage ?: "auto")
            }.combine(preferencesRepository.playerPlaybackSpeed) { snapshot, playerPlaybackSpeed ->
                snapshot.copy(playerPlaybackSpeed = playerPlaybackSpeed)
            }.combine(preferencesRepository.playerSubtitleTextScale) { snapshot, subtitleTextScale ->
                snapshot.copy(subtitleTextScale = subtitleTextScale)
            }.combine(preferencesRepository.playerSubtitleTextColor) { snapshot, subtitleTextColor ->
                snapshot.copy(subtitleTextColor = subtitleTextColor)
            }.combine(preferencesRepository.playerSubtitleBackgroundColor) { snapshot, subtitleBackgroundColor ->
                snapshot.copy(subtitleBackgroundColor = subtitleBackgroundColor)
            }.combine(preferencesRepository.playerWifiMaxVideoHeight) { snapshot, wifiMaxVideoHeight ->
                snapshot.copy(wifiMaxVideoHeight = wifiMaxVideoHeight)
            }.combine(preferencesRepository.playerEthernetMaxVideoHeight) { snapshot, ethernetMaxVideoHeight ->
                snapshot.copy(ethernetMaxVideoHeight = ethernetMaxVideoHeight)
            }.combine(preferencesRepository.lastSpeedTestMegabits) { snapshot, lastSpeedTestMegabits ->
                snapshot.copy(lastSpeedTestMegabits = lastSpeedTestMegabits)
            }.combine(preferencesRepository.lastSpeedTestTimestamp) { snapshot, lastSpeedTestTimestamp ->
                snapshot.copy(lastSpeedTestTimestamp = lastSpeedTestTimestamp)
            }.combine(preferencesRepository.lastSpeedTestTransport) { snapshot, lastSpeedTestTransport ->
                snapshot.copy(lastSpeedTestTransport = lastSpeedTestTransport)
            }.combine(preferencesRepository.lastSpeedTestRecommendedHeight) { snapshot, lastSpeedTestRecommendedHeight ->
                snapshot.copy(lastSpeedTestRecommendedHeight = lastSpeedTestRecommendedHeight)
            }.combine(preferencesRepository.lastSpeedTestEstimated) { snapshot, lastSpeedTestEstimated ->
                snapshot.copy(lastSpeedTestEstimated = lastSpeedTestEstimated)
            }.combine(preferencesRepository.isIncognitoMode) { snapshot, incognito ->
                snapshot.copy(isIncognitoMode = incognito)
            }.combine(preferencesRepository.useXtreamTextClassification) { snapshot, useTextClass ->
                snapshot.copy(useXtreamTextClassification = useTextClass)
            }.combine(preferencesRepository.liveTvChannelMode) { snapshot, liveTvChannelMode ->
                snapshot.copy(liveTvChannelMode = LiveTvChannelMode.fromStorage(liveTvChannelMode))
            }.combine(preferencesRepository.liveTvCategoryFilters) { snapshot, liveTvCategoryFilters ->
                snapshot.copy(liveTvCategoryFilters = liveTvCategoryFilters)
            }.combine(preferencesRepository.liveTvQuickFilterVisibility) { snapshot, visibilityMode ->
                snapshot.copy(
                    liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.fromStorage(visibilityMode)
                )
            }.combine(preferencesRepository.liveChannelNumberingMode) { snapshot, liveChannelNumberingMode ->
                snapshot.copy(liveChannelNumberingMode = liveChannelNumberingMode)
            }.combine(preferencesRepository.vodViewMode) { snapshot, vodViewMode ->
                snapshot.copy(vodViewMode = VodViewMode.fromStorage(vodViewMode))
            }.combine(preferencesRepository.preventStandbyDuringPlayback) { snapshot, preventStandby ->
                snapshot.copy(preventStandbyDuringPlayback = preventStandby)
            }.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        providers = snapshot.providers,
                        activeProviderId = snapshot.activeProviderId,
                        parentalControlLevel = snapshot.parentalControlLevel,
                        hasParentalPin = snapshot.hasParentalPin,
                        appLanguage = snapshot.appLanguage,
                        preferredAudioLanguage = snapshot.preferredAudioLanguage,
                        playerPlaybackSpeed = snapshot.playerPlaybackSpeed,
                        subtitleTextScale = snapshot.subtitleTextScale,
                        subtitleTextColor = snapshot.subtitleTextColor,
                        subtitleBackgroundColor = snapshot.subtitleBackgroundColor,
                        wifiMaxVideoHeight = snapshot.wifiMaxVideoHeight,
                        ethernetMaxVideoHeight = snapshot.ethernetMaxVideoHeight,
                        lastSpeedTest = snapshot.lastSpeedTestMegabits?.let {
                            InternetSpeedTestUiModel(
                                megabitsPerSecond = it,
                                measuredAtMs = snapshot.lastSpeedTestTimestamp ?: 0L,
                                transportLabel = snapshot.lastSpeedTestTransport ?: InternetSpeedTestTransport.UNKNOWN.name,
                                recommendedMaxVideoHeight = snapshot.lastSpeedTestRecommendedHeight,
                                isEstimated = snapshot.lastSpeedTestEstimated
                            )
                        },
                        isIncognitoMode = snapshot.isIncognitoMode,
                        useXtreamTextClassification = snapshot.useXtreamTextClassification,
                        liveTvChannelMode = snapshot.liveTvChannelMode,
                        liveTvCategoryFilters = snapshot.liveTvCategoryFilters,
                        liveTvQuickFilterVisibilityMode = snapshot.liveTvQuickFilterVisibilityMode,
                        liveChannelNumberingMode = snapshot.liveChannelNumberingMode,
                        vodViewMode = snapshot.vodViewMode,
                        preventStandbyDuringPlayback = snapshot.preventStandbyDuringPlayback
                    )
                }
            }
        }

        viewModelScope.launch {
            providerRepository.getProviders()
                .flatMapLatest { providers ->
                    if (providers.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            providers.map { provider ->
                                syncMetadataRepository.observeMetadata(provider.id).map { metadata ->
                                    provider.id to ProviderDiagnosticsUiModel(
                                        lastSyncStatus = metadata?.lastSyncStatus ?: "NONE",
                                        lastLiveSync = metadata?.lastLiveSync ?: 0L,
                                        lastMovieSync = metadata?.lastMovieSync ?: 0L,
                                        lastMovieAttempt = metadata?.lastMovieAttempt ?: 0L,
                                        lastMovieSuccess = metadata?.lastMovieSuccess ?: 0L,
                                        lastMoviePartial = metadata?.lastMoviePartial ?: 0L,
                                        lastSeriesSync = metadata?.lastSeriesSync ?: 0L,
                                        lastEpgSync = metadata?.lastEpgSync ?: 0L,
                                        liveCount = metadata?.liveCount ?: 0,
                                        movieCount = metadata?.movieCount ?: 0,
                                        seriesCount = metadata?.seriesCount ?: 0,
                                        epgCount = metadata?.epgCount ?: 0,
                                        movieSyncMode = metadata?.movieSyncMode ?: VodSyncMode.UNKNOWN,
                                        movieWarningsCount = metadata?.movieWarningsCount ?: 0,
                                        movieCatalogStale = metadata?.movieCatalogStale ?: false,
                                        capabilitySummary = buildCapabilitySummary(provider),
                                        sourceLabel = when (provider.type) {
                                            ProviderType.XTREAM_CODES -> "Xtream Codes"
                                            ProviderType.M3U -> "M3U Playlist"
                                        },
                                        expirySummary = run {
                                            val expirationDate = provider.expirationDate
                                            when {
                                                expirationDate == null -> "Expiry unknown"
                                                expirationDate == Long.MAX_VALUE -> "No expiry reported"
                                                expirationDate < System.currentTimeMillis() -> "Expired"
                                                else -> "Active until ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expirationDate))}"
                                            }
                                        },
                                        connectionSummary = "${provider.maxConnections} connection(s)",
                                        archiveSummary = when (provider.type) {
                                            ProviderType.XTREAM_CODES -> "Catch-up depends on provider archive flags and replay stream ids."
                                            ProviderType.M3U -> if (provider.epgUrl.isBlank()) {
                                                "M3U replay is limited without guide coverage."
                                            } else {
                                                "M3U replay depends on channel templates and guide alignment."
                                            }
                                        }
                                    )
                                }
                            }
                        ) { pairs ->
                            pairs.toMap()
                        }
                    }
                }
                .collect { diagnosticsByProvider ->
                    _uiState.update { it.copy(diagnosticsByProvider = diagnosticsByProvider) }
                }
        }

        viewModelScope.launch {
            activeProviderIdFlow
                .flatMapLatest { providerId ->
                    if (providerId == null) {
                        flowOf(CategoryManagementSnapshot())
                    } else {
                        combine(
                            preferencesRepository.getCategorySortMode(providerId, ContentType.LIVE),
                            preferencesRepository.getCategorySortMode(providerId, ContentType.MOVIE),
                            preferencesRepository.getCategorySortMode(providerId, ContentType.SERIES),
                            categoryRepository.getCategories(providerId),
                            preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE),
                            preferencesRepository.getHiddenCategoryIds(providerId, ContentType.MOVIE),
                            preferencesRepository.getHiddenCategoryIds(providerId, ContentType.SERIES)
                        ) { values ->
                            val liveSort = values[0] as CategorySortMode
                            val movieSort = values[1] as CategorySortMode
                            val seriesSort = values[2] as CategorySortMode
                            val categories = values[3] as List<Category>
                            val hiddenLive = values[4] as Set<Long>
                            val hiddenMovies = values[5] as Set<Long>
                            val hiddenSeries = values[6] as Set<Long>
                            val hiddenByType = mapOf(
                                ContentType.LIVE to hiddenLive,
                                ContentType.MOVIE to hiddenMovies,
                                ContentType.SERIES to hiddenSeries
                            )
                            CategoryManagementSnapshot(
                                categorySortModes = mapOf(
                                    ContentType.LIVE to liveSort,
                                    ContentType.MOVIE to movieSort,
                                    ContentType.SERIES to seriesSort
                                ),
                                hiddenCategories = categories
                                    .filter { category -> category.id in hiddenByType[category.type].orEmpty() }
                                    .sortedWith(compareBy<Category>({ it.type.ordinal }, { it.name.lowercase() }))
                            )
                        }
                    }
                }
                .collect { snapshot ->
                    _uiState.update {
                        it.copy(
                            categorySortModes = snapshot.categorySortModes,
                            hiddenCategories = snapshot.hiddenCategories
                        )
                    }
                }
        }

        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                _uiState.update { it.copy(recordingItems = items.sortedByDescending(RecordingItem::scheduledStartMs)) }
            }
        }

        viewModelScope.launch {
            recordingManager.observeStorageState().collect { storage ->
                _uiState.update { it.copy(recordingStorageState = storage) }
            }
        }
    }

    fun setActiveProvider(providerId: Long) {
        viewModelScope.launch {
            preferencesRepository.setLastActiveProviderId(providerId)
            providerRepository.setActiveProvider(providerId)
            // Force sync on connect
            refreshProvider(providerId)
        }
    }

    fun setParentalControlLevel(level: Int) {
        viewModelScope.launch {
            preferencesRepository.setParentalControlLevel(level)
        }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            preferencesRepository.setAppLanguage(language)
        }
    }

    fun setLiveTvChannelMode(mode: LiveTvChannelMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveTvChannelMode(mode.name)
        }
    }

    fun setLiveTvQuickFilterVisibilityMode(mode: LiveTvQuickFilterVisibilityMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveTvQuickFilterVisibility(mode.storageValue)
        }
    }

    fun addLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            val normalized = filter.trim()
            when {
                normalized.isBlank() -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_blank))
                    }
                }
                _uiState.value.liveTvCategoryFilters.any { existing ->
                    existing.equals(normalized, ignoreCase = true)
                } -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_duplicate, normalized))
                    }
                }
                preferencesRepository.addLiveTvCategoryFilter(normalized) -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_added, normalized))
                    }
                }
            }
        }
    }

    fun removeLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            if (preferencesRepository.removeLiveTvCategoryFilter(filter)) {
                _uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_removed, filter))
                }
            }
        }
    }

    fun setLiveChannelNumberingMode(mode: ChannelNumberingMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveChannelNumberingMode(mode)
        }
    }

    fun setVodViewMode(mode: VodViewMode) {
        viewModelScope.launch {
            preferencesRepository.setVodViewMode(mode.storageValue)
        }
    }

    fun setPreventStandbyDuringPlayback(prevent: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPreventStandbyDuringPlayback(prevent)
        }
    }

    fun setCategorySortMode(type: ContentType, mode: CategorySortMode) {
        val providerId = _uiState.value.activeProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategorySortMode(providerId, type, mode)
        }
    }

    fun unhideCategory(category: Category) {
        val providerId = _uiState.value.activeProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = category.type,
                categoryId = category.id,
                hidden = false
            )
            _uiState.update { it.copy(userMessage = "Unhid ${category.name}") }
        }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            preferencesRepository.setPlayerPlaybackSpeed(speed)
        }
    }

    fun setPreferredAudioLanguage(languageTag: String?) {
        viewModelScope.launch {
            preferencesRepository.setPreferredAudioLanguage(languageTag)
        }
    }

    fun setSubtitleTextScale(scale: Float) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleTextScale(scale)
        }
    }

    fun setSubtitleTextColor(colorArgb: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleTextColor(colorArgb)
        }
    }

    fun setSubtitleBackgroundColor(colorArgb: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleBackgroundColor(colorArgb)
        }
    }

    fun setWifiQualityCap(maxHeight: Int?) {
        viewModelScope.launch {
            preferencesRepository.setPlayerWifiMaxVideoHeight(maxHeight)
        }
    }

    fun setEthernetQualityCap(maxHeight: Int?) {
        viewModelScope.launch {
            preferencesRepository.setPlayerEthernetMaxVideoHeight(maxHeight)
        }
    }

    fun runInternetSpeedTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningInternetSpeedTest = true) }
            when (val result = internetSpeedTestRunner.run()) {
                is InternetSpeedTestResult.Success -> {
                    val snapshot = result.snapshot
                    preferencesRepository.setLastSpeedTestResult(
                        megabitsPerSecond = snapshot.megabitsPerSecond,
                        measuredAtMs = snapshot.measuredAtMs,
                        transport = snapshot.transport.name,
                        recommendedMaxHeight = snapshot.recommendedMaxVideoHeight,
                        estimated = snapshot.isEstimated
                    )
                    _uiState.update {
                        it.copy(
                            isRunningInternetSpeedTest = false,
                            userMessage = if (snapshot.isEstimated) {
                                appContext.getString(R.string.settings_speed_test_estimate_complete)
                            } else {
                                appContext.getString(R.string.settings_speed_test_complete)
                            }
                        )
                    }
                }
                is InternetSpeedTestResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRunningInternetSpeedTest = false,
                            userMessage = appContext.getString(R.string.settings_speed_test_failed, result.message)
                        )
                    }
                }
            }
        }
    }

    fun applySpeedTestRecommendationToWifi() {
        viewModelScope.launch {
            val recommendation = _uiState.value.lastSpeedTest?.recommendedMaxVideoHeight
            preferencesRepository.setPlayerWifiMaxVideoHeight(recommendation)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_speed_test_wifi_applied)) }
        }
    }

    fun applySpeedTestRecommendationToEthernet() {
        viewModelScope.launch {
            val recommendation = _uiState.value.lastSpeedTest?.recommendedMaxVideoHeight
            preferencesRepository.setPlayerEthernetMaxVideoHeight(recommendation)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_speed_test_ethernet_applied)) }
        }
    }

    fun toggleIncognitoMode() {
        viewModelScope.launch {
            val current = _uiState.value.isIncognitoMode
            preferencesRepository.setIncognitoMode(!current)
        }
    }

    fun toggleXtreamTextClassification() {
        viewModelScope.launch {
            val current = _uiState.value.useXtreamTextClassification
            preferencesRepository.setUseXtreamTextClassification(!current)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            when (val result = playbackHistoryRepository.clearAllHistory()) {
                is Result.Success -> {
                    preferencesRepository.clearAllRecentData()
                    _uiState.update { it.copy(isSyncing = false, userMessage = appContext.getString(R.string.settings_history_cleared)) }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isSyncing = false, userMessage = "Failed to clear history: ${result.message}") }
                }
                Result.Loading -> Unit
            }
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun changePin(newPin: String) {
        viewModelScope.launch {
            preferencesRepository.setParentalPin(newPin)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_pin_changed)) }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshProvider(providerId: Long, movieFastSyncOverride: Boolean? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            try {
            val result = syncProvider(
                SyncProviderCommand(
                    providerId = providerId,
                    force = true,
                    movieFastSyncOverride = movieFastSyncOverride
                )
            )
            if (result !is SyncProviderResult.Error) {
                tvInputChannelSyncManager.refreshTvInputCatalog()
            }
            _uiState.update { state ->
                val partialWarnings = (result as? SyncProviderResult.Success)?.warnings.orEmpty()
                val warningsMessage = partialWarnings
                    .take(3)
                    .joinToString(separator = ", ")
                    .ifBlank { "Some sections are incomplete." }
                state.copy(
                    isSyncing = false,
                    userMessage = when {
                        result is SyncProviderResult.Error -> "Refresh failed: ${result.message}"
                        (result as? SyncProviderResult.Success)?.isPartial == true -> "Refresh completed with warnings: $warningsMessage"
                        else -> "Provider refreshed successfully"
                    },
                    syncWarningsByProvider = when {
                        result is SyncProviderResult.Error -> state.syncWarningsByProvider - providerId
                        (result as? SyncProviderResult.Success)?.isPartial == true -> state.syncWarningsByProvider + (providerId to partialWarnings)
                        else -> state.syncWarningsByProvider - providerId
                    }
                )
            }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, userMessage = "Sync failed: ${e.message}") }
            }
        }
    }

    fun syncProviderSection(providerId: Long, selection: ProviderSyncSelection) {
        viewModelScope.launch {
            when (selection) {
                ProviderSyncSelection.ALL, ProviderSyncSelection.FAST -> refreshProvider(providerId)
                else -> runSectionSync(providerId, listOf(selection))
            }
        }
    }

    fun syncProviderCustom(providerId: Long, selections: Set<ProviderSyncSelection>) {
        viewModelScope.launch {
            val orderedSelections = listOf(
                ProviderSyncSelection.TV,
                ProviderSyncSelection.MOVIES,
                ProviderSyncSelection.SERIES,
                ProviderSyncSelection.EPG
            ).filter { it in selections }
            if (orderedSelections.isEmpty()) {
                _uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_sync_custom_required))
                }
                return@launch
            }
            runSectionSync(providerId, orderedSelections)
        }
    }

    fun retryWarningAction(providerId: Long, action: ProviderWarningAction) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val section = when (action) {
                ProviderWarningAction.EPG -> SyncRepairSection.EPG
                ProviderWarningAction.MOVIES -> SyncRepairSection.MOVIES
                ProviderWarningAction.SERIES -> SyncRepairSection.SERIES
            }
            val result = syncManager.retrySection(providerId, section)
            _uiState.update { state ->
                if (result is Result.Error) {
                    state.copy(
                        isSyncing = false,
                        userMessage = "Retry failed: ${result.message}"
                    )
                } else {
                    val currentWarnings = state.syncWarningsByProvider[providerId].orEmpty()
                    val updatedWarnings = currentWarnings.filterNot { warning ->
                        when (action) {
                            ProviderWarningAction.EPG -> warning.contains("EPG", ignoreCase = true)
                            ProviderWarningAction.MOVIES -> warning.contains("Movies", ignoreCase = true)
                            ProviderWarningAction.SERIES -> warning.contains("Series", ignoreCase = true)
                        }
                    }
                    state.copy(
                        isSyncing = false,
                        userMessage = if (updatedWarnings.isEmpty()) {
                            "Section retry succeeded. All current warnings cleared."
                        } else {
                            "Section retry succeeded."
                        },
                        syncWarningsByProvider = if (updatedWarnings.isEmpty()) {
                            state.syncWarningsByProvider - providerId
                        } else {
                            state.syncWarningsByProvider + (providerId to updatedWarnings)
                        }
                    )
                }
            }
        }
    }

    private suspend fun runSectionSync(
        providerId: Long,
        selections: List<ProviderSyncSelection>
    ) {
        _uiState.update { it.copy(isSyncing = true) }
        try {
        val failures = mutableListOf<String>()
        val completed = mutableListOf<String>()

        selections.forEach { selection ->
            val section = when (selection) {
                ProviderSyncSelection.TV -> SyncRepairSection.LIVE
                ProviderSyncSelection.MOVIES -> SyncRepairSection.MOVIES
                ProviderSyncSelection.SERIES -> SyncRepairSection.SERIES
                ProviderSyncSelection.EPG -> SyncRepairSection.EPG
                ProviderSyncSelection.ALL, ProviderSyncSelection.FAST -> null
            } ?: return@forEach

            when (val result = syncManager.retrySection(providerId, section)) {
                is Result.Error -> failures += "${selection.label(appContext)}: ${result.message}"
                else -> completed += selection.label(appContext)
            }
        }

        if (completed.any { it == appContext.getString(R.string.settings_sync_option_all) || it == appContext.getString(R.string.settings_sync_option_tv) }) {
            tvInputChannelSyncManager.refreshTvInputCatalog()
        }

        _uiState.update { state ->
            state.copy(
                isSyncing = false,
                userMessage = when {
                    failures.isEmpty() -> appContext.getString(
                        R.string.settings_sync_sections_success,
                        completed.joinToString()
                    )
                    completed.isEmpty() -> appContext.getString(
                        R.string.settings_sync_sections_failed,
                        failures.joinToString()
                    )
                    else -> appContext.getString(
                        R.string.settings_sync_sections_partial,
                        completed.joinToString(),
                        failures.joinToString()
                    )
                }
            )
        }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSyncing = false, userMessage = "Sync failed: ${e.message}") }
        }
    }

    fun deleteProvider(providerId: Long) {
        viewModelScope.launch {
            providerRepository.deleteProvider(providerId)
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun exportConfig(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = exportBackup(ExportBackupCommand(uriString))
            _uiState.update { state ->
                state.copy(
                    isSyncing = false,
                    userMessage = if (result is ExportBackupResult.Error)
                        "Export failed: ${result.message}"
                    else "Configuration exported successfully"
                )
            }
        }
    }

    fun inspectBackup(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = importBackup.inspect(InspectBackupCommand(uriString))
            _uiState.update { state ->
                when (result) {
                    is InspectBackupResult.Error -> state.copy(
                        isSyncing = false,
                        userMessage = "Import failed: ${result.message}"
                    )
                    is InspectBackupResult.Success -> state.copy(
                        isSyncing = false,
                        pendingBackupUri = result.uriString,
                        backupPreview = result.preview,
                        backupImportPlan = result.defaultPlan
                    )
                }
            }
        }
    }

    fun dismissBackupPreview() {
        _uiState.update {
            it.copy(
                backupPreview = null,
                pendingBackupUri = null,
                backupImportPlan = BackupImportPlan()
            )
        }
    }

    fun setBackupConflictStrategy(strategy: BackupConflictStrategy) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(conflictStrategy = strategy)) }
    }

    fun setImportPreferences(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPreferences = enabled)) }
    }

    fun setImportProviders(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importProviders = enabled)) }
    }

    fun setImportSavedLibrary(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importSavedLibrary = enabled)) }
    }

    fun setImportPlaybackHistory(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPlaybackHistory = enabled)) }
    }

    fun setImportMultiViewPresets(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importMultiViewPresets = enabled)) }
    }

    fun setImportRecordingSchedules(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importRecordingSchedules = enabled)) }
    }

    fun confirmBackupImport() {
        val uriString = _uiState.value.pendingBackupUri ?: return
        val plan = _uiState.value.backupImportPlan
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = importBackup.confirm(ImportBackupCommand(uriString, plan))
            _uiState.update { state ->
                state.copy(
                    isSyncing = false,
                    userMessage = if (result is ImportBackupResult.Error)
                        "Import failed: ${result.message}"
                    else "Configuration imported: ${(result as ImportBackupResult.Success).importedSummary}",
                    backupPreview = null,
                    pendingBackupUri = null,
                    backupImportPlan = BackupImportPlan()
                )
            }
        }
    }

    fun stopRecording(recordingId: String) {
        viewModelScope.launch {
            val result = recordingManager.stopRecording(recordingId)
            _uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_stop_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_stopped)
                    }
                )
            }
        }
    }

    fun cancelRecording(recordingId: String) {
        viewModelScope.launch {
            val result = recordingManager.cancelRecording(recordingId)
            _uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_cancel_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_cancelled)
                    }
                )
            }
        }
    }

    fun deleteRecording(recordingId: String) {
        viewModelScope.launch {
            val result = recordingManager.deleteRecording(recordingId)
            _uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_delete_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_deleted)
                    }
                )
            }
        }
    }

    // ── EPG Source Management ────────────────────────────────────────

    fun loadEpgSources() {
        viewModelScope.launch {
            epgSourceRepository.getAllSources().collect { sources ->
                _uiState.update { it.copy(epgSources = sources) }
            }
        }
    }

    fun loadEpgAssignments(providerId: Long) {
        viewModelScope.launch {
            epgSourceRepository.getAssignmentsForProvider(providerId).collect { assignments ->
                val summary = epgSourceRepository.getResolutionSummary(providerId)
                _uiState.update {
                    it.copy(
                        epgSourceAssignments = it.epgSourceAssignments + (providerId to assignments),
                        epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary)
                    )
                }
            }
        }
    }

    fun addEpgSource(name: String, url: String) {
        viewModelScope.launch {
            val result = epgSourceRepository.addSource(name, url)
            if (result is Result.Error) {
                _uiState.update { it.copy(userMessage = result.message) }
            }
        }
    }

    fun deleteEpgSource(sourceId: Long) {
        viewModelScope.launch {
            val affectedProviders = loadedProvidersForSource(sourceId)
            epgSourceRepository.deleteSource(sourceId)
            refreshLoadedResolutionSummaries(affectedProviders)
        }
    }

    fun toggleEpgSourceEnabled(sourceId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val affectedProviders = loadedProvidersForSource(sourceId)
            epgSourceRepository.setSourceEnabled(sourceId, enabled)
            refreshLoadedResolutionSummaries(affectedProviders)
        }
    }

    fun refreshEpgSource(sourceId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val affectedProviders = loadedProvidersForSource(sourceId)
            val result = epgSourceRepository.refreshSource(sourceId)
            if (result !is Result.Error) {
                refreshLoadedResolutionSummaries(affectedProviders)
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    userMessage = if (result is Result.Error) result.message else "EPG source refreshed"
                )
            }
        }
    }

    fun assignEpgSourceToProvider(providerId: Long, epgSourceId: Long) {
        viewModelScope.launch {
            val existingAssignments = _uiState.value.epgSourceAssignments[providerId].orEmpty()
            val nextPriority = (existingAssignments.maxOfOrNull { it.priority } ?: 0) + 1
            val result = epgSourceRepository.assignSourceToProvider(providerId, epgSourceId, nextPriority)
            if (result is Result.Error) {
                _uiState.update { it.copy(userMessage = result.message) }
            } else {
                refreshProviderEpgSummary(providerId)
            }
        }
    }

    fun unassignEpgSourceFromProvider(providerId: Long, epgSourceId: Long) {
        viewModelScope.launch {
            epgSourceRepository.unassignSourceFromProvider(providerId, epgSourceId)
            refreshProviderEpgSummary(providerId)
        }
    }

    fun moveEpgSourceAssignmentUp(providerId: Long, epgSourceId: Long) {
        viewModelScope.launch {
            val assignments = _uiState.value.epgSourceAssignments[providerId].orEmpty()
                .sortedBy { it.priority }
            val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
            if (index <= 0) return@launch
            val current = assignments[index]
            val previous = assignments[index - 1]
            epgSourceRepository.updateAssignmentPriority(providerId, current.epgSourceId, previous.priority)
            epgSourceRepository.updateAssignmentPriority(providerId, previous.epgSourceId, current.priority)
            refreshProviderEpgSummary(providerId)
        }
    }

    fun moveEpgSourceAssignmentDown(providerId: Long, epgSourceId: Long) {
        viewModelScope.launch {
            val assignments = _uiState.value.epgSourceAssignments[providerId].orEmpty()
                .sortedBy { it.priority }
            val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
            if (index == -1 || index >= assignments.lastIndex) return@launch
            val current = assignments[index]
            val next = assignments[index + 1]
            epgSourceRepository.updateAssignmentPriority(providerId, current.epgSourceId, next.priority)
            epgSourceRepository.updateAssignmentPriority(providerId, next.epgSourceId, current.priority)
            refreshProviderEpgSummary(providerId)
        }
    }

    private suspend fun refreshProviderEpgSummary(providerId: Long) {
        val summary = epgSourceRepository.getResolutionSummary(providerId)
        _uiState.update {
            it.copy(epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary))
        }
    }

    private fun loadedProvidersForSource(sourceId: Long): List<Long> =
        _uiState.value.epgSourceAssignments
            .filterValues { assignments -> assignments.any { it.epgSourceId == sourceId } }
            .keys
            .toList()

    private suspend fun refreshLoadedResolutionSummaries(providerIds: Iterable<Long>) {
        providerIds
            .asSequence()
            .distinct()
            .forEach { providerId ->
                refreshProviderEpgSummary(providerId)
            }
    }

    private fun buildCapabilitySummary(provider: Provider): String {
        return when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                if (provider.epgUrl.isNotBlank()) {
                    appContext.getString(R.string.settings_capability_xtream_with_epg)
                } else {
                    appContext.getString(R.string.settings_capability_xtream_without_epg)
                }
            }
            ProviderType.M3U -> {
                if (provider.epgUrl.isNotBlank()) {
                    appContext.getString(R.string.settings_capability_m3u_with_epg)
                } else {
                    appContext.getString(R.string.settings_capability_m3u_without_epg)
                }
            }
        }
    }
}

data class ProviderDiagnosticsUiModel(
    val lastSyncStatus: String = "NONE",
    val lastLiveSync: Long = 0L,
    val lastMovieSync: Long = 0L,
    val lastMovieAttempt: Long = 0L,
    val lastMovieSuccess: Long = 0L,
    val lastMoviePartial: Long = 0L,
    val lastSeriesSync: Long = 0L,
    val lastEpgSync: Long = 0L,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val epgCount: Int = 0,
    val movieSyncMode: VodSyncMode = VodSyncMode.UNKNOWN,
    val movieWarningsCount: Int = 0,
    val movieCatalogStale: Boolean = false,
    val capabilitySummary: String = "",
    val sourceLabel: String = "",
    val expirySummary: String = "",
    val connectionSummary: String = "",
    val archiveSummary: String = ""
)

data class SettingsUiState(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: Long? = null,
    val isSyncing: Boolean = false,
    val userMessage: String? = null,
    val syncWarningsByProvider: Map<Long, List<String>> = emptyMap(),
    val diagnosticsByProvider: Map<Long, ProviderDiagnosticsUiModel> = emptyMap(),
    val parentalControlLevel: Int = 0,
    val hasParentalPin: Boolean = false,
    val appLanguage: String = "system",
    val preferredAudioLanguage: String = "auto",
    val playerPlaybackSpeed: Float = 1f,
    val subtitleTextScale: Float = 1f,
    val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackgroundColor: Int = 0x80000000.toInt(),
    val wifiMaxVideoHeight: Int? = null,
    val ethernetMaxVideoHeight: Int? = null,
    val lastSpeedTest: InternetSpeedTestUiModel? = null,
    val isRunningInternetSpeedTest: Boolean = false,
    val backupPreview: BackupPreview? = null,
    val pendingBackupUri: String? = null,
    val backupImportPlan: BackupImportPlan = BackupImportPlan(),
    val recordingItems: List<RecordingItem> = emptyList(),
    val recordingStorageState: RecordingStorageState = RecordingStorageState(),
    val isIncognitoMode: Boolean = false,
    val useXtreamTextClassification: Boolean = true,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.PRO,
    val liveTvCategoryFilters: List<String> = emptyList(),
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
    val liveChannelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP,
    val vodViewMode: VodViewMode = VodViewMode.MODERN,
    val preventStandbyDuringPlayback: Boolean = true,
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList(),
    val epgSources: List<com.streamvault.domain.model.EpgSource> = emptyList(),
    val epgSourceAssignments: Map<Long, List<com.streamvault.domain.model.ProviderEpgSourceAssignment>> = emptyMap(),
    val epgResolutionSummaries: Map<Long, EpgResolutionSummary> = emptyMap()
)

private fun ProviderSyncSelection.label(application: Application): String = when (this) {
    ProviderSyncSelection.ALL -> application.getString(R.string.settings_sync_option_all)
    ProviderSyncSelection.FAST -> application.getString(R.string.settings_sync_option_fast)
    ProviderSyncSelection.TV -> application.getString(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> application.getString(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> application.getString(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> application.getString(R.string.settings_sync_option_epg)
}

data class InternetSpeedTestUiModel(
    val megabitsPerSecond: Double,
    val measuredAtMs: Long,
    val transportLabel: String,
    val recommendedMaxVideoHeight: Int?,
    val isEstimated: Boolean
)

private data class CategoryManagementSnapshot(
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList()
)
