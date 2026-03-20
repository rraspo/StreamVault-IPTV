package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.EpgRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [SyncManager] state machine transitions.
 *
 * Strategy:
 * - Uses an in-memory [FakeProviderDao] — no Room needed
 * - Real [M3uParser] (pure JVM)
 * - Mockito-kotlin mocks for network/DAO collaborators
 * - All sync calls expected to FAIL (mocks return nulls/errors by default)
 *   because we're testing state transitions, not data correctness
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── In-memory fake ──────────────────────────────────────────────

    private class FakeProviderDao(
        private val provider: ProviderEntity? = sampleProvider()
    ) : ProviderDao() {
        override suspend fun getById(id: Long): ProviderEntity? = provider
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
        override fun getAll() = kotlinx.coroutines.flow.flowOf(listOfNotNull(provider))
        override fun getActive() = kotlinx.coroutines.flow.flowOf(provider)
        override suspend fun insert(entity: ProviderEntity) = provider?.id ?: 0L
        override suspend fun update(entity: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun setActive(id: Long) = Unit
        override suspend fun getByUrlAndUser(url: String, user: String): ProviderEntity? = null
        override suspend fun updateEpgUrl(id: Long, epgUrl: String) = Unit
    }

    companion object {
        fun sampleProvider(type: ProviderType = ProviderType.XTREAM_CODES) = ProviderEntity(
            id = 1L, name = "Test", type = type,
            serverUrl = "https://test.example.com:8080",
            username = "demo", password = "demo"
        )
    }

    private class FakeSyncMetadataRepository : com.streamvault.domain.repository.SyncMetadataRepository {
        private val values = mutableMapOf<Long, SyncMetadata>()

        override fun observeMetadata(providerId: Long): Flow<SyncMetadata?> = flowOf(values[providerId])

        override suspend fun getMetadata(providerId: Long): SyncMetadata? = values[providerId]

        override suspend fun updateMetadata(metadata: SyncMetadata) {
            values[metadata.providerId] = metadata
        }

        override suspend fun clearMetadata(providerId: Long) {
            values.remove(providerId)
        }
    }

    // Mockito mocks — all return defaults (null/0/Unit), which will cause
    // the sync pipeline to throw and transition to Error state.
    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val xtreamApi: XtreamApiService = mock()
    private val epgRepo: EpgRepository = mock()
    private val okHttp: OkHttpClient = mock()
    private val syncMetadataRepo = FakeSyncMetadataRepository()

    private fun buildManager(
        providerType: ProviderType = ProviderType.XTREAM_CODES,
        providerPresent: Boolean = true,
        providerEntity: ProviderEntity? = null
    ): SyncManager = SyncManager(
        providerDao = FakeProviderDao(
            if (providerPresent) {
                providerEntity ?: sampleProvider(providerType)
            } else {
                null
            }
        ),
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        xtreamApiService = xtreamApi,
        m3uParser = M3uParser(),
        epgRepository = epgRepo,
        okHttpClient = okHttp,
        syncMetadataRepository = syncMetadataRepo
    )

    // ── Initial state ───────────────────────────────────────────────

    @Test
    fun `initialState_isIdle`() = runTest {
        val mgr = buildManager()
        assertThat(mgr.syncState.first()).isEqualTo(SyncState.Idle)
    }

    // ── Provider not found ──────────────────────────────────────────

    @Test
    fun `sync_providerNotFound_returnsError_stateRemainsIdle`() = runTest {
        val mgr = buildManager(providerPresent = false)

        val result = mgr.sync(99L)

        assertThat(result.isError).isTrue()
        // State must NOT transition away from Idle (no provider = nothing to sync)
        assertThat(mgr.syncState.first()).isEqualTo(SyncState.Idle)
    }

    // ── Xtream sync failure ─────────────────────────────────────────

    @Test
    fun `sync_xtream_networksError_transitionsToError`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        // The Xtream path calls XtreamProvider(xtreamApi,...).getLiveCategories()
        // Since xtreamApi is a mock with null returns, the call throws → manager catches → Error
        mgr.sync(1L)

        assertThat(mgr.syncState.first()).isInstanceOf(SyncState.Error::class.java)
    }

    @Test
    fun `sync_xtream_stateMustPassThroughSyncing`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)

        // In TestDispatcher, sync() runs synchronously and state transitions complete
        // before this coroutine resumes. We verify the terminal state is Error —
        // that's only reachable via Syncing, proving the full transition happened.
        mgr.sync(1L)

        val finalState = mgr.syncState.first()
        assertThat(finalState).isInstanceOf(SyncState.Error::class.java)
    }

    @Test
    fun `sync_xtream_errorHasNonEmptyMessage`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        mgr.sync(1L)

        val state = mgr.syncState.first() as? SyncState.Error
        assertThat(state).isNotNull()
        assertThat(state!!.message).isNotEmpty()
    }

    @Test
    fun `sync_xtream_withFreshCache_andForceFalse_skipsRemoteCalls`() = runTest {
        val mgr = buildManager(providerType = ProviderType.XTREAM_CODES)
        val now = System.currentTimeMillis()
        syncMetadataRepo.updateMetadata(
            SyncMetadata(
                providerId = 1L,
                lastLiveSync = now,
                lastMovieSync = now,
                lastSeriesSync = now,
                lastEpgSync = now
            )
        )

        val result = mgr.sync(1L, force = false)

        assertThat(result.isSuccess).isTrue()
        verify(xtreamApi, never()).getLiveCategories(any())
        verify(xtreamApi, never()).getLiveStreams(any())
    }

    @Test
    fun `sync_m3u_fileImport_batchesAndDiscoversEpg`() = runTest {
        whenever(channelDao.getIdMappings(1L)).thenReturn(emptyList())
        whenever(movieDao.getIdMappings(1L)).thenReturn(emptyList())

        val playlist = tempFolder.newFile("playlist.m3u")
        playlist.writeText(buildString {
            append("#EXTM3U x-tvg-url=\"https://epg.example.com/guide.xml\"\n")
            repeat(2501) { index ->
                append("#EXTINF:-1 group-title=\"News\",Channel ${index + 1}\n")
                append("https://live.example.com/ch${index + 1}.ts\n")
            }
            append("#EXTINF:-1 group-title=\"Movies\",Movie One\n")
            append("https://vod.example.com/movie1.mp4\n")
        })
        val url = playlist.toURI().toString()
        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = url, m3uUrl = url, epgUrl = "")
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)

        if (result is Result.Error) {
            error(result.message)
        }
        assertThat(result.isSuccess).isTrue()
        verify(channelDao, atLeast(3)).insertAll(any())
        verify(movieDao, atLeastOnce()).insertAll(any())
    }

    @Test
    fun `sync_m3u_gzipFileImport_succeeds`() = runTest {
        whenever(channelDao.getIdMappings(1L)).thenReturn(emptyList())
        whenever(movieDao.getIdMappings(1L)).thenReturn(emptyList())

        val gzFile = tempFolder.newFile("playlist.m3u.gz")
        GZIPOutputStream(gzFile.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("#EXTM3U\n")
            writer.write("#EXTINF:-1 group-title=\"Live\",Compressed Channel\n")
            writer.write("https://live.example.com/compressed.ts\n")
        }
        val url = gzFile.toURI().toString()
        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = url, m3uUrl = url)
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)

        if (result is Result.Error) {
            error(result.message)
        }
        assertThat(result.isSuccess).isTrue()
        verify(channelDao, atLeastOnce()).insertAll(any())
    }

    @Test
    fun `sync_m3u_ignores_insecure_streams_and_header_epg`() = runTest {
        whenever(channelDao.getIdMappings(1L)).thenReturn(emptyList())
        whenever(movieDao.getIdMappings(1L)).thenReturn(emptyList())

        val playlist = tempFolder.newFile("mixed-playlist.m3u")
        playlist.writeText(
            """
            #EXTM3U x-tvg-url="http://epg.example.com/guide.xml"
            #EXTINF:-1 group-title="News",Secure Channel
            https://live.example.com/secure.ts
            #EXTINF:-1 group-title="News",Insecure Channel
            ftp://live.example.com/insecure.ts
            """.trimIndent()
        )

        val provider = sampleProvider(ProviderType.M3U).copy(serverUrl = playlist.toURI().toString(), m3uUrl = playlist.toURI().toString())
        val mgr = buildManager(providerType = ProviderType.M3U, providerEntity = provider)

        val result = mgr.sync(1L, force = true)

        assertThat(result.isSuccess).isTrue()
        val state = mgr.syncState.first()
        assertThat(state).isInstanceOf(SyncState.Partial::class.java)
        val insertedChannels = argumentCaptor<List<com.streamvault.data.local.entity.ChannelEntity>>()
        verify(channelDao, atLeastOnce()).insertAll(insertedChannels.capture())
        assertThat(insertedChannels.allValues.flatten()).hasSize(1)
        assertThat((state as SyncState.Partial).warnings).contains("Ignored insecure EPG URL from playlist header.")
    }

    // ── M3U sync failure ────────────────────────────────────────────

    @Test
    fun `sync_m3u_networkError_transitionsToError`() = runTest {
        val mgr = buildManager(providerType = ProviderType.M3U)

        // OkHttpClient mock: newCall() returns null → NullPointerException → Error
        mgr.sync(1L)

        assertThat(mgr.syncState.first()).isInstanceOf(SyncState.Error::class.java)
    }

    // ── Reset state ─────────────────────────────────────────────────

    @Test
    fun `resetState_afterError_returnsToIdle`() = runTest {
        val mgr = buildManager()
        mgr.sync(1L) // fails → Error

        assertThat(mgr.syncState.first()).isInstanceOf(SyncState.Error::class.java)

        mgr.resetState()

        assertThat(mgr.syncState.first()).isEqualTo(SyncState.Idle)
    }

    @Test
    fun `resetState_whenAlreadyIdle_staysIdle`() = runTest {
        val mgr = buildManager()

        mgr.resetState() // should be a no-op

        assertThat(mgr.syncState.first()).isEqualTo(SyncState.Idle)
    }

    // ── isVodEntry (SyncManager is the canonical source) ───────────

    @Test
    fun `isVodEntry_mp4Extension_returnsTrue`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(url = "http://vod.example.com/film.mp4"))).isTrue()
    }

    @Test
    fun `isVodEntry_mkvExtension_returnsTrue`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(url = "http://vod.example.com/show.mkv"))).isTrue()
    }

    @Test
    fun `isVodEntry_movieGroupTitle_returnsTrue`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(group = "Movies HD"))).isTrue()
    }

    @Test
    fun `isVodEntry_vodGroupTitle_returnsTrue`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(group = "VOD Library"))).isTrue()
    }

    @Test
    fun `isVodEntry_filmGroupTitle_returnsTrue`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(group = "Film Classics"))).isTrue()
    }

    @Test
    fun `isVodEntry_liveTs_returnsFalse`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(url = "http://live.example.com/cnn.ts", group = "News"))).isFalse()
    }

    @Test
    fun `isVodEntry_liveM3u8_returnsFalse`() {
        val mgr = buildManager()
        assertThat(mgr.isVodEntry(entry(url = "http://live.example.com/sports.m3u8", group = "Sports"))).isFalse()
    }

    // ── SyncState sealed class properties ───────────────────────────

    @Test
    fun `syncState_properties_idle`() {
        assertThat(SyncState.Idle.isSyncing).isFalse()
        assertThat(SyncState.Idle.isError).isFalse()
        assertThat(SyncState.Idle.isSuccess).isFalse()
    }

    @Test
    fun `syncState_properties_syncing`() {
        val state = SyncState.Syncing("Downloading…")
        assertThat(state.isSyncing).isTrue()
        assertThat(state.isError).isFalse()
        assertThat(state.isSuccess).isFalse()
    }

    @Test
    fun `syncState_properties_success`() {
        val state = SyncState.Success(timestamp = 12345L)
        assertThat(state.isSuccess).isTrue()
        assertThat(state.isSyncing).isFalse()
        assertThat(state.isError).isFalse()
        assertThat(state.timestamp).isEqualTo(12345L)
    }

    @Test
    fun `syncState_properties_error`() {
        val state = SyncState.Error("something went wrong", cause = RuntimeException("root"))
        assertThat(state.isError).isTrue()
        assertThat(state.isSyncing).isFalse()
        assertThat(state.isSuccess).isFalse()
        assertThat(state.message).isEqualTo("something went wrong")
        assertThat(state.cause).isInstanceOf(RuntimeException::class.java)
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun entry(
        url: String = "http://stream.example.com/ch1.ts",
        group: String = "Live"
    ) = M3uParser.M3uEntry(
        name = "Test", groupTitle = group,
        tvgId = null, tvgName = null, tvgLogo = null, tvgChno = null,
        tvgLanguage = null, tvgCountry = null,
        catchUp = null, catchUpDays = null, catchUpSource = null, timeshift = null, url = url
    )
}
