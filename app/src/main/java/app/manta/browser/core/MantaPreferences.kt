package app.manta.browser.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class SearchEngineOption(
    val id: String,
    val name: String,
    val badge: String,
    val templateUrl: String,
    val homeUrl: String
)

data class SavedBookmark(
    val title: String,
    val url: String
)

/**
 * Manages Manta Browser user settings, search engine selection, custom speed dials,
 * bookmarks, and privacy controls.
 * Stored inside Manta's clearable origin vault (`manta://settings`) so a full shred
 * resets or preserves settings cleanly.
 */
object MantaPreferences {

    private const val SETTINGS_ORIGIN = "https://settings.manta.local"

    val SEARCH_ENGINES = listOf(
        SearchEngineOption(
            id = "google",
            name = "Google",
            badge = "G",
            templateUrl = "https://www.google.com/search?q=%s",
            homeUrl = "https://www.google.com"
        ),
        SearchEngineOption(
            id = "duckduckgo",
            name = "DuckDuckGo",
            badge = "DDG",
            templateUrl = "https://duckduckgo.com/?q=%s",
            homeUrl = "https://duckduckgo.com"
        ),
        SearchEngineOption(
            id = "brave",
            name = "Brave Search",
            badge = "B",
            templateUrl = "https://search.brave.com/search?q=%s",
            homeUrl = "https://search.brave.com"
        ),
        SearchEngineOption(
            id = "startpage",
            name = "Startpage",
            badge = "SP",
            templateUrl = "https://www.startpage.com/do/dsearch?query=%s",
            homeUrl = "https://www.startpage.com"
        ),
        SearchEngineOption(
            id = "github",
            name = "GitHub Code",
            badge = "GH",
            templateUrl = "https://github.com/search?q=%s",
            homeUrl = "https://github.com"
        )
    )

    var searchEngineId: String = "google"
    var javascriptEnabled: Boolean = true
    var imagesEnabled: Boolean = true
    var blockPopups: Boolean = true
    var httpsOnlyMode: Boolean = false
    var doNotTrack: Boolean = true
    var globalPrivacyControl: Boolean = true
    var defaultZoomPercent: Int = 100

    fun loadFromVault() {
        val raw = MantaNative.storageGetItem(SETTINGS_ORIGIN, "prefs_v2", false)
        if (raw.isBlank()) return
        try {
            val obj = JSONObject(raw)
            searchEngineId = obj.optString("searchEngineId", "google")
            javascriptEnabled = obj.optBoolean("javascriptEnabled", true)
            imagesEnabled = obj.optBoolean("imagesEnabled", true)
            blockPopups = obj.optBoolean("blockPopups", true)
            httpsOnlyMode = obj.optBoolean("httpsOnlyMode", false)
            doNotTrack = obj.optBoolean("doNotTrack", true)
            globalPrivacyControl = obj.optBoolean("globalPrivacyControl", true)
            defaultZoomPercent = obj.optInt("defaultZoomPercent", 100).coerceIn(50, 300)
        } catch (_: Throwable) {
        }
    }

    fun saveToVault() {
        try {
            val obj = JSONObject().apply {
                put("searchEngineId", searchEngineId)
                put("javascriptEnabled", javascriptEnabled)
                put("imagesEnabled", imagesEnabled)
                put("blockPopups", blockPopups)
                put("httpsOnlyMode", httpsOnlyMode)
                put("doNotTrack", doNotTrack)
                put("globalPrivacyControl", globalPrivacyControl)
                put("defaultZoomPercent", defaultZoomPercent)
            }
            MantaNative.storageSetItem(SETTINGS_ORIGIN, "prefs_v2", obj.toString(), false)
            MantaNative.flushCustomStorage()
        } catch (_: Throwable) {
        }
    }

    fun currentSearchEngine(): SearchEngineOption {
        return SEARCH_ENGINES.find { it.id == searchEngineId } ?: SEARCH_ENGINES.first()
    }

    fun cycleSearchEngine(): SearchEngineOption {
        val idx = SEARCH_ENGINES.indexOfFirst { it.id == searchEngineId }.coerceAtLeast(0)
        val next = SEARCH_ENGINES[(idx + 1) % SEARCH_ENGINES.size]
        searchEngineId = next.id
        saveToVault()
        return next
    }

    fun resolveInputToUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed == "manta://start") return "manta://start"
        if (trimmed.startsWith("manta://") ||
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("data:")
        ) {
            return if (httpsOnlyMode && trimmed.startsWith("http://") && !trimmed.contains("localhost") && !trimmed.contains("127.0.0.1")) {
                trimmed.replaceFirst("http://", "https://")
            } else {
                trimmed
            }
        }
        if (trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1")) {
            return "http://$trimmed"
        }
        if (trimmed.contains('.') && !trimmed.contains(' ')) {
            return "https://$trimmed"
        }
        val engine = currentSearchEngine()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return engine.templateUrl.replace("%s", encoded)
    }

    fun getBookmarks(): MutableList<SavedBookmark> {
        val raw = MantaNative.storageGetItem(SETTINGS_ORIGIN, "bookmarks_v2", false)
        val list = mutableListOf<SavedBookmark>()
        if (raw.isBlank()) {
            return list
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val u = item.optString("url")
                val t = item.optString("title", u)
                if (u.isNotBlank()) {
                    list.add(SavedBookmark(t, u))
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    fun isBookmarked(url: String): Boolean {
        if (url.isBlank() || url == "manta://start") return false
        return getBookmarks().any { it.url.equals(url.trim(), ignoreCase = true) }
    }

    fun toggleBookmark(title: String, url: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank() || cleanUrl == "manta://start") return false
        MantaNative.toggleBookmark(cleanUrl, title)

        val current = getBookmarks()
        val existingIdx = current.indexOfFirst { it.url.equals(cleanUrl, ignoreCase = true) }
        val added = if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            false
        } else {
            current.add(0, SavedBookmark(title.ifBlank { cleanUrl }, cleanUrl))
            true
        }
        saveBookmarksList(current)
        return added
    }

    fun removeBookmark(url: String) {
        val current = getBookmarks()
        current.removeAll { it.url.equals(url.trim(), ignoreCase = true) }
        saveBookmarksList(current)
    }

    private fun saveBookmarksList(list: List<SavedBookmark>) {
        try {
            val arr = JSONArray()
            for (b in list) {
                arr.put(JSONObject().apply {
                    put("title", b.title)
                    put("url", b.url)
                })
            }
            MantaNative.storageSetItem(SETTINGS_ORIGIN, "bookmarks_v2", arr.toString(), false)
            MantaNative.flushCustomStorage()
        } catch (_: Throwable) {
        }
    }
}
