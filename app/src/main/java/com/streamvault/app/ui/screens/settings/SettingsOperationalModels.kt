package com.streamvault.app.ui.screens.settings

import com.streamvault.data.preferences.DatabaseMaintenanceSnapshot
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VodSyncMode

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
    val liveSequentialFailuresRemembered: Boolean = false,
    val liveHealthySyncStreak: Int = 0,
    val movieParallelFailuresRemembered: Boolean = false,
    val movieHealthySyncStreak: Int = 0,
    val seriesSequentialFailuresRemembered: Boolean = false,
    val seriesHealthySyncStreak: Int = 0,
    val capabilitySummary: String = "",
    val sourceLabel: String = "",
    val expirySummary: String = "",
    val connectionSummary: String = "",
    val archiveSummary: String = ""
) {
    val hasHealthWarning: Boolean
        get() = liveSequentialFailuresRemembered ||
            movieParallelFailuresRemembered ||
            seriesSequentialFailuresRemembered ||
            movieCatalogStale ||
            movieWarningsCount > 0
}

data class DatabaseMaintenanceUiModel(
    val ranAt: Long,
    val deletedPrograms: Int,
    val deletedExternalProgrammes: Int,
    val deletedOrphanEpisodes: Int,
    val deletedStaleFavorites: Int,
    val vacuumRan: Boolean,
    val mainDbBytes: Long,
    val walBytes: Long,
    val reclaimableBytes: Long,
    val channelRows: Long,
    val movieRows: Long,
    val seriesRows: Long,
    val episodeRows: Long,
    val programRows: Long,
    val epgProgrammeRows: Long,
    val playbackHistoryRows: Long,
    val favoriteRows: Long
)

internal fun DatabaseMaintenanceSnapshot.toUiModel(): DatabaseMaintenanceUiModel =
    DatabaseMaintenanceUiModel(
        ranAt = ranAt,
        deletedPrograms = deletedPrograms,
        deletedExternalProgrammes = deletedExternalProgrammes,
        deletedOrphanEpisodes = deletedOrphanEpisodes,
        deletedStaleFavorites = deletedStaleFavorites,
        vacuumRan = vacuumRan,
        mainDbBytes = mainDbBytes,
        walBytes = walBytes,
        reclaimableBytes = reclaimableBytes,
        channelRows = channelRows,
        movieRows = movieRows,
        seriesRows = seriesRows,
        episodeRows = episodeRows,
        programRows = programRows,
        epgProgrammeRows = epgProgrammeRows,
        playbackHistoryRows = playbackHistoryRows,
        favoriteRows = favoriteRows
    )

data class InternetSpeedTestUiModel(
    val megabitsPerSecond: Double,
    val measuredAtMs: Long,
    val transportLabel: String,
    val recommendedMaxVideoHeight: Int?,
    val isEstimated: Boolean
)

internal data class CategoryManagementSnapshot(
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList()
)