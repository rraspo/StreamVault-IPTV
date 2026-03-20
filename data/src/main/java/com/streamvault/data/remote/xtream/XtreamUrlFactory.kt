package com.streamvault.data.remote.xtream

import com.streamvault.domain.model.ContentType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class XtreamStreamKind(val pathSegment: String) {
    LIVE("live"),
    MOVIE("movie"),
    SERIES("series")
}

data class XtreamStreamToken(
    val providerId: Long,
    val kind: XtreamStreamKind,
    val streamId: Long,
    val containerExtension: String? = null,
    val directSource: String? = null
)

object XtreamUrlFactory {
    private const val INTERNAL_SCHEME = "xtream"
    private val queryCredentialRegex = Regex("([?&](?:username|password)=)[^&\\s]+", RegexOption.IGNORE_CASE)
    private val liveMovieSeriesPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/(live|movie|series)/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val timeshiftPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/timeshift/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun buildPlayerApiUrl(
        serverUrl: String,
        username: String,
        password: String,
        action: String? = null,
        extraQueryParams: Map<String, String?> = emptyMap()
    ): String {
        return buildUrl(
            serverUrl = serverUrl,
            path = "player_api.php",
            queryParams = buildList {
                add("username" to username)
                add("password" to password)
                action?.let { add("action" to it) }
                extraQueryParams.forEach { (key, value) -> value?.let { add(key to it) } }
            }
        )
    }

    fun buildXmltvUrl(serverUrl: String, username: String, password: String): String {
        return buildUrl(
            serverUrl = serverUrl,
            path = "xmltv.php",
            queryParams = listOf(
                "username" to username,
                "password" to password
            )
        )
    }

    fun buildPlaybackUrl(
        serverUrl: String,
        username: String,
        password: String,
        kind: XtreamStreamKind,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        val normalizedExtension = normalizeContainerExtension(containerExtension)
        val ext = when (kind) {
            XtreamStreamKind.LIVE -> normalizedExtension ?: "ts"
            XtreamStreamKind.MOVIE, XtreamStreamKind.SERIES -> normalizedExtension ?: "mp4"
        }
        return serverUrl.trimEnd('/') + "/${kind.pathSegment}/" +
            encodePathSegment(username) + "/" +
            encodePathSegment(password) + "/" +
            encodePathSegment(streamId.toString()) + "." + encodePathSegment(ext)
    }

    fun buildCatchUpUrl(
        serverUrl: String,
        username: String,
        password: String,
        durationMinutes: Long,
        formattedStart: String,
        streamId: Long
    ): String {
        return serverUrl.trimEnd('/') + "/timeshift/" +
            encodePathSegment(username) + "/" +
            encodePathSegment(password) + "/" +
            encodePathSegment(durationMinutes.toString()) + "/" +
            encodePathSegment(formattedStart) + "/" +
            encodePathSegment(streamId.toString()) + ".ts"
    }

    fun buildInternalStreamUrl(
        providerId: Long,
        kind: XtreamStreamKind,
        streamId: Long,
        containerExtension: String? = null,
        directSource: String? = null
    ): String {
        val normalizedExtension = normalizeContainerExtension(containerExtension)
        val normalizedDirectSource = normalizeDirectSource(directSource)
        val queryParameters = buildList {
            normalizedExtension?.let { add("ext=${encodeQueryComponent(it)}") }
            normalizedDirectSource?.let { add("src=${encodeQueryComponent(it)}") }
        }
        val query = queryParameters.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "?", separator = "&")
            .orEmpty()
        return "$INTERNAL_SCHEME://$providerId/${kind.pathSegment}/$streamId$query"
    }

    fun parseInternalStreamUrl(url: String?): XtreamStreamToken? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(INTERNAL_SCHEME, ignoreCase = true)) return null
        val providerId = uri.authority?.toLongOrNull() ?: return null
        val pathSegments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val kind = pathSegments.getOrNull(0)?.let(::kindFromPathSegment) ?: return null
        val streamId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        val query = parseQuery(uri.rawQuery)
        val ext = query["ext"]?.let(::normalizeContainerExtension)
        val directSource = query["src"]?.let(::normalizeDirectSource)
        return XtreamStreamToken(providerId, kind, streamId, ext, directSource)
    }

    fun kindForContentType(contentType: ContentType): XtreamStreamKind? = when (contentType) {
        ContentType.LIVE -> XtreamStreamKind.LIVE
        ContentType.MOVIE -> XtreamStreamKind.MOVIE
        ContentType.SERIES_EPISODE -> XtreamStreamKind.SERIES
        ContentType.SERIES -> null
    }

    fun sanitizePersistedStreamUrl(url: String, providerId: Long): String {
        val parsed = parseCredentialedStreamUrl(url, providerId) ?: return url
        return buildInternalStreamUrl(
            providerId = parsed.providerId,
            kind = parsed.kind,
            streamId = parsed.streamId,
            containerExtension = parsed.containerExtension,
            directSource = parsed.directSource
        )
    }

    fun isInternalStreamUrl(url: String?): Boolean = parseInternalStreamUrl(url) != null

    fun sanitizeLogMessage(message: String): String {
        val queryRedacted = queryCredentialRegex.replace(message) { match ->
            match.groupValues[1] + "<redacted>"
        }
        val pathRedacted = liveMovieSeriesPathRegex.replace(queryRedacted) { match ->
            val prefix = match.groupValues[1]
            val type = match.groupValues[2]
            val suffix = match.groupValues[3]
            "$prefix/$type/<redacted>/<redacted>$suffix"
        }
        return timeshiftPathRegex.replace(pathRedacted) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[2]
            "$prefix/timeshift/<redacted>/<redacted>$suffix"
        }
    }

    private fun parseCredentialedStreamUrl(url: String, providerId: Long): XtreamStreamToken? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val pathSegments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val kind = pathSegments.getOrNull(0)?.let(::kindFromPathSegment) ?: return null
        if (pathSegments.size < 4) return null
        val fileSegment = pathSegments[3]
        val dotIndex = fileSegment.lastIndexOf('.')
        val streamId = fileSegment.substring(0, dotIndex.takeIf { it > 0 } ?: fileSegment.length).toLongOrNull() ?: return null
        val ext = if (dotIndex > 0 && dotIndex < fileSegment.lastIndex) {
            normalizeContainerExtension(fileSegment.substring(dotIndex + 1))
        } else {
            null
        }
        return XtreamStreamToken(providerId, kind, streamId, ext, directSource = null)
    }

    private fun kindFromPathSegment(segment: String): XtreamStreamKind? = when (segment.lowercase()) {
        XtreamStreamKind.LIVE.pathSegment -> XtreamStreamKind.LIVE
        XtreamStreamKind.MOVIE.pathSegment -> XtreamStreamKind.MOVIE
        XtreamStreamKind.SERIES.pathSegment -> XtreamStreamKind.SERIES
        else -> null
    }

    private fun buildUrl(
        serverUrl: String,
        path: String,
        queryParams: List<Pair<String, String>>
    ): String {
        val query = queryParams.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return serverUrl.trimEnd('/') + "/$path?$query"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                decodeQueryComponent(key) to decodeQueryComponent(value)
            }
            .toMap()
    }

    private fun encodePathSegment(value: String): String = encodeQueryComponent(value)

    private fun encodeQueryComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun decodeQueryComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun normalizeContainerExtension(containerExtension: String?): String? {
        return containerExtension
            ?.trim()
            ?.removePrefix(".")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeDirectSource(directSource: String?): String? {
        return directSource
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}