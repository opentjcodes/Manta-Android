package app.manta.browser.ui.render

import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import app.manta.browser.core.MantaDownloadController
import app.manta.browser.core.MantaNative

/**
 * Dedicated JS-to-Rust Bridge (`MantaJsBridge.kt`) for Manta Core v2.5:
 * Connects the high-level kernel scripts (`kernel_scripts.rs` & `web_compat_engine.rs`)
 * directly to Manta's Transactional `MANTADB3` Storage Engine, Cookie Jar, IndexedDB Mirror,
 * Form Draft Persistence, DNS Pre-warmer, and File Download Controller.
 */
class MantaJsBridge(
    private val context: Context,
    private val getCurrentOriginUrl: () -> String,
    private val onDownloadCompleted: (String) -> Unit
) {

    @JavascriptInterface
    fun copyText(text: String) {
        MantaNative.copyToClipboard(text)
    }

    @JavascriptInterface
    fun prewarmDns(originUrl: String) {
        if (originUrl.isNotBlank()) {
            MantaNative.prewarmDns(originUrl)
        }
    }

    @JavascriptInterface
    fun storageSet(key: String, value: String, isSession: Boolean): Boolean {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank()) return false
        return MantaNative.storageSetItem(origin, key, value, isSession)
    }

    @JavascriptInterface
    fun storageGet(key: String, isSession: Boolean): String {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank()) return ""
        return MantaNative.storageGetItem(origin, key, isSession)
    }

    @JavascriptInterface
    fun storageRemove(key: String, isSession: Boolean) {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank()) return
        MantaNative.storageRemoveItem(origin, key, isSession)
    }

    @JavascriptInterface
    fun storageClearOrigin() {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank()) return
        MantaNative.storageClearOrigin(origin)
    }

    @JavascriptInterface
    fun cookieSet(rawCookie: String): Boolean {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank() || rawCookie.isBlank()) return false
        try {
            CookieManager.getInstance().setCookie(origin, rawCookie)
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
        return MantaNative.storageSetItem(origin, "__manta_cookie_jar", rawCookie, false)
    }

    @JavascriptInterface
    fun idbPut(storeName: String, key: String, jsonVal: String): Boolean {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank() || storeName.isBlank() || key.isBlank()) return false
        return MantaNative.storageSetItem(
            origin,
            "__manta_idb::${storeName.trim()}::${key.trim()}",
            jsonVal,
            false
        )
    }

    @JavascriptInterface
    fun idbDelete(storeName: String, key: String) {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank() || storeName.isBlank() || key.isBlank()) return
        MantaNative.storageRemoveItem(
            origin,
            "__manta_idb::${storeName.trim()}::${key.trim()}",
            false
        )
    }

    @JavascriptInterface
    fun saveFormDraft(fieldKey: String, value: String): Boolean {
        val origin = getCurrentOriginUrl()
        if (origin.isBlank() || fieldKey.isBlank()) return false
        return MantaNative.storageSetItem(origin, "__manta_draft::$fieldKey", value, false)
    }

    @JavascriptInterface
    fun saveBlobOrDataUri(dataUri: String, suggestedName: String) {
        MantaDownloadController.enqueueDownload(
            context = context,
            url = dataUri,
            userAgent = "",
            contentDisposition = "attachment; filename=\"$suggestedName\"",
            mimeType = null
        ) { saved, _ ->
            if (saved != null) {
                onDownloadCompleted(saved.name)
            }
        }
    }
}
