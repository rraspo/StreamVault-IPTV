package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlayerSurfaceMode
import kotlinx.coroutines.launch

internal fun PlayerViewModel.applyPrepareSessionState(
    streamUrl: String,
    internalChannelId: Long,
    categoryId: Long,
    providerId: Long,
    combinedProfileId: Long?,
    combinedSourceFilterProviderId: Long?,
    contentType: String,
    title: String,
    artworkUrl: String?,
    seriesId: Long?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    hasArchiveRequest: Boolean,
    preferredDecoderMode: DecoderMode,
    preferredSurfaceMode: PlayerSurfaceMode
): Boolean {
    val previousProviderId = currentProviderId
    val previousCategoryId = currentCategoryId
    val previousCombinedProfileId = currentCombinedProfileId
    val previousCombinedSourceFilterProviderId = currentCombinedSourceFilterProviderId
    val shouldReloadPlaylist = categoryId != -1L &&
        (
            categoryId != previousCategoryId ||
                providerId != previousProviderId ||
                combinedProfileId != previousCombinedProfileId ||
                combinedSourceFilterProviderId != previousCombinedSourceFilterProviderId
            )

    clearSeekPreview()
    currentResolvedPlaybackUrl = ""
    currentStreamUrl = streamUrl
    currentContentId = internalChannelId
    currentTitle = title
    playbackTitleFlow.value = title
    currentArtworkUrl = artworkUrl
    currentContentType = try {
        ContentType.valueOf(contentType)
    } catch (_: Exception) {
        ContentType.LIVE
    }
    currentProviderId = providerId
    currentCombinedProfileId = combinedProfileId?.takeIf { it > 0L }
    currentCombinedSourceFilterProviderId = combinedSourceFilterProviderId?.takeIf { it > 0L }
    currentSeriesId = seriesId?.takeIf { it > 0L }
    currentSeasonNumber = seasonNumber
    currentEpisodeNumber = episodeNumber
    val streamClassLabel = if (hasArchiveRequest) "Catch-up" else "Primary"
    applyDefaultPlaybackTimersIfNeeded()

    if (currentContentType == ContentType.LIVE && currentCombinedProfileId != null) {
        val activeCombinedProfileId = currentCombinedProfileId
        viewModelScope.launch {
            val members = activeCombinedProfileId?.let { combinedM3uRepository.getProfile(it)?.members }.orEmpty()
            if (currentCombinedProfileId == activeCombinedProfileId) {
                currentCombinedProfileMembers = members
            }
        }
    } else {
        currentCombinedProfileMembers = emptyList()
        combinedCategoriesById = emptyMap()
    }

    if (!hasArchiveRequest) {
        pendingCatchUpUrls = emptyList()
    }
    if (currentContentType != ContentType.SERIES_EPISODE || providerId <= 0 || currentSeriesId == null) {
        clearSeriesEpisodeContext()
    }
    if (currentContentType != ContentType.LIVE) {
        lastRecordedLivePlaybackKey = null
        recentChannelsJob?.cancel()
        recentChannelsFlow.value = emptyList()
        lastVisitedCategoryJob?.cancel()
        _lastVisitedCategory.value = null
        playerEngine.stopLiveTimeshift()
    }

    hasRetriedWithSoftwareDecoder = false
    playerEngine.setDecoderMode(preferredDecoderMode)
    playerEngine.setSurfaceMode(preferredSurfaceMode)
    updateDecoderMode(preferredDecoderMode)
    updateStreamClass(streamClassLabel)

    triedAlternativeStreams.clear()
    if (!hasArchiveRequest) {
        triedAlternativeStreams.add(streamUrl)
    }

    return shouldReloadPlaylist
}