package com.streamvault.domain.usecase

import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.MovieRepository
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class GetRecommendations @Inject constructor(
    private val movieRepository: MovieRepository
) {
    private val logger = Logger.getLogger("GetRecommendations")

    operator fun invoke(providerId: Long, limit: Int = 12): Flow<List<Movie>> {
        return movieRepository.getRecommendations(providerId, limit)
            .flatMapLatest { recommended ->
                if (recommended.isNotEmpty()) {
                    flowOf(recommended.take(limit))
                } else {
                    movieRepository.getTopRatedPreview(providerId, limit)
                        .map { fallback -> fallback.take(limit) }
                }
            }
            .catch { error ->
                logger.log(Level.WARNING, "Failed to load recommendations", error)
                emit(emptyList())
            }
    }
}