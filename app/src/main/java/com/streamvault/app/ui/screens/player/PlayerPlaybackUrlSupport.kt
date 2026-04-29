package com.streamvault.app.ui.screens.player

internal fun String?.safeTrimmedOrNull(): String? {
    val value = this ?: return null
    return value.trim().takeIf { it.isNotEmpty() }
}

internal fun resolvePlaybackIdentityUrl(
    currentResolvedPlaybackUrl: String,
    currentStreamUrl: String
): String = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }

internal fun resolvePlaybackProbeCacheKey(
    currentStreamUrl: String,
    url: String
): String = currentStreamUrl.takeIf { it.isNotBlank() } ?: url

internal fun matchesActivePlaybackSession(
    requestVersion: Long,
    activeRequestVersion: Long,
    expectedLogicalUrl: String? = null,
    currentResolvedPlaybackUrl: String,
    currentStreamUrl: String
): Boolean {
    if (requestVersion != activeRequestVersion) return false
    val expectedUrl = expectedLogicalUrl?.takeIf { it.isNotBlank() } ?: return true
    val activeUrl = resolvePlaybackIdentityUrl(
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        currentStreamUrl = currentStreamUrl
    )
    return activeUrl.isBlank() || activeUrl == expectedUrl || currentStreamUrl == expectedUrl
}