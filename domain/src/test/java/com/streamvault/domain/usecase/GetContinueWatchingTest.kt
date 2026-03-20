package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GetContinueWatchingTest {

    @Test
    fun collapses_multiple_episode_entries_into_one_series_resume() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 21L, type = ContentType.SERIES_EPISODE, seriesId = 7L, lastWatchedAt = 300L, resumePositionMs = 50_000L),
                    history(contentId = 20L, type = ContentType.SERIES_EPISODE, seriesId = 7L, lastWatchedAt = 200L, resumePositionMs = 40_000L),
                    history(contentId = 11L, type = ContentType.MOVIE, lastWatchedAt = 100L, resumePositionMs = 10_000L)
                )
            )
        )

        val result = useCase(providerId = 1L, limit = 5).collectOnce()

        assertThat(result).hasSize(2)
        assertThat(result.first().contentId).isEqualTo(21L)
        assertThat(result.last().contentId).isEqualTo(11L)
    }

    @Test
    fun movie_scope_keeps_only_movies() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 1L, type = ContentType.MOVIE, lastWatchedAt = 300L, resumePositionMs = 15_000L),
                    history(contentId = 2L, type = ContentType.SERIES_EPISODE, seriesId = 9L, lastWatchedAt = 200L, resumePositionMs = 15_000L)
                )
            )
        )

        val result = useCase(providerId = 1L, scope = ContinueWatchingScope.MOVIES).collectOnce()

        assertThat(result.map { it.contentId }).containsExactly(1L)
    }

    @Test
    fun require_resume_position_filters_out_unstarted_movies_and_episodes() = runTest {
        val useCase = GetContinueWatching(
            playbackHistoryRepository = FakePlaybackHistoryRepository(
                history = listOf(
                    history(contentId = 1L, type = ContentType.MOVIE, lastWatchedAt = 400L, resumePositionMs = 0L),
                    history(contentId = 2L, type = ContentType.SERIES, seriesId = 2L, lastWatchedAt = 300L, resumePositionMs = 0L),
                    history(contentId = 3L, type = ContentType.SERIES_EPISODE, seriesId = 3L, lastWatchedAt = 200L, resumePositionMs = 0L),
                    history(contentId = 4L, type = ContentType.MOVIE, lastWatchedAt = 100L, resumePositionMs = 25_000L)
                )
            )
        )

        val result = useCase(providerId = 1L, requireResumePosition = true).collectOnce()

        assertThat(result.map { it.contentId }).containsExactly(2L, 4L).inOrder()
    }

    private suspend fun Flow<List<PlaybackHistory>>.collectOnce(): List<PlaybackHistory> = first()

    private fun history(
        contentId: Long,
        type: ContentType,
        seriesId: Long? = null,
        lastWatchedAt: Long,
        resumePositionMs: Long
    ) = PlaybackHistory(
        contentId = contentId,
        contentType = type,
        providerId = 1L,
        title = "$type-$contentId",
        streamUrl = "https://example.com/$contentId",
        resumePositionMs = resumePositionMs,
        totalDurationMs = 120_000L,
        lastWatchedAt = lastWatchedAt,
        seriesId = seriesId
    )

    private class FakePlaybackHistoryRepository(
        private val history: List<PlaybackHistory>
    ) : PlaybackHistoryRepository {
        override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> = flowOf(history.take(limit))
        override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> = flowOf(history.take(limit))
        override fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int> = flowOf(0)
        override suspend fun getPlaybackHistory(contentId: Long, contentType: ContentType, providerId: Long): PlaybackHistory? = null
        override suspend fun markAsWatched(history: PlaybackHistory) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun recordPlayback(history: PlaybackHistory) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun updateResumePosition(history: PlaybackHistory) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long) = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun clearAllHistory() = com.streamvault.domain.model.Result.success(Unit)
        override suspend fun clearHistoryForProvider(providerId: Long) = com.streamvault.domain.model.Result.success(Unit)
    }
}