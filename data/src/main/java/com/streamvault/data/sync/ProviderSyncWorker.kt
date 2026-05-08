package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.SyncMetadataRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class ProviderSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderSyncWorkerEntryPoint {
        fun providerDao(): ProviderDao
        fun syncManager(): SyncManager
        fun syncMetadataRepository(): SyncMetadataRepository
        fun xtreamIndexJobDao(): XtreamIndexJobDao
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ProviderSyncWorkerEntryPoint::class.java
            )
            val providers = entryPoint.providerDao().getAllSync()
            if (providers.isEmpty()) {
                return Result.success()
            }

            var sawRetryableFailure = false
            providers.forEach { provider ->
                val result = if (provider.type == ProviderType.XTREAM_CODES) {
                    syncXtreamProviderIfStale(entryPoint, provider)
                } else {
                    entryPoint.syncManager().sync(provider.id, force = false)
                }
                when (result) {
                    is com.streamvault.domain.model.Result.Error -> {
                        Log.w(TAG, "Provider sync worker failed for provider ${provider.id}: ${result.message}")
                        if (shouldRetry(result.exception)) {
                            sawRetryableFailure = true
                        }
                    }
                    else -> Unit
                }
            }

            if (sawRetryableFailure) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Provider sync worker failed", e)
            if (shouldRetry(e)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return when (error) {
            is java.io.IOException -> true
            is SQLiteException -> error.message.orEmpty().contains("locked", ignoreCase = true) ||
                error.message.orEmpty().contains("busy", ignoreCase = true)
            else -> false
        }
    }

    companion object {
        private const val TAG = "ProviderSyncWorker"
        private const val UNIQUE_WORK_NAME = "provider-sync-worker"
        private const val UNIQUE_LAUNCH_STALE_WORK_NAME = "provider-sync-launch-stale-check"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProviderSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueLaunchStaleCheck(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_LAUNCH_STALE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    private suspend fun syncXtreamProviderIfStale(
        entryPoint: ProviderSyncWorkerEntryPoint,
        provider: com.streamvault.data.local.entity.ProviderEntity
    ): com.streamvault.domain.model.Result<Unit> {
        val now = System.currentTimeMillis()
        val metadata = entryPoint.syncMetadataRepository().getMetadata(provider.id)
        val liveStale = ContentCachePolicy.shouldRefresh(
            metadata?.lastLiveSuccess ?: 0L,
            ContentCachePolicy.CATALOG_TTL_MILLIS,
            now
        )
        val epgStale = provider.epgSyncMode != ProviderEpgSyncMode.SKIP &&
            ContentCachePolicy.shouldRefresh(
                metadata?.lastEpgSuccess ?: 0L,
                ContentCachePolicy.EPG_TTL_MILLIS,
                now
            )
        val movieIndexDue = shouldRunIndexJob(entryPoint, provider.id, ContentType.MOVIE, now)
        val seriesIndexDue = shouldRunIndexJob(entryPoint, provider.id, ContentType.SERIES, now)

        if (!provider.isActive) {
            return com.streamvault.domain.model.Result.success(Unit)
        }

        if (liveStale) {
            when (val liveResult = entryPoint.syncManager().retrySection(provider.id, SyncRepairSection.LIVE)) {
                is com.streamvault.domain.model.Result.Error -> return liveResult
                else -> Unit
            }
        }
        if (epgStale) {
            when (val epgResult = entryPoint.syncManager().syncEpg(provider.id, force = false)) {
                is com.streamvault.domain.model.Result.Error -> return epgResult
                else -> Unit
            }
        }
        if (movieIndexDue) {
            entryPoint.syncManager().scheduleXtreamIndexSync(provider.id, ContentType.MOVIE)
        }
        if (seriesIndexDue) {
            entryPoint.syncManager().scheduleXtreamIndexSync(provider.id, ContentType.SERIES)
        }
        return com.streamvault.domain.model.Result.success(Unit)
    }

    private suspend fun shouldRunIndexJob(
        entryPoint: ProviderSyncWorkerEntryPoint,
        providerId: Long,
        section: ContentType,
        now: Long
    ): Boolean {
        val job = entryPoint.xtreamIndexJobDao().get(providerId, section.name) ?: return true
        if (job.state in setOf("QUEUED", "PARTIAL", "STALE", "FAILED_RETRYABLE")) return true
        return ContentCachePolicy.shouldRefresh(job.lastSuccessAt, ContentCachePolicy.CATALOG_TTL_MILLIS, now)
    }
}
