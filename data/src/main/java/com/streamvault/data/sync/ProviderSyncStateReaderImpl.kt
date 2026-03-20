package com.streamvault.data.sync

import com.streamvault.domain.manager.ProviderSyncStateReader
import com.streamvault.domain.model.SyncState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderSyncStateReaderImpl @Inject constructor(
    private val syncManager: SyncManager
) : ProviderSyncStateReader {
    override fun currentSyncState(providerId: Long): SyncState = syncManager.currentSyncState(providerId)
}