package com.streamvault.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamVaultDatabaseMigrationTest {

    private val testDbName = "streamvault-migration-test"

    @get:Rule
    val migrationTestHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StreamVaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate9To10_createsBackfillsAndMaintainsFtsTables() {
        migrationTestHelper.createDatabase(testDbName, 9).apply {
            execSQL(
                """
                INSERT INTO channels (
                    stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count
                ) VALUES (1001, 'News One', 'http://test/live/news', 101, 0, 0, 1, 0, 0, '', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, last_watched_at, is_adult, is_user_protected
                ) VALUES (2001, 'Movie Alpha', 'http://test/movie/alpha', 7200, 7.5, 1, 0, 0, 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    series_id, name, rating, last_modified, provider_id, is_adult, is_user_protected
                ) VALUES (3001, 'Series Prime', 8.1, 0, 1, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            testDbName,
            10,
            true,
            StreamVaultDatabase.MIGRATION_9_10
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'news*'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movies_fts WHERE movies_fts MATCH 'movie*'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM series_fts WHERE series_fts MATCH 'series*'"))

        migratedDb.execSQL(
            """
            INSERT INTO channels (
                stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                provider_id, is_adult, is_user_protected, logical_group_id, error_count
            ) VALUES (1002, 'Sports Arena', 'http://test/live/sports', 102, 0, 0, 1, 0, 0, '', 0)
            """.trimIndent()
        )
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'sports*'"))

        migratedDb.execSQL("UPDATE channels SET name = 'Sports Central' WHERE stream_id = 1002")
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'central*'"))

        migratedDb.execSQL("DELETE FROM channels WHERE stream_id = 1002")
        assertFalse(exists(migratedDb, "SELECT 1 FROM channels_fts WHERE channels_fts MATCH 'central*'"))

        migratedDb.close()
    }

    @Test
    fun migrate1To15_fullChainValidatesLatestSchema() {
        migrationTestHelper.createDatabase("streamvault-full-chain-test", 1).close()

        migrationTestHelper.runMigrationsAndValidate(
            "streamvault-full-chain-test",
            15,
            true,
            StreamVaultDatabase.MIGRATION_1_2,
            StreamVaultDatabase.MIGRATION_2_3,
            StreamVaultDatabase.MIGRATION_3_4,
            StreamVaultDatabase.MIGRATION_4_5,
            StreamVaultDatabase.MIGRATION_5_6,
            StreamVaultDatabase.MIGRATION_6_7,
            StreamVaultDatabase.MIGRATION_7_8,
            StreamVaultDatabase.MIGRATION_8_9,
            StreamVaultDatabase.MIGRATION_9_10,
            StreamVaultDatabase.MIGRATION_10_11,
            StreamVaultDatabase.MIGRATION_11_12,
            StreamVaultDatabase.MIGRATION_12_13,
            StreamVaultDatabase.MIGRATION_13_14,
            StreamVaultDatabase.MIGRATION_14_15
        ).close()
    }

    @Test
    fun migrate14To15_addsAuditCompletionColumns() {
        migrationTestHelper.createDatabase("streamvault-14-15-test", 14).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, expiration_date, status, last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, NULL, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO playback_history (
                    content_id, content_type, provider_id, title, poster_url, stream_url,
                    resume_position_ms, total_duration_ms, last_watched_at, watch_count, series_id, season_number, episode_number
                ) VALUES (10, 'MOVIE', 1, 'Movie', NULL, 'https://provider.example.com/movie.mp4', 0, 7200, 0, 1, NULL, NULL, NULL)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-14-15-test",
            15,
            true,
            StreamVaultDatabase.MIGRATION_14_15
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'api_version'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channels') WHERE name = 'quality_options_json'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('programs') WHERE name = 'rating'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('playback_history') WHERE name = 'watched_status'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM playback_history WHERE watched_status = 'IN_PROGRESS'"))

        migratedDb.close()
    }

    private fun countRows(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    private fun exists(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Boolean {
        db.query(sql).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
