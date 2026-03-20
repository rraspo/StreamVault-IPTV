package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgListing
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamEpisode
import com.streamvault.data.remote.dto.XtreamEpisodeInfo
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.remote.dto.XtreamVodInfo
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.dto.XtreamVodMovieData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XtreamProviderTest {

    @Test
    fun `parseXtreamExpirationDate handles slash separated local date times`() {
        assertThat(parseXtreamExpirationDate("2026/03/20 14:30:00")).isEqualTo(1774017000000L)
    }

    @Test
    fun `parseXtreamExpirationDate handles slash separated dates`() {
        assertThat(parseXtreamExpirationDate("2026/03/20")).isEqualTo(1773964800000L)
    }

    @Test
    fun `parseXtreamExpirationDate handles timestamps and iso instants`() {
        assertThat(parseXtreamExpirationDate("1710801000")).isEqualTo(1710801000000L)
        assertThat(parseXtreamExpirationDate("2026-03-20T14:30:00Z")).isEqualTo(1774017000000L)
        assertThat(parseXtreamExpirationDate("Unlimited")).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `getLiveStreams preserves live container extension in internal url`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveStreams = listOf(
                    XtreamStream(
                        name = "Live Channel",
                        streamId = 777,
                        containerExtension = ".M3U8",
                        directSource = "https://cdn.example.com/live/777/master.m3u8?token=abc"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val channels = provider.getLiveStreams().getOrNull().orEmpty()

        assertThat(channels).hasSize(1)
        assertThat(channels.first().streamUrl).isEqualTo(
            "xtream://42/live/777?ext=m3u8&src=https%3A%2F%2Fcdn.example.com%2Flive%2F777%2Fmaster.m3u8%3Ftoken%3Dabc"
        )
    }

    @Test
    fun `getVodInfo decodes common xtream metadata fields`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodInfo = XtreamVodInfoResponse(
                    info = XtreamVodInfo(
                        plot = "U29tZSBQbG90",
                        cast = "Sm9obiBEb2U=",
                        director = "SmFuZSBEb2U=",
                        genre = "QWN0aW9u",
                        durationSecs = 120,
                        rating = "7.5"
                    ),
                    movieData = XtreamVodMovieData(
                        streamId = 99,
                        name = "TW92aWUgTmFtZQ==",
                        containerExtension = "MKV",
                        directSource = "https://cdn.example.com/vod/99/movie.mkv?exp=1774017000"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movie = provider.getVodInfo(99).getOrNull()

        assertThat(movie).isNotNull()
        assertThat(movie?.name).isEqualTo("Movie Name")
        assertThat(movie?.plot).isEqualTo("Some Plot")
        assertThat(movie?.cast).isEqualTo("John Doe")
        assertThat(movie?.director).isEqualTo("Jane Doe")
        assertThat(movie?.genre).isEqualTo("Action")
        assertThat(movie?.streamUrl).isEqualTo(
            "xtream://42/movie/99?ext=mkv&src=https%3A%2F%2Fcdn.example.com%2Fvod%2F99%2Fmovie.mkv%3Fexp%3D1774017000"
        )
    }

    private class FakeXtreamApiService(
        private val liveCategories: List<XtreamCategory> = emptyList(),
        private val liveStreams: List<XtreamStream> = emptyList(),
        private val vodCategories: List<XtreamCategory> = emptyList(),
        private val vodStreams: List<XtreamStream> = emptyList(),
        private val vodInfo: XtreamVodInfoResponse = XtreamVodInfoResponse(),
        private val seriesCategories: List<XtreamCategory> = emptyList(),
        private val seriesList: List<XtreamSeriesItem> = emptyList(),
        private val seriesInfo: XtreamSeriesInfoResponse = XtreamSeriesInfoResponse(),
        private val shortEpg: XtreamEpgResponse = XtreamEpgResponse(),
        private val fullEpg: XtreamEpgResponse = XtreamEpgResponse()
    ) : XtreamApiService {
        override suspend fun authenticate(endpoint: String): XtreamAuthResponse {
            return XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())
        }

        override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = liveCategories

        override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = liveStreams

        override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = vodCategories

        override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = vodStreams

        override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = vodInfo

        override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = seriesCategories

        override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = seriesList

        override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse = seriesInfo

        override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = shortEpg

        override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = fullEpg
    }
}
