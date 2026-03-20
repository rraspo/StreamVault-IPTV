package com.streamvault.domain.usecase

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

enum class SearchContentScope {
    ALL,
    LIVE,
    MOVIES,
    SERIES
}

data class SearchContentResult(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList()
)

class SearchContent @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository
) {
    private val logger = Logger.getLogger("SearchContent")

    operator fun invoke(
        providerId: Long,
        query: String,
        scope: SearchContentScope = SearchContentScope.ALL,
        maxResultsPerSection: Int = 120
    ): Flow<SearchContentResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return flowOf(SearchContentResult())
        }

        return combine(
            if (scope == SearchContentScope.ALL || scope == SearchContentScope.LIVE) {
                channelRepository.searchChannels(providerId, normalizedQuery)
            } else {
                flowOf(emptyList())
            },
            if (scope == SearchContentScope.ALL || scope == SearchContentScope.MOVIES) {
                movieRepository.searchMovies(providerId, normalizedQuery)
            } else {
                flowOf(emptyList())
            },
            if (scope == SearchContentScope.ALL || scope == SearchContentScope.SERIES) {
                seriesRepository.searchSeries(providerId, normalizedQuery)
            } else {
                flowOf(emptyList())
            }
        ) { channels, movies, series ->
            SearchContentResult(
                channels = channels.take(maxResultsPerSection),
                movies = movies.take(maxResultsPerSection),
                series = series.take(maxResultsPerSection)
            )
        }.catch { error ->
            logger.log(Level.WARNING, "Failed to build search results", error)
            emit(SearchContentResult())
        }
    }
}