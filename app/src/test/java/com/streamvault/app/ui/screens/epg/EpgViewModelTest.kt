package com.streamvault.app.ui.screens.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class EpgViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val channelRepository: ChannelRepository = mock()
    private val epgRepository: EpgRepository = mock()
    private val favoriteRepository: FavoriteRepository = mock()
    private val preferencesRepository: PreferencesRepository = mock()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `guide loads programs with one batched repository call`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val channels = listOf(
            Channel(id = 1L, name = "One", providerId = provider.id, epgChannelId = "one"),
            Channel(id = 2L, name = "Two", providerId = provider.id, epgChannelId = "two")
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannels(provider.id)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByNumber(provider.id, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrors(provider.id, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(flowOf(channels))
        whenever(favoriteRepository.getFavorites(ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(
            flowOf(
                mapOf(
                    "one" to listOf(
                        Program(
                            id = 1L,
                            channelId = "one",
                            title = "Headline",
                            startTime = System.currentTimeMillis() - 60_000L,
                            endTime = System.currentTimeMillis() + 60_000L,
                            providerId = provider.id
                        )
                    ),
                    "two" to emptyList()
                )
            )
        )

        val viewModel = EpgViewModel(
            providerRepository = providerRepository,
            channelRepository = channelRepository,
            epgRepository = epgRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = preferencesRepository
        )

        advanceUntilIdle()

        assertThat(viewModel.uiState.value.programsByChannel.keys).containsExactly("one", "two")
        verify(epgRepository).getProgramsForChannels(eq(provider.id), eq(listOf("one", "two")), any(), any())
        verify(epgRepository, never()).getProgramsForChannel(any(), any(), any(), any())
    }

    @Test
    fun `guide search uses repository program search within selected category`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val channels = listOf(
            Channel(id = 1L, name = "One", providerId = provider.id, epgChannelId = "one", categoryId = 10L),
            Channel(id = 2L, name = "Two", providerId = provider.id, epgChannelId = "two", categoryId = 10L)
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannels(provider.id)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategory(provider.id, 10L)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByNumber(provider.id, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByNumber(provider.id, 10L)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrors(provider.id, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrors(provider.id, 10L)).thenReturn(flowOf(channels))
        whenever(favoriteRepository.getFavorites(ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(flowOf(emptyMap()))
        whenever(epgRepository.searchPrograms(eq(provider.id), eq("Headline"), any(), any(), eq(10L), any())).thenReturn(
            flowOf(
                listOf(
                    Program(
                        id = 1L,
                        channelId = "one",
                        title = "Headline",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    )
                )
            )
        )

        val viewModel = EpgViewModel(
            providerRepository = providerRepository,
            channelRepository = channelRepository,
            epgRepository = epgRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = preferencesRepository
        )

        advanceUntilIdle()
        viewModel.selectCategory(10L)
        viewModel.updateProgramSearchQuery("Headline")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(1L, 2L)
        assertThat(viewModel.uiState.value.programsByChannel.keys).containsExactly("one")
        assertThat(viewModel.uiState.value.programSearchQuery).isEqualTo("Headline")
        verify(epgRepository).searchPrograms(eq(provider.id), eq("Headline"), any(), any(), eq(10L), any())
    }

    @Test
    fun `guide prefers healthy channels but falls back when favorite channels have errors`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val healthyChannel = Channel(
            id = 1L,
            name = "Healthy",
            providerId = provider.id,
            epgChannelId = "healthy",
            number = 1,
            categoryId = 10L
        )
        val unhealthyFavorite = Channel(
            id = 2L,
            name = "Unhealthy Favorite",
            providerId = provider.id,
            epgChannelId = "unhealthy",
            number = 2,
            categoryId = 10L,
            errorCount = 3
        )

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannelsByNumber(provider.id, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(
            flowOf(listOf(healthyChannel, unhealthyFavorite))
        )
        whenever(channelRepository.getChannelsWithoutErrors(provider.id, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(
            flowOf(listOf(healthyChannel))
        )
        whenever(channelRepository.getChannelsByNumber(provider.id, 10L)).thenReturn(
            flowOf(listOf(healthyChannel, unhealthyFavorite))
        )
        whenever(channelRepository.getChannelsWithoutErrors(provider.id, 10L)).thenReturn(
            flowOf(listOf(healthyChannel))
        )
        whenever(favoriteRepository.getFavorites(ContentType.LIVE)).thenReturn(
            flowOf(listOf(Favorite(contentId = unhealthyFavorite.id, contentType = ContentType.LIVE)))
        )
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(flowOf(emptyMap()))

        val viewModel = EpgViewModel(
            providerRepository = providerRepository,
            channelRepository = channelRepository,
            epgRepository = epgRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = preferencesRepository
        )

        advanceUntilIdle()
        viewModel.toggleFavoritesOnly()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(unhealthyFavorite.id)
    }
}
