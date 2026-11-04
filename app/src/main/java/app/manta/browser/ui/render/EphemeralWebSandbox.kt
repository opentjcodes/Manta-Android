package app.manta.browser.ui.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import app.manta.browser.core.MantaDownloadController
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.theme.MantaTheme
import java.io.ByteArrayInputStream

/**
 * Manta Universal Web Viewport (`EphemeralWebSandbox.kt`):
 * 1. Keeps tabs Hot-Active in RAM while the app is open so switching tabs never loses form input or state.
 * 2. Supports `<input type="file">` uploads via `onShowFileChooser`.
 * 3. Supports direct HTTP/HTTPS, `data:`, and `blob:` file downloads via `MantaDownloadController`.
 * 4. Synchronizes origin-partitioned `localStorage` and `sessionStorage` with Manta's Rust `MantaDB` engine.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class EphemeralWebSandbox(
    context: Context,
    val tabId: Int,
    private val isDesktopModeEnabled: () -> Boolean,
    private val onPageMetaUpdate: (tabId: Int, title: String, url: String) -> Unit,
    private val onProgressUpdate: (tabId: Int, progress: Int) -> Unit,
    private val onShowFilePicker: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val onDownloadFinished: (fileName: String) -> Unit,
    private val onSecurityEvent: (String) -> Unit
) : WebView(context) {

    @Volatile
    private var currentMainFrameOriginUrl: String = ""

    inner class MantaJsBridge {
        @JavascriptInterface
        fun copyText(text: String) {
            MantaNative.copyToClipboard(text)
        }

        @JavascriptInterface
        fun storageSet(key: String, value: String, isSession: Boolean): Boolean {
            val origin = currentMainFrameOriginUrl
            if (origin.isBlank()) return false
            return MantaNative.storageSetItem(origin, key, value, isSession)
        }

        @JavascriptInterface
        fun storageGet(key: String, isSession: Boolean): String {
            val origin = currentMainFrameOriginUrl
            if (origin.isBlank()) return ""
            return MantaNative.storageGetItem(origin, key, isSession)
        }

        @JavascriptInterface
        fun storageRemove(key: String, isSession: Boolean) {
            val origin = currentMainFrameOriginUrl
            if (origin.isBlank()) return
            MantaNative.storageRemoveItem(origin, key, isSession)
        }

        @JavascriptInterface
        fun storageClearOrigin() {
            val origin = currentMainFrameOriginUrl
            if (origin.isBlank()) return
            MantaNative.storageClearOrigin(origin)
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
                    post { onDownloadFinished(saved.name) }
                }
            }
        }
    }

    init {
        setBackgroundColor(MantaTheme.BG_OBSIDIAN)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isFocusable = true
        isFocusableInTouchMode = true

        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@EphemeralWebSandbox, true)
            }
        } catch (_: Throwable) {
        }

        syncPreferencesToWebSettings()

        addJavascriptInterface(MantaJsBridge(), "MantaBridge")

        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                val blobFetchJs = """
                    (function() {
                        fetch('$url').then(r => r.blob()).then(b => {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                window.MantaBridge.saveBlobOrDataUri(reader.result, 'manta_download');
                            };
                            reader.readAsDataURL(b);
                        });
                    })();
                """.trimIndent()
                evaluateJavascript(blobFetchJs, null)
                return@DownloadListener
            }

            MantaDownloadController.enqueueDownload(
                context = context,
                url = url,
                userAgent = userAgent ?: settings.userAgentString,
                contentDisposition = contentDisposition,
                mimeType = mimeType
            ) { saved, err ->
                post {
                    if (saved != null) {
                        onDownloadFinished(saved.name)
                    } else if (err != null) {
                        onSecurityEvent("Download error: $err")
                    }
                }
            }
        })

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                post { onProgressUpdate(tabId, newProgress) }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentUrl = view?.url ?: ""
                if (!title.isNullOrBlank()) {
                    post { onPageMetaUpdate(tabId, title, currentUrl) }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                return onShowFilePicker(filePathCallback, fileChooserParams)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val rawUrl = request.url?.toString() ?: return null
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    return super.shouldInterceptRequest(view, request)
                }

                if (MantaNative.isChallengeUrl(rawUrl)) {
                    return super.shouldInterceptRequest(view, request)
                }

                val initiator = if (request.isForMainFrame) "" else currentMainFrameOriginUrl
                val verdict = MantaNative.validateUrl(initiator, rawUrl, request.isForMainFrame)
                if (verdict == "BLOCKED") {
                    val host = request.url?.host ?: rawUrl
                    post { onSecurityEvent("Site Isolation Blocked SSRF/Unsafe: $host") }
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Forbidden",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) {
                    currentMainFrameOriginUrl = url
                    if (!MantaNative.isChallengeUrl(url)) {
                        MantaNative.prewarmDns(url)
                        val runtimeJs = MantaNative.getRuntimeScript(url)
                        if (runtimeJs.isNotEmpty()) {
                            view.evaluateJavascript(runtimeJs, null)
                        }
                    }
                    post { onPageMetaUpdate(tabId, view.title ?: url, url) }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    currentMainFrameOriginUrl = url
                    if (!MantaNative.isChallengeUrl(url)) {
                        val runtimeJs = MantaNative.getRuntimeScript(url)
                        if (runtimeJs.isNotEmpty()) {
                            view.evaluateJavascript(runtimeJs, null)
                        }
                    }
                    MantaNative.flushCustomStorage()
                }
                post {
                    onProgressUpdate(tabId, 100)
                    onPageMetaUpdate(tabId, view.title ?: "Manta", url ?: "")
                }
            }
        }
    }

    fun syncPreferencesToWebSettings() {
        settings.apply {
            javaScriptEnabled = MantaPreferences.javascriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE

            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = MantaPreferences.imagesEnabled
            blockNetworkImage = !MantaPreferences.imagesEnabled

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = MantaNative.getTabZoom(tabId)

            javaScriptCanOpenWindowsAutomatically = !MantaPreferences.blockPopups
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = buildStandardUserAgent(context, isDesktopModeEnabled())
        }
    }

    fun applyDesktopModeSetting(desktopEnabled: Boolean) {
        MantaNative.setDesktopMode(tabId, desktopEnabled)
        settings.userAgentString = buildStandardUserAgent(context, desktopEnabled)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
    }

    fun applyZoomPercent(zoomPercent: Int) {
        MantaNative.setTabZoom(tabId, zoomPercent)
        settings.textZoom = zoomPercent
    }

    fun capturePageHtmlAndSave(onSaved: (String?) -> Unit) {
        val currentTitle = title ?: "manta_saved_page"
        evaluateJavascript("(function(){ return document.documentElement.outerHTML; })();") { rawJsonStr ->
            try {
                val unescaped = org.json.JSONTokener(rawJsonStr).nextValue()?.toString() ?: ""
                if (unescaped.isBlank()) {
                    onSaved(null)
                    return@evaluateJavascript
                }
                MantaDownloadController.saveOfflineHtmlSnapshot(context, currentTitle, unescaped) { savedFile ->
                    post { onSaved(savedFile?.name) }
                }
            } catch (_: Throwable) {
                post { onSaved(null) }
            }
        }
    }

    /**
     * Keeps the tab Hot-Active in RAM while recording a lightweight snapshot in Manta Core.
     */
    fun markBackgroundHotActive() {
        try {
            MantaNative.compressTabState(tabId, url ?: "", title ?: "Manta Tab", scrollY)
        } catch (_: Throwable) {
        }
    }

    fun wakeToForeground() {
        try {
            onResume()
            requestFocus()
        } catch (_: Throwable) {
        }
    }

    fun releaseTabResources() {
        try {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearFormData()
            clearCache(false)
        } catch (_: Throwable) {
        }
    }

    fun wipeAllEphemeralData() {
        try {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearFormData()
            clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun buildStandardUserAgent(context: Context, desktopMode: Boolean = false): String {
            if (MantaPreferences.custom_user_agent_override.isNotBlank()) {
                return MantaPreferences.custom_user_agent_override
            }
            val webKitVer = try {
                val raw = WebSettings.getDefaultUserAgent(context)
                Regex("Chrome/([0-9.]+)").find(raw)?.groupValues?.getOrNull(1) ?: "131.0.0.0"
            } catch (_: Throwable) {
                "131.0.0.0"
            }
            return if (desktopMode) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$webKitVer Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$webKitVer Mobile Safari/537.36"
            }
        }
    }
}

private val MantaPreferences.custom_user_agent_override: String
    get() = ""
