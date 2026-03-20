package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EpisodeDaoTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var episodeDao: EpisodeDao
    private lateinit var historyDao: PlaybackHistoryDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, StreamVaultDatabase::class.java
        ).build()
        episodeDao = db.episodeDao()
        historyDao = db.playbackHistoryDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun syncWatchProgressFromHistory_updatesEpisodeFromPlaybackHistory() = runTest {
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    id = 11L,
                    episodeId = 201L,
                    title = "Episode 1",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    seriesId = 91L,
                    providerId = 5L,
                    watchProgress = 0L
                )
            )
        )
        historyDao.insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = 11L,
                contentType = ContentType.SERIES_EPISODE,
                providerId = 5L,
                resumePositionMs = 18_000L,
                lastWatchedAt = 27_000L
            )
        )

        episodeDao.syncWatchProgressFromHistory(11L, 5L)

        val episode = episodeDao.getById(11L)
        assertThat(episode).isNotNull()
        assertThat(episode?.watchProgress).isEqualTo(18_000L)
        assertThat(episode?.lastWatchedAt).isEqualTo(27_000L)
    }
}