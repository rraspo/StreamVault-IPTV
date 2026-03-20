package com.streamvault.app.ui.screens.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.data.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EpgUiState(
    val currentProviderName: String? = null,
    val providerSourceLabel: String = "",
    val providerArchiveSummary: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Long = ChannelRepository.ALL_CHANNELS_ID,
    val programSearchQuery: String = "",
    val showScheduledOnly: Boolean = false,
    val selectedChannelMode: GuideChannelMode = GuideChannelMode.ALL,
    val selectedDensity: GuideDensity = GuideDensity.COMFORTABLE,
    val showFavoritesOnly: Boolean = false,
    val favoriteChannelIds: Set<Long> = emptySet(),
    val channels: List<Channel> = emptyList(),
    val programsByChannel: Map<String, List<Program>> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val totalChannelCount: Int = 0,
    val channelsWithSchedule: Int = 0,
    val failedScheduleCount: Int = 0,
    val lastUpdatedAt: Long? = null,
    val isGuideStale: Boolean = false,
    val guideAnchorTime: Long = System.currentTimeMillis(),
    val guideWindowStart: Long = System.currentTimeMillis() - EpgViewModel.LOOKBACK_MS,
    val guideWindowEnd: Long = System.currentTimeMillis() + EpgViewModel.LOOKAHEAD_MS
)

enum class GuideChannelMode {
    ALL,
    ANCHORED,
    ARCHIVE_READY
}

enum class GuideDensity {
    COMPACT,
    COMFORTABLE,
    CINEMATIC
}

private data class GuideProgramsResult(
    val programsByChannel: Map<String, List<Program>>,
    val failedCount: Int
)

private data class GuideChannelSelection(
    val channels: List<Channel>,
    val favoriteChannelIds: Set<Long>
)

