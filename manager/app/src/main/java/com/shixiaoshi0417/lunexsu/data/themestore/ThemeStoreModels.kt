// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.data.themestore

import org.json.JSONArray

/** The FolkPatch-compatible theme index FinaSU's store reads; it serves `.fpt` packages. */
const val THEME_STORE_URL = "https://folk.mysqil.com/api/themes"

/** Host whose plain-HTTP assets the store reports; only this one is safe to upgrade to HTTPS. */
private const val STORE_HTTP_HOST = "folk.mysqil.com"

const val THEME_SOURCE_ALL = "all"
const val THEME_SOURCE_OFFICIAL = "official"
const val THEME_SOURCE_THIRD_PARTY = "third_party"
const val THEME_TYPE_PHONE = "phone"
const val THEME_TYPE_TABLET = "tablet"

/**
 * The store index advertises previews (and occasionally downloads) over plain HTTP, which Android
 * blocks by default. Rewrite the store's own HTTP links to HTTPS; leave other hosts untouched
 * rather than silently changing third-party URLs.
 */
fun normalizeStoreUrl(url: String): String {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true)) return trimmed
    val authority = trimmed.substring(7).substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfter('@').substringBefore(':')
    if (host.equals(STORE_HTTP_HOST, ignoreCase = true) ||
        host.endsWith(".$STORE_HTTP_HOST", ignoreCase = true)
    ) {
        return "https://" + trimmed.substring(7)
    }
    return trimmed
}

/** Downloads go over HTTPS only; a theme package replaces colors, fonts and backgrounds. */
fun isSafeDownloadUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

/** A theme as advertised by the online store. */
data class RemoteTheme(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val version: String,
    val previewUrl: String,
    val downloadUrl: String,
    val type: String,
    val source: String,
)

/** A theme already on disk. */
data class LocalTheme(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val version: String,
    val previewUrl: String,
    val downloadUrl: String,
    val type: String,
    val source: String,
    val localPath: String,
    val previewImagePath: String,
)

data class DownloadProgress(
    val themeId: String,
    val fileProgress: Float,
    val imageProgress: Float,
    val overallProgress: Float,
    val status: DownloadStatus,
    val errorMessage: String? = null,
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

/** Search, author, source and device-type filters of the store grid. */
data class ThemeStoreFilter(
    val query: String = "",
    val author: String = "",
    val source: String = THEME_SOURCE_ALL,
    val phone: Boolean = true,
    val tablet: Boolean = true,
) {
    fun matches(theme: RemoteTheme): Boolean {
        val q = query.trim()
        val matchesQuery = q.isEmpty() ||
            theme.name.contains(q, ignoreCase = true) ||
            theme.author.contains(q, ignoreCase = true) ||
            theme.description.contains(q, ignoreCase = true)
        val matchesAuthor = author.isBlank() || theme.author.contains(author.trim(), ignoreCase = true)
        val matchesSource = when (source) {
            THEME_SOURCE_OFFICIAL -> theme.source == THEME_SOURCE_OFFICIAL
            THEME_SOURCE_THIRD_PARTY -> theme.source != THEME_SOURCE_OFFICIAL
            else -> true
        }
        val matchesType = (phone && theme.type == THEME_TYPE_PHONE) || (tablet && theme.type == THEME_TYPE_TABLET)
        return matchesQuery && matchesAuthor && matchesSource && matchesType
    }
}

/**
 * Parses the store index. It ships duplicate ids, numeric ids and plain-HTTP links: keep one entry
 * per id, rewrite the store's own HTTP assets to HTTPS and drop entries without an HTTPS package.
 */
fun parseThemeStoreIndex(body: String): List<RemoteTheme> {
    val array = JSONArray(body)
    val result = LinkedHashMap<String, RemoteTheme>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.optString("id", "").trim()
        if (id.isEmpty() || result.containsKey(id)) continue
        val downloadUrl = normalizeStoreUrl(item.optString("download_url", ""))
        if (!isSafeDownloadUrl(downloadUrl)) continue
        result[id] = RemoteTheme(
            id = id,
            name = item.optString("name", "").trim().ifEmpty { id },
            author = item.optString("author", "").trim(),
            description = item.optString("description", "").trim(),
            version = item.optString("version", "").trim(),
            previewUrl = normalizeStoreUrl(item.optString("preview_url", "")),
            downloadUrl = downloadUrl,
            type = item.optString("type", THEME_TYPE_PHONE).trim().ifEmpty { THEME_TYPE_PHONE },
            source = item.optString("source", THEME_SOURCE_THIRD_PARTY).trim().ifEmpty { THEME_SOURCE_THIRD_PARTY },
        )
    }
    return result.values.toList()
}
