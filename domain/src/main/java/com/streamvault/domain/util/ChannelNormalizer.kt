package com.streamvault.domain.util

import java.text.Normalizer
import java.util.Locale

object ChannelNormalizer {
    
    /**
     * Common tags found in IPTV channel names that do not denote different content, 
     * but rather quality, source, or generic metadata.
     */
    private val qualityTags = listOf(
        "sd", "hd", "fhd", "uhd", "4k", "8k", "hevc", "h265", "raw", "vip", "premium", "pro",
        "1080p", "720p", "1080i", "4k bluray", "blu-ray", "blu ray", "mkv", "mp4", "ts"
    )

    private val specialCharactersRegex = Regex("[^a-zA-Z0-9 ]")
    private val extraSpacesRegex = Regex("\\s+")

    /**
     * Normalizes a channel name to a deterministic base logical group ID.
     * Removes quality tags, country prefixes/suffixes (e.g. "US:", "|UK|"), and special characters.
     * 
     * Examples:
     * "BBC One HD" -> "bbcone"
     * "US: HBO Max FHD" -> "hbomax"
     * "|UK| Sky Sports 1 (FHD)" -> "skysports1"
     */
    fun getLogicalGroupId(channelName: String, providerId: Long): String {
        var normalized = channelName.lowercase(Locale.ROOT)

        // 1. Remove bracketed/parenthesized tags (often country codes or "FHD")
        // e.g. [UK], (USA), |FR|
        normalized = normalized.replace(Regex("\\[.*?\\]"), " ")
        normalized = normalized.replace(Regex("\\(.*?\\)"), " ")
        normalized = normalized.replace(Regex("\\|.*?\\|"), " ")
        
        // 2. Remove isolated country prefixes like "US:", "UK -", "FR:"
        normalized = normalized.replace(Regex("^[a-z]{2,3}[:\\-]\\s*"), " ")

        // 3. Strip accents for multilingual channel names
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        // 4. Remove all non-alphanumeric characters (keeps spaces for word boundary checking)
        normalized = normalized.replace(specialCharactersRegex, " ")

        // 5. Tokenize and remove quality tags
        val words = normalized.split(extraSpacesRegex).filter { it.isNotBlank() }
        val filteredWords = words.filter { word ->
            !qualityTags.contains(word)
        }

        // 6. Rejoin and strip all spaces for the final deterministic ID
        // Also prepend the providerId so we don't merge across different subscriptions 
        // unless explicitly desired (which is complex for playback tokens).
        val baseId = filteredWords.joinToString("").ifEmpty {
            // Fallback if the name was literally just "FHD" or empty
            channelName.replace(specialCharactersRegex, "").lowercase(Locale.ROOT)
        }

        return "${providerId}_${baseId}"
    }
}