@HiltViewModel
class EpgViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    companion object {
        const val MAX_CHANNELS = 60
        const val LOOKBACK_MS = 60 * 60 * 1000L
        const val LOOKAHEAD_MS = 6 * 60 * 60 * 1000L
        const val HALF_HOUR_SHIFT_MS = 30 * 60 * 1000L
        const val WINDOW_SHIFT_MS = 3 * 60 * 60 * 1000L
        const val PAGE_SHIFT_MS = LOOKBACK_MS + LOOKAHEAD_MS
        const val DAY_SHIFT_MS = 24 * 60 * 60 * 1000L
        const val PRIME_TIME_HOUR = 20
        const val NO_ACTIVE_PROVIDER = "NO_ACTIVE_PROVIDER"
    }

    private val _uiState = MutableStateFlow(EpgUiState())
    val uiState: StateFlow<EpgUiState> = _uiState.asStateFlow()

    private val selectedCategoryId = MutableStateFlow(ChannelRepository.ALL_CHANNELS_ID)
    private val guideAnchorTime = MutableStateFlow(System.currentTimeMillis())
    private val showScheduledOnly = MutableStateFlow(false)
    private val selectedChannelMode = MutableStateFlow(GuideChannelMode.ALL)
    private val selectedDensity = MutableStateFlow(GuideDensity.COMFORTABLE)
    private val showFavoritesOnly = MutableStateFlow(false)
    private val programSearchQuery = MutableStateFlow("")
    private val refreshNonce = MutableStateFlow(0)

    init {
        restoreGuidePreferences()
        observeGuide()
    }

    fun selectCategory(categoryId: Long) {
        if (selectedCategoryId.value == categoryId) return
        selectedCategoryId.value = categoryId
    }

    fun updateProgramSearchQuery(query: String) {
        programSearchQuery.value = query
    }

    fun clearProgramSearch() {
        if (programSearchQuery.value.isBlank()) return
        programSearchQuery.value = ""
    }

    fun refresh() {
        refreshNonce.update { it + 1 }
    }

    fun jumpToNow() {
        updateGuideAnchorTime(System.currentTimeMillis())
    }

    fun jumpForwardHalfHour() {
        updateGuideAnchorTime(guideAnchorTime.value + HALF_HOUR_SHIFT_MS)
    }

    fun jumpBackwardHalfHour() {
        updateGuideAnchorTime((guideAnchorTime.value - HALF_HOUR_SHIFT_MS).coerceAtLeast(0L))
    }

    fun jumpForward() {
        updateGuideAnchorTime(guideAnchorTime.value + WINDOW_SHIFT_MS)
    }

    fun jumpBackward() {
        updateGuideAnchorTime((guideAnchorTime.value - WINDOW_SHIFT_MS).coerceAtLeast(0L))
    }

    fun pageBackward() {
        updateGuideAnchorTime((guideAnchorTime.value - PAGE_SHIFT_MS).coerceAtLeast(0L))
    }

    fun pageForward() {
        updateGuideAnchorTime(guideAnchorTime.value + PAGE_SHIFT_MS)
    }

    fun jumpToTomorrow() {
        updateGuideAnchorTime(System.currentTimeMillis() + DAY_SHIFT_MS)
    }

    fun jumpToPrimeTime() {
        val selectedDayStart = (guideAnchorTime.value / DAY_SHIFT_MS) * DAY_SHIFT_MS
        updateGuideAnchorTime(selectedDayStart + (PRIME_TIME_HOUR * 60 * 60 * 1000L))
    }

    fun jumpToPreviousDay() {
        updateGuideAnchorTime((guideAnchorTime.value - DAY_SHIFT_MS).coerceAtLeast(0L))
    }

    fun jumpToNextDay() {
        updateGuideAnchorTime(guideAnchorTime.value + DAY_SHIFT_MS)
    }

    fun jumpToDay(dayStartMillis: Long) {
        val currentTimeOfDay = guideAnchorTime.value
            .mod(DAY_SHIFT_MS)
            .let { if (it < 0L) it + DAY_SHIFT_MS else it }
        updateGuideAnchorTime(dayStartMillis + currentTimeOfDay)
    }

    fun toggleScheduledOnly() {
        val enabled = !showScheduledOnly.value
        showScheduledOnly.value = enabled
        viewModelScope.launch {
            preferencesRepository.setGuideScheduledOnly(enabled)
        }
    }

    fun selectChannelMode(mode: GuideChannelMode) {
        selectedChannelMode.value = mode
        viewModelScope.launch {
            preferencesRepository.setGuideChannelMode(mode.name)
        }
    }

    fun selectDensity(density: GuideDensity) {
        selectedDensity.value = density
        viewModelScope.launch {
            preferencesRepository.setGuideDensity(density.name)
        }
    }

    fun toggleFavoritesOnly() {
        val enabled = !showFavoritesOnly.value
        showFavoritesOnly.value = enabled
        viewModelScope.launch {
            preferencesRepository.setGuideFavoritesOnly(enabled)
        }
    }

    fun applyNavigationContext(
        categoryId: Long?,
        anchorTime: Long?,
        favoritesOnly: Boolean?
    ) {
        categoryId?.let { requested ->
            selectedCategoryId.value = requested
        }
        anchorTime?.takeIf { it > 0L }?.let { requested ->
            guideAnchorTime.value = requested
        }
        favoritesOnly?.let { requested ->
            showFavoritesOnly.value = requested
        }
    }

    private fun observeGuide() {
        viewModelScope.launch {
            providerRepository.getActiveProvider().collectLatest { provider ->
                if (provider == null) {
                    _uiState.update {
                        it.copy(
                            currentProviderName = null,
                            providerSourceLabel = "",
                            providerArchiveSummary = "",
                            categories = emptyList(),
                            channels = emptyList(),
                            programsByChannel = emptyMap(),
                            programSearchQuery = "",
                            isLoading = false,
                            error = NO_ACTIVE_PROVIDER,
                            totalChannelCount = 0,
                            channelsWithSchedule = 0,
                            failedScheduleCount = 0,
                            lastUpdatedAt = null,
                            isGuideStale = false,
                            guideAnchorTime = System.currentTimeMillis(),
                            guideWindowStart = System.currentTimeMillis() - LOOKBACK_MS,
                            guideWindowEnd = System.currentTimeMillis() + LOOKAHEAD_MS
                        )
                    }
                    return@collectLatest
                }

                channelRepository.getCategories(provider.id).combine(
                    combine(
                        combine(
                            combine(
                                selectedCategoryId,
                                programSearchQuery,
                                guideAnchorTime
                            ) { requestedCategoryId, searchQuery, anchorTime ->
                                Triple(requestedCategoryId, searchQuery, anchorTime)
                            },
                            showScheduledOnly,
                            selectedChannelMode,
                            selectedDensity
                        ) { selectionTriple, scheduledOnly, channelMode, density ->
                            val (requestedCategoryId, searchQuery, anchorTime) = selectionTriple
                            GuideSelectionState(
                                requestedCategoryId = requestedCategoryId,
                                programSearchQuery = searchQuery,
                                anchorTime = anchorTime,
                                scheduledOnly = scheduledOnly,
                                channelMode = channelMode,
                                density = density,
                                favoritesOnly = false
                            )
                        },
                        showFavoritesOnly
                    ) { selection, favoritesOnly ->
                        selection.copy(favoritesOnly = favoritesOnly)
                    }
                ) { categories, selection ->
                    GuideRequest(
                        categories = categories,
                        requestedCategoryId = selection.requestedCategoryId,
                        programSearchQuery = selection.programSearchQuery,
                        anchorTime = selection.anchorTime,
                        scheduledOnly = selection.scheduledOnly,
                        channelMode = selection.channelMode,
                        density = selection.density,
                        favoritesOnly = selection.favoritesOnly
                    )
                }.combine(refreshNonce) { request, _ ->
                    request
                }.collectLatest { request ->
                    val providerCategories = request.categories
                    val requestedCategoryId = request.requestedCategoryId
                    val anchorTime = request.anchorTime
                    val categories = buildList {
                        add(Category(id = ChannelRepository.ALL_CHANNELS_ID, name = "All Channels"))
                        addAll(providerCategories.sortedBy { it.name.lowercase() })
                    }

                    val resolvedCategoryId = requestedCategoryId.takeIf { categoryId ->
                        categoryId == ChannelRepository.ALL_CHANNELS_ID || providerCategories.any { it.id == categoryId }
                    } ?: ChannelRepository.ALL_CHANNELS_ID

                    _uiState.update {
                        it.copy(
                            currentProviderName = provider.name,
                            providerSourceLabel = when (provider.type) {
                                com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                                com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                            },
                            providerArchiveSummary = buildProviderArchiveSummary(provider),
                            categories = categories,
                            selectedCategoryId = resolvedCategoryId,
                            programSearchQuery = request.programSearchQuery,
                            showScheduledOnly = request.scheduledOnly,
                            selectedChannelMode = request.channelMode,
                            selectedDensity = request.density,
                            showFavoritesOnly = request.favoritesOnly,
                            guideAnchorTime = anchorTime,
                            guideWindowStart = anchorTime - LOOKBACK_MS,
                            guideWindowEnd = anchorTime + LOOKAHEAD_MS,
                            isLoading = true,
                            error = null
                        )
                    }

                    combine(
                        channelRepository.getChannelsByNumber(provider.id, resolvedCategoryId),
                        channelRepository.getChannelsWithoutErrors(provider.id, resolvedCategoryId),
                        favoriteRepository.getFavorites(ContentType.LIVE)
                    ) { channelsByNumber, healthyChannels, favorites ->
                        val favoriteIds = favorites.map { it.contentId }.toSet()
                        val preferredChannels = if (request.favoritesOnly) {
                            healthyChannels.filter { it.id in favoriteIds }
                                .ifEmpty { channelsByNumber.filter { it.id in favoriteIds } }
                        } else {
                            healthyChannels.ifEmpty { channelsByNumber }
                        }
                        if (!request.favoritesOnly) {
                            GuideChannelSelection(
                                channels = preferredChannels,
                                favoriteChannelIds = favoriteIds
                            )
                        } else {
                            GuideChannelSelection(
                                channels = preferredChannels,
                                favoriteChannelIds = favoriteIds
                            )
                        }
                    }.collectLatest { channelSelection ->
                        val visibleChannels = channelSelection.channels.take(MAX_CHANNELS)
                        val windowStart = anchorTime - LOOKBACK_MS
                        val windowEnd = anchorTime + LOOKAHEAD_MS
                        val guideResult = loadGuidePrograms(
                            providerId = provider.id,
                            channels = visibleChannels,
                            categoryId = resolvedCategoryId,
                            favoritesOnly = request.favoritesOnly,
                            searchQuery = request.programSearchQuery,
                            windowStart = windowStart,
                            windowEnd = windowEnd
                        )
                        val now = System.currentTimeMillis()
                        val channelsWithSchedule = guideResult.programsByChannel.count { it.value.isNotEmpty() }
                        val hasUpcomingData = guideResult.programsByChannel.values.flatten().any { program ->
                            program.endTime > windowStart
                        }
                        val displayChannels = visibleChannels.filter { channel ->
                            val programs = channel.epgChannelId?.let { epgId ->
                                guideResult.programsByChannel[epgId].orEmpty()
                            }.orEmpty()
                            val matchesScheduled = !request.scheduledOnly || programs.isNotEmpty()
                            val matchesMode = when (request.channelMode) {
                                GuideChannelMode.ALL -> true
                                GuideChannelMode.ANCHORED -> programs.any { program ->
                                    request.anchorTime in program.startTime until program.endTime
                                }
                                GuideChannelMode.ARCHIVE_READY -> {
                                    channel.catchUpSupported || programs.any { it.hasArchive }
                                }
                            }
                            matchesScheduled && matchesMode
                        }

                        _uiState.update {
                            it.copy(
                                favoriteChannelIds = channelSelection.favoriteChannelIds,
                                channels = displayChannels,
                                programsByChannel = guideResult.programsByChannel,
                                isLoading = false,
                                error = null,
                                totalChannelCount = channelSelection.channels.size,
                                channelsWithSchedule = channelsWithSchedule,
                                failedScheduleCount = guideResult.failedCount,
                                lastUpdatedAt = now,
                                isGuideStale = visibleChannels.isNotEmpty() && (channelsWithSchedule == 0 || !hasUpcomingData),
                                guideAnchorTime = request.anchorTime,
                                guideWindowStart = windowStart,
                                guideWindowEnd = windowEnd
                            )
                        }
                    }
                }
            }
        }
    }

    private fun restoreGuidePreferences() {
        viewModelScope.launch {
            preferencesRepository.guideDensity.first()
                ?.let { saved ->
                    GuideDensity.entries.firstOrNull { it.name == saved }?.let { density ->
                        selectedDensity.value = density
                    }
                }
            preferencesRepository.guideChannelMode.first()
                ?.let { saved ->
                    GuideChannelMode.entries.firstOrNull { it.name == saved }?.let { mode ->
                        selectedChannelMode.value = mode
                    }
                }
            showFavoritesOnly.value = preferencesRepository.guideFavoritesOnly.first()
            showScheduledOnly.value = preferencesRepository.guideScheduledOnly.first()
            preferencesRepository.guideAnchorTime.first()
                ?.takeIf { it > 0L }
                ?.let { guideAnchorTime.value = it }
        }
    }

    private fun buildProviderArchiveSummary(provider: com.streamvault.domain.model.Provider): String {
        return when (provider.type) {
            com.streamvault.domain.model.ProviderType.XTREAM_CODES ->
                "Xtream replay depends on archive-enabled channels and valid replay stream ids from the provider."
            com.streamvault.domain.model.ProviderType.M3U ->
                if (provider.epgUrl.isBlank()) {
                    "M3U replay is limited: archive depends on provider templates and guide coverage is weaker without XMLTV."
                } else {
                    "M3U replay depends on the provider catch-up template and matching guide data."
                }
        }
    }

    private fun updateGuideAnchorTime(anchorTimeMs: Long) {
        guideAnchorTime.value = anchorTimeMs
        viewModelScope.launch {
            preferencesRepository.setGuideAnchorTime(anchorTimeMs)
        }
    }

    private suspend fun loadGuidePrograms(
        providerId: Long,
        channels: List<Channel>,
        categoryId: Long,
        favoritesOnly: Boolean,
        searchQuery: String,
        windowStart: Long,
        windowEnd: Long
    ): GuideProgramsResult {
        if (channels.isEmpty()) {
            return GuideProgramsResult(emptyMap(), failedCount = 0)
        }

        val epgIds = channels.mapNotNull { it.epgChannelId }.distinct()
        val programsByChannel = runCatching {
            val allowedIds = epgIds.toSet()
            when {
                searchQuery.trim().length >= 2 -> {
                    epgRepository.searchPrograms(
                        providerId = providerId,
                        query = searchQuery,
                        startTime = windowStart,
                        endTime = windowEnd,
                        categoryId = categoryId.takeUnless { it == ChannelRepository.ALL_CHANNELS_ID },
                        limit = maxOf(120, channels.size * 12)
                    ).first().filter { it.channelId in allowedIds }.groupBy { it.channelId }
                }

                categoryId != ChannelRepository.ALL_CHANNELS_ID && !favoritesOnly -> {
                    epgRepository.getProgramsByCategory(
                        providerId = providerId,
                        categoryId = categoryId,
                        startTime = windowStart,
                        endTime = windowEnd
                    ).first().filter { it.channelId in allowedIds }.groupBy { it.channelId }
                }

                else -> epgRepository.getProgramsForChannels(providerId, epgIds, windowStart, windowEnd).first()
            }
        }.getOrElse { emptyMap() }

        return GuideProgramsResult(
            programsByChannel = programsByChannel,
            failedCount = epgIds.count { epgId -> programsByChannel[epgId].isNullOrEmpty() }
        )
    }

}

private data class GuideRequest(
    val categories: List<Category>,
    val requestedCategoryId: Long,
    val programSearchQuery: String,
    val anchorTime: Long,
    val scheduledOnly: Boolean,
    val channelMode: GuideChannelMode,
    val density: GuideDensity,
    val favoritesOnly: Boolean
)

private data class GuideSelectionState(
    val requestedCategoryId: Long,
    val programSearchQuery: String,
    val anchorTime: Long,
    val scheduledOnly: Boolean,
    val channelMode: GuideChannelMode,
    val density: GuideDensity,
    val favoritesOnly: Boolean
)
