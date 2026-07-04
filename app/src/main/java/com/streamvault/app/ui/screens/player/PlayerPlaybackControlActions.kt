package com.streamvault.app.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import kotlinx.coroutines.launch

private const val PLAYBACK_CONTROL_MUTE_TOGGLE_DEBOUNCE_MS = 250L

fun PlayerViewModel.seekForward() {
    notifyUserActivity()
    playerEngine.seekForward()
}

fun PlayerViewModel.seekBackward() {
    notifyUserActivity()
    playerEngine.seekBackward()
}

fun PlayerViewModel.seekToLiveEdge() {
    notifyUserActivity()
    playerEngine.seekToLiveEdge()
}

fun PlayerViewModel.playEpisode(episode: Episode, showResumePrompt: Boolean = true) {
    prepare(
        streamUrl = episode.streamUrl,
        epgChannelId = null,
        internalChannelId = episode.id,
        categoryId = -1,
        providerId = episode.providerId,
        isVirtual = false,
        contentType = ContentType.SERIES_EPISODE.name,
        title = buildEpisodePlaybackTitle(episode),
        artworkUrl = episode.coverUrl ?: currentSeries.value?.posterUrl ?: currentSeries.value?.backdropUrl,
        seriesId = currentSeriesId ?: episode.seriesId.takeIf { it > 0L },
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        showResumePrompt = showResumePrompt
    )
}

fun PlayerViewModel.toggleMute() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastMuteToggleAtMs < PLAYBACK_CONTROL_MUTE_TOGGLE_DEBOUNCE_MS) return
    lastMuteToggleAtMs = now
    playerEngine.toggleMute()
    val muted = playerEngine.isMuted.value
    mutePersistJob?.cancel()
    mutePersistJob = viewModelScope.launch {
        preferencesRepository.setPlayerMuted(muted)
    }
}

fun PlayerViewModel.toggleControls() {
    closeChannelInfoOverlay()
    showControlsFlow.value = !showControlsFlow.value
    if (!showControlsFlow.value) {
        clearSeekPreview()
    }
}

fun PlayerViewModel.toggleAspectRatio() {
    val nextRatio = when (_aspectRatio.value) {
        AspectRatio.FIT -> AspectRatio.FILL
        AspectRatio.FILL -> AspectRatio.ZOOM
        AspectRatio.ZOOM -> AspectRatio.FIT
    }
    _aspectRatio.value = nextRatio

    // channel_preferences.channel_id has a FK to the live-channels table; movie and
    // episode content ids are not in it, so persisting for VOD crashes with a
    // SQLITE_CONSTRAINT_FOREIGNKEY. Persist only for live; VOD keeps the chosen
    // ratio for the session. The write is also best-effort: a preference persist
    // must never take playback down.
    if (currentContentType == ContentType.LIVE && currentContentId != -1L) {
        viewModelScope.launch {
            runCatching {
                preferencesRepository.setAspectRatioForChannel(currentContentId, nextRatio.name)
            }.onFailure { Log.w("PlayerControls", "aspect ratio persist failed", it) }
        }
    }
}

fun PlayerViewModel.dismissResumePrompt(resume: Boolean) {
    val prompt = _resumePrompt.value
    _resumePrompt.value = ResumePromptState()
    if (resume && prompt.positionMs > 0) {
        playerEngine.seekTo(prompt.positionMs)
    }
    playerEngine.play()
}

fun PlayerViewModel.play() {
    notifyUserActivity()
    if (
        currentContentType == ContentType.LIVE &&
        timeshiftConfig.enabled &&
        timeshiftUiState.value.engineState.status == LiveTimeshiftStatus.PAUSED_BEHIND_LIVE
    ) {
        playerEngine.resumeTimeshift()
    } else {
        playerEngine.play()
    }
}

fun PlayerViewModel.pause() {
    notifyUserActivity()
    if (currentContentType == ContentType.LIVE && timeshiftConfig.enabled) {
        playerEngine.pauseTimeshift()
    } else {
        playerEngine.pause()
    }
}