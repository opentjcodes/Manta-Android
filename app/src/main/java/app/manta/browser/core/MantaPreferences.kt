package app.manta.browser.core

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

data class HistoryItem(
    val title: String,
    val url: String,
    val timestamp: Long
)

/**
 * Manages Manta Browser user settings, search engine selection, Brave-style Shields counters,
 * custom speed dials, bookmarks, and browsing history inside `manta_vault.db`.
 */
object MantaPreferences {

    private const val SETTINGS_ORIGIN = "https://settings.manta.local"

    val SEARCH_ENGINES = listOf(
        SearchEngineOption(
            id = "brave",
            name = "Brave Search",
            badge = "B",
            templateUrl = "https://search.brave.com/search?q=%s",
            homeUrl = "https://search.brave.com"
        ),
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

    private val TRACKER_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "connect.facebook.net",
        "analytics.tiktok.com",
        "ads-twitter.com",
        "scorecardresearch.com",
        "criteo.com",
        "outbrain.com",
        "taboola.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "hotjar.com",
        "clarity.ms"
    )

    var searchEngineId: String = "google"
    var shieldsEnabledGlobal: Boolean = true
    var javascriptEnabled: Boolean = true
    var imagesEnabled: Boolean = true
    var blockPopups: Boolean = true
    var httpsOnlyMode: Boolean = false
    var doNotTrack: Boolean = true
    var globalPrivacyControl: Boolean = true
    var defaultZoomPercent: Int = 100
    var totalTrackersBlocked: Int = 0

    private val disabledShieldHosts = HashSet<String>()

    fun loadFromVault() {
        val raw = MantaNative.storageGetItem(SETTINGS_ORIGIN, "prefs_v25", false)
            .ifBlank { MantaNative.storageGetItem(SETTINGS_ORIGIN, "prefs_v2", false) }
        if (raw.isBlank()) return
        try {
            val obj = JSONObject(raw)
            searchEngineId = obj.optString("searchEngineId", "google")
            shieldsEnabledGlobal = obj.optBoolean("shieldsEnabledGlobal", true)
            javascriptEnabled = obj.optBoolean("javascriptEnabled", true)
            imagesEnabled = obj.optBoolean("imagesEnabled", true)
            blockPopups = obj.optBoolean("blockPopups", true)
            httpsOnlyMode = obj.optBoolean("httpsOnlyMode", false)
            doNotTrack = obj.optBoolean("doNotTrack", true)
            globalPrivacyControl = obj.optBoolean("globalPrivacyControl", true)
            defaultZoomPercent = obj.optInt("defaultZoomPercent", 100).coerceIn(50, 300)
            totalTrackersBlocked = obj.optInt("totalTrackersBlocked", 0).coerceAtLeast(0)

            disabledShieldHosts.clear()
            val arr = obj.optJSONArray("disabledShieldHosts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i)
                    if (h.isNotBlank()) disabledShieldHosts.add(h)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun saveToVault() {
        try {
            val disabledArr = JSONArray()
            disabledShieldHosts.forEach { disabledArr.put(it) }

            val obj = JSONObject().apply {
                put("searchEngineId", searchEngineId)
                put("shieldsEnabledGlobal", shieldsEnabledGlobal)
                put("javascriptEnabled", javascriptEnabled)
                put("imagesEnabled", imagesEnabled)
                put("blockPopups", blockPopups)
                put("httpsOnlyMode", httpsOnlyMode)
                put("doNotTrack", doNotTrack)
                put("globalPrivacyControl", globalPrivacyControl)
                put("defaultZoomPercent", defaultZoomPercent)
                put("totalTrackersBlocked", totalTrackersBlocked)
                put("disabledShieldHosts", disabledArr)
            }
            MantaNative.storageSetItem(SETTINGS_ORIGIN, "prefs_v25", obj.toString(), false)
            MantaNative.flushCustomStorage()
        } catch (_: Throwable) {
        }
    }

    fun isShieldsEnabledForUrl(url: String): Boolean {
        if (!shieldsEnabledGlobal) return false
        val host = extractHost(url)
        return host.isEmpty() || !disabledShieldHosts.contains(host)
    }

    fun setShieldsEnabledForUrl(url: String, enabled: Boolean) {
        val host = extractHost(url)
        if (host.isEmpty()) return
        if (enabled) {
            disabledShieldHosts.remove(host)
        } else {
            disabledShieldHosts.add(host)
        }
        saveToVault()
    }

    fun shouldBlockTrackerRequest(pageUrl: String, requestUrl: String): Boolean {
        if (!isShieldsEnabledForUrl(pageUrl)) return false
        val reqHost = extractHost(requestUrl)
        if (reqHost.isEmpty()) return false
        val isTracker = TRACKER_DOMAINS.any { domain ->
            reqHost == domain || reqHost.endsWith(".$domain")
        }
        if (isTracker) {
            totalTrackersBlocked++
            if (totalTrackersBlocked % 5 == 0) {
                saveToVault()
            }
        }
        return isTracker
    }

    fun recordTrackerBlocked() {
        totalTrackersBlocked++
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
        if (raw.isBlank()) return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val u = item.optString("url")
                val t = item.optString("title", u)
                if (u.isNotBlank()) list.add(SavedBookmark(t, u))
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

    fun recordHistoryVisit(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank() || cleanUrl.startsWith("manta://") || cleanUrl == "about:blank") return
        val current = getHistory()
        current.removeAll { it.url.equals(cleanUrl, ignoreCase = true) }
        current.add(0, HistoryItem(title.ifBlank { cleanUrl }, cleanUrl, System.currentTimeMillis()))
        val trimmed = current.take(50)
        try {
            val arr = JSONArray()
            for (h in trimmed) {
                arr.put(JSONObject().apply {
                    put("title", h.title)
                    put("url", h.url)
                    put("ts", h.timestamp)
                })
            }
            MantaNative.storageSetItem(SETTINGS_ORIGIN, "history_v25", arr.toString(), false)
            MantaNative.flushCustomStorage()
        } catch (_: Throwable) {
        }
    }

    fun getHistory(): MutableList<HistoryItem> {
        val raw = MantaNative.storageGetItem(SETTINGS_ORIGIN, "history_v25", false)
        val list = mutableListOf<HistoryItem>()
        if (raw.isBlank()) return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val u = item.optString("url")
                val t = item.optString("title", u)
                val ts = item.optLong("ts", 0L)
                if (u.isNotBlank()) list.add(HistoryItem(t, u, ts))
            }
        } catch (_: Throwable) {
        }
        return list
    }

    fun removeHistoryItem(url: String) {
        val current = getHistory()
        current.removeAll { it.url.equals(url.trim(), ignoreCase = true) }
        try {
            val arr = JSONArray()
            for (h in current) {
                arr.put(JSONObject().apply {
                    put("title", h.title)
                    put("url", h.url)
                    put("ts", h.timestamp)
                })
            }
            MantaNative.storageSetItem(SETTINGS_ORIGIN, "history_v25", arr.toString(), false)
            MantaNative.flushCustomStorage()
        } catch (_: Throwable) {
        }
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

    private fun extractHost(url: String): String {
        return try {
            val withoutScheme = url.lowercase().substringAfter("://")
            withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")
        } catch (_: Throwable) {
            ""
        }
    }
}
