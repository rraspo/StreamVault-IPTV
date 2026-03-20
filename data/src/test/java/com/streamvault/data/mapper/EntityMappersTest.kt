package com.streamvault.data.mapper

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelQualityOption
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import org.junit.Test

/**
 * Unit tests for entity ↔ domain mapper extension functions in [EntityMappers].
 *
 * Validates that every field survives a round-trip through the data layer without
 * mutation, truncation, or silent default substitution.
 */
class EntityMappersTest {

    // ── Provider ──────────────────────────────────────────────────

    @Test
    fun `provider_roundTrip_preservesAllFields`() {
        val original = Provider(
            id = 42L,
            name = "My Xtream Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://example.com:8080",
            username = "user123",
            password = "p@ssw0rd!",
            m3uUrl = "",
            epgUrl = "http://example.com/epg.xml",
            isActive = true,
            maxConnections = 3,
            expirationDate = 1_798_761_600_000L, // 2027-01-01 UTC as epoch ms
            apiVersion = "2.0.1",
            status = ProviderStatus.ACTIVE,
            lastSyncedAt = 1_700_000_000_000L,
            createdAt = 1_600_000_000_000L
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.id).isEqualTo(original.id)
        assertThat(roundTripped.name).isEqualTo(original.name)
        assertThat(roundTripped.type).isEqualTo(original.type)
        assertThat(roundTripped.serverUrl).isEqualTo(original.serverUrl)
        assertThat(roundTripped.username).isEqualTo(original.username)
        assertThat(roundTripped.password).isEqualTo(original.password)
        assertThat(roundTripped.m3uUrl).isEqualTo(original.m3uUrl)
        assertThat(roundTripped.epgUrl).isEqualTo(original.epgUrl)
        assertThat(roundTripped.isActive).isEqualTo(original.isActive)
        assertThat(roundTripped.maxConnections).isEqualTo(original.maxConnections)
        assertThat(roundTripped.expirationDate).isEqualTo(original.expirationDate)
        assertThat(roundTripped.apiVersion).isEqualTo(original.apiVersion)
        assertThat(roundTripped.status).isEqualTo(original.status)
        assertThat(roundTripped.lastSyncedAt).isEqualTo(original.lastSyncedAt)
        assertThat(roundTripped.createdAt).isEqualTo(original.createdAt)
    }

    @Test
    fun `playbackHistory_unknownWatchedStatus_defaultsToInProgress`() {
        val entity = PlaybackHistoryEntity(
            contentId = 10L,
            contentType = ContentType.MOVIE,
            providerId = 1L,
            watchedStatus = "NOT_A_REAL_STATUS"
        )

        val domain = entity.toDomain()

        assertThat(domain.watchedStatus).isEqualTo(PlaybackWatchedStatus.IN_PROGRESS)
    }

    // ── Channel ───────────────────────────────────────────────────

    @Test
    fun `channel_nullableFields_handledCorrectly`() {
        val channel = Channel(
            id = 99L,
            name = "Test Channel",
            logoUrl = null,        // intentionally null
            groupTitle = "Live",
            categoryId = null,     // intentionally null
            categoryName = "Live",
            streamUrl = "http://stream.example.com/99.ts",
            epgChannelId = null,
            number = 0,
            catchUpSupported = false,
            catchUpDays = 0,
            qualityOptions = listOf(ChannelQualityOption(label = "1080p", height = 1080, url = "http://stream.example.com/99_1080.ts")),
            providerId = 1L
        )

        val roundTripped = channel.toEntity().toDomain()

        assertThat(roundTripped.id).isEqualTo(99L)
        assertThat(roundTripped.logoUrl).isNull()
        assertThat(roundTripped.categoryId).isNull()
        assertThat(roundTripped.epgChannelId).isNull()
        assertThat(roundTripped.name).isEqualTo("Test Channel")
        assertThat(roundTripped.streamUrl).isEqualTo("http://stream.example.com/99.ts")
        assertThat(roundTripped.qualityOptions).containsExactly(
            ChannelQualityOption(label = "1080p", height = 1080, url = "http://stream.example.com/99_1080.ts")
        )
    }

    // ── Movie ─────────────────────────────────────────────────────

    @Test
    fun `movie_watchProgress_preserved`() {
        val movie = Movie(
            id = 1001L,
            name = "Inception",
            posterUrl = "http://poster.example.com/inception.jpg",
            categoryId = 5L,
            categoryName = "Sci-Fi",
            streamUrl = "http://vod.example.com/inception.mp4",
            providerId = 2L,
            watchProgress = 4_800_000L,   // 80 minutes into the film
            lastWatchedAt = 1_700_000_000_000L
        )

        val roundTripped = movie.toEntity().toDomain()

        assertThat(roundTripped.id).isEqualTo(1001L)
        assertThat(roundTripped.name).isEqualTo("Inception")
        assertThat(roundTripped.watchProgress).isEqualTo(4_800_000L)
        assertThat(roundTripped.lastWatchedAt).isEqualTo(1_700_000_000_000L)
    }
}
