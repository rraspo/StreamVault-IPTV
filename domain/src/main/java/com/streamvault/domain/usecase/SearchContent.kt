package com.streamvault.domain.usecase

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.util.shouldRethrowDomainFlowFailure
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

enum class SearchContentScope {
    ALL,
    LIVE,
    MOVIES,
    SERIES
}

data class SearchContentResult(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val isPartialResult: Boolean = false
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
                    .map { it to false }
                    .catch { error ->
                        if (error.shouldRethrowDomainFlowFailure()) throw error
                        logger.log(Level.WARNING, "Channel search section failed", error)
                        emit(emptyList<Channel>() to true)
                    }
            } else {
                flowOf(emptyList<Channel>() to false)
            },
            if (scope == SearchContentScope.ALL || scope == SearchContentScope.MOVIES) {
                movieRepository.searchMovies(providerId, normalizedQuery)
                    .map { it to false }
                    .catch { error ->
                        if (error.shouldRethrowDomainFlowFailure()) throw error
                        logger.log(Level.WARNING, "Movie search section failed", error)
                        emit(emptyList<Movie>() to true)
                    }
            } else {
                flowOf(emptyList<Movie>() to false)
            },
            if (scope == SearchContentScope.ALL || scope == SearchContentScope.SERIES) {
                seriesRepository.searchSeries(providerId, normalizedQuery)
                    .map { it to false }
                    .catch { error ->
                        if (error.shouldRethrowDomainFlowFailure()) throw error
                        logger.log(Level.WARNING, "Series search section failed", error)
                        emit(emptyList<Series>() to true)
                    }
            } else {
                flowOf(emptyList<Series>() to false)
            }
        ) { (channels, channelsDegraded), (movies, moviesDegraded), (series, seriesDegraded) ->
            SearchContentResult(
                channels = channels.take(maxResultsPerSection),
                movies = movies.take(maxResultsPerSection),
                series = series.take(maxResultsPerSection),
                isPartialResult = channelsDegraded || moviesDegraded || seriesDegraded
            )
        }
    }
}