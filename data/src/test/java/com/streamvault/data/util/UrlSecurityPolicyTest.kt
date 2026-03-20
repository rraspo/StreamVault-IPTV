package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlSecurityPolicyTest {

    @Test
    fun `validateXtreamServerUrl rejects non https endpoints`() {
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("http://provider.example.com"))
            .isEqualTo("Only HTTPS Xtream server URLs are supported.")
        assertThat(UrlSecurityPolicy.validateXtreamServerUrl("https://provider.example.com")).isNull()
    }

    @Test
    fun `validatePlaylistSourceUrl allows local and https only`() {
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("file:///storage/emulated/0/playlist.m3u")).isNull()
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("content://downloads/public_downloads/1")).isNull()
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("https://example.com/playlist.m3u")).isNull()
        assertThat(UrlSecurityPolicy.validatePlaylistSourceUrl("http://example.com/playlist.m3u"))
            .isEqualTo("Playlist sources must use HTTPS or point to a local file.")
    }

    @Test
    fun `stream entry urls reject newline injection`() {
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts%0AInjected: true")).isFalse()
        assertThat(UrlSecurityPolicy.isAllowedStreamEntryUrl("https://example.com/live.ts")).isTrue()
    }

    @Test
    fun `sanitizeImportedAssetUrl drops unsupported schemes`() {
        assertThat(UrlSecurityPolicy.sanitizeImportedAssetUrl("ftp://example.com/logo.png")).isNull()
        assertThat(UrlSecurityPolicy.sanitizeImportedAssetUrl("https://example.com/logo.png"))
            .isEqualTo("https://example.com/logo.png")
    }
}