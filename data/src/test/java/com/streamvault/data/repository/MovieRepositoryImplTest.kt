package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MovieRepositoryImplTest {

    private val movieDao: MovieDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()

    @Test
    fun `getRecommendations prioritizes movies similar to recent history`() = runTest {
        val watchedMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            cast = "Ava Stone",
            categoryId = 10L,
            rating = 7.2f
        )
        val recommendedMovie = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            cast = "Ava Stone",
            categoryId = 10L,
            rating = 8.8f
        )
        val unrelatedMovie = movieEntity(
            id = 3L,
            name = "Quiet Tea",
            genre = "Drama",
            cast = "Noah Reed",
            categoryId = 11L,
            rating = 9.5f
        )

        stubVisibleMovies(listOf(watchedMovie, recommendedMovie, unrelatedMovie))
        whenever(favoriteDao.getAllByType(ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getRecentlyWatchedByProvider(eq(7L), any())).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistoryEntity(
                        contentId = watchedMovie.id,
                        contentType = ContentType.MOVIE,
                        providerId = 7L,
                        title = watchedMovie.name,
                        lastWatchedAt = 1_000L,
                        resumePositionMs = 1_000L,
                        totalDurationMs = 10_000L
                    )
                )
            )
        )

        val repository = createRepository()

        val result = repository.getRecommendations(7L, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Quiet Tea").inOrder()
    }

    @Test
    fun `getRelatedContent ranks shared genre and cast ahead of category only matches`() = runTest {
        val targetMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            cast = "Ava Stone",
            categoryId = 10L,
            rating = 7.2f
        )
        val closeMatch = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            cast = "Ava Stone",
            categoryId = 10L,
            rating = 8.8f
        )
        val categoryOnly = movieEntity(
            id = 3L,
            name = "Harbor Escape",
            genre = "Thriller",
            cast = "Milo Hart",
            categoryId = 10L,
            rating = 9.0f
        )

        stubVisibleMovies(listOf(targetMovie, closeMatch, categoryOnly))

        val repository = createRepository()

        val result = repository.getRelatedContent(7L, targetMovie.id, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Harbor Escape").inOrder()
    }

    private fun createRepository() = MovieRepositoryImpl(
        movieDao = movieDao,
        categoryDao = categoryDao,
        preferencesRepository = preferencesRepository,
        favoriteDao = favoriteDao,
        playbackHistoryDao = playbackHistoryDao,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver
    )

    private fun stubVisibleMovies(movies: List<MovieEntity>) {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getByProvider(7L)).thenReturn(flowOf(movies))
    }

    private fun movieEntity(
        id: Long,
        name: String,
        genre: String,
        cast: String,
        categoryId: Long,
        rating: Float
    ) = MovieEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        cast = cast,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )
}