package com.streamvault.domain.manager

import com.streamvault.domain.model.SyncState

interface ProviderSyncStateReader {
    fun currentSyncState(providerId: Long): SyncState
}