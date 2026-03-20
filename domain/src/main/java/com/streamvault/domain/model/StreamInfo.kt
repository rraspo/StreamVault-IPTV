package com.streamvault.domain.model

data class StreamInfo(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val streamType: StreamType = StreamType.UNKNOWN,
    val containerExtension: String? = null,
    val catchUpUrl: String? = null,
    val expirationTime: Long? = null,
    val drmInfo: DrmInfo? = null
) {
    init {
        require(url.isNotBlank()) { "StreamInfo url must not be blank" }
        expirationTime?.let { require(it >= 0) { "StreamInfo expirationTime must be non-negative" } }
    }
}

data class DrmInfo(
    val scheme: DrmScheme,
    val licenseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
    val forceDefaultLicenseUrl: Boolean = false,
    val playClearContentWithoutKey: Boolean = false
) {
    init {
        require(licenseUrl.isNotBlank()) { "DrmInfo licenseUrl must not be blank" }
    }
}

enum class DrmScheme {
    WIDEVINE,
    PLAYREADY,
    CLEARKEY
}

enum class StreamType {
    HLS,
    DASH,
    MPEG_TS,
    PROGRESSIVE,
    RTSP,    // PE-H03: native RTSP via Media3 RtspMediaSource
    UNKNOWN
}

enum class DecoderMode {
    AUTO,
    HARDWARE,
    SOFTWARE
}
