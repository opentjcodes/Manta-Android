package app.manta.browser.ui.screens

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import app.manta.browser.core.DisplayListParser
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.components.BraveTopBar
import app.manta.browser.ui.components.FindInPageBar
import app.manta.browser.ui.render.EphemeralWebSandbox
import app.manta.browser.ui.render.MantaRenderView
import app.manta.browser.ui.theme.MantaTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Full-Screen Brave-Style Browser Viewport (`SearchBrowserScreen.kt`):
 * - Features the always-accessible `BraveTopBar` (SSL Lock + Domain + Manta Shields `🛡` + Reload).
 * - Keeps all open tabs 100% Hot-Active in RAM simultaneously (never killing open tabs!).
 * - Supports HTML5 Fullscreen `<video>`, `<input type="file">` uploads, Find-in-Page,
 *   Offline Page Saving, and Rust Reader Mode.
 */
class SearchBrowserScreen(
    context: Context,
    private val openTabs: MutableList<EphemeralTabInfo>,
    private val getActiveTabId: () -> Int,
    private val setActiveTabId: (Int) -> Unit,
    private val isDesktopModeEnabled: () -> Boolean,
    private val onStateUpdated: (title: String, url: String, progress: Int, blockedCount: Int) -> Unit,
    private val onScrollDirectionChanged: (scrollingDown: Boolean) -> Unit,
    private val onOpenOmnibox: () -> Unit,
    private val onOpenShieldsSheet: () -> Unit,
    private val onOpenVaultSettings: () -> Unit,
    private val onShowFilePicker: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val onSecurityLog: (String) -> Unit
) : LinearLayout(context) {

    private val topBar: BraveTopBar
    private val viewportFrame: FrameLayout
    private val startOverlay: HomeScreen
    private val rustRenderView: MantaRenderView
    private val findBar: FindInPageBar
    private val fullscreenVideoContainer: FrameLayout
    private var fullscreenCustomCallback: WebChromeClient.CustomViewCallback? = null

    private val tabSandboxes = HashMap<Int, EphemeralWebSandbox>()

    private var useRustReaderMode: Boolean = false
    var currentUrl: String = "manta://start"
        private set
    private var currentProgress: Int = 100

    init {
        orientation = VERTICAL
        setBackgroundColor(MantaTheme.BG_OBSIDIAN)

        // 1. Brave-Style Top Address & Shields Bar
        topBar = BraveTopBar(
            context = context,
            onUrlBarClick = onOpenOmnibox,
            onShieldsClick = onOpenShieldsSheet,
            onReloadClick = { reloadCurrentPage() },
            onHomeClick = { loadInputUrlOrQuery("manta://start", false) }
        )
        addView(
            topBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        // 2. Main Web & Home Viewport Frame
        viewportFrame = FrameLayout(context).apply {
            setBackgroundColor(MantaTheme.BG_OBSIDIAN)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }

        rustRenderView = MantaRenderView(
            context = context,
            onLinkClicked = { href -> loadInputUrlOrQuery(href, false) },
            onTextCopied = { copied ->
                MantaNative.copyToClipboard(copied)
                Toast.makeText(context, "Copied paragraph to clipboard", Toast.LENGTH_SHORT).show()
            }
        ).apply {
            visibility = View.GONE
        }
        viewportFrame.addView(
            rustRenderView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        startOverlay = HomeScreen(
            context = context,
            onOpenOmniboxSearch = onOpenOmnibox,
            onLaunchUrl = { targetUrl, preferReader ->
                loadInputUrlOrQuery(targetUrl, preferReader)
            },
            onOpenVaultSettings = onOpenVaultSettings
        )
        viewportFrame.addView(
            startOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        findBar = FindInPageBar(
            context = context,
            onQueryChanged = { query ->
                val activeSandbox = tabSandboxes[getActiveTabId()]
                if (activeSandbox != null) {
                    if (query.isEmpty()) {
                        activeSandbox.clearMatches()
                        findBar.updateMatches(0, 0)
                    } else {
                        activeSandbox.findAllAsync(query)
                    }
                }
            },
            onFindNext = { forward ->
                tabSandboxes[getActiveTabId()]?.findNext(forward)
            },
            onClose = {
                tabSandboxes[getActiveTabId()]?.clearMatches()
            }
        )
        val m = MantaTheme.dp(context, 12f)
        viewportFrame.addView(
            findBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(m, m, m, 0)
            }
        )

        fullscreenVideoContainer = FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        viewportFrame.addView(
            fullscreenVideoContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        addView(viewportFrame)
    }

    fun getActiveTabBlockedCount(): Int {
        return tabSandboxes[getActiveTabId()]?.blockedCountOnThisTab ?: 0
    }

    fun bootInitialPage() {
        showStartOverlayForTab(getActiveTabId())
    }

    fun refreshHomepageDashboard() {
        startOverlay.refreshHomeDashboard()
        topBar.updateTopBar(currentUrl, currentProgress, getActiveTabBlockedCount())
    }

    fun applyPreferencesToAllOpenSandboxes() {
        tabSandboxes.values.forEach { it.syncPreferencesToWebSettings() }
        startOverlay.refreshHomeDashboard()
        topBar.updateTopBar(currentUrl, currentProgress, getActiveTabBlockedCount())
    }

    fun flushAllOpenTabsStorage() {
        tabSandboxes.values.forEach { it.markBackgroundHotActive() }
        MantaNative.flushCustomStorage()
    }

    private fun showStartOverlayForTab(tabId: Int) {
        currentUrl = "manta://start"
        openTabs.find { it.id == tabId }?.let {
            it.title = "New Tab"
            it.url = "manta://start"
        }
        tabSandboxes.values.forEach { it.visibility = View.GONE }
        rustRenderView.visibility = View.GONE
        findBar.visibility = View.GONE
        startOverlay.refreshHomeDashboard()
        startOverlay.visibility = View.VISIBLE
        onScrollDirectionChanged(false)
        topBar.updateTopBar("manta://start", 100, 0)
        onStateUpdated("New Tab", "manta://start", 100, 0)
    }

    fun cycleTabByOffset(offset: Int) {
        if (openTabs.size <= 1) {
            Toast.makeText(context, "1 Tab Open • Swipe up for Tab Switcher", Toast.LENGTH_SHORT).show()
            return
        }
        val curIdx = openTabs.indexOfFirst { it.id == getActiveTabId() }.coerceAtLeast(0)
        val nextIdx = (curIdx + offset + openTabs.size) % openTabs.size
        val targetTab = openTabs[nextIdx]
        switchToExistingTab(targetTab.id)
    }

    fun switchToExistingTab(targetTabId: Int) {
        val previousId = getActiveTabId()
        if (previousId != targetTabId) {
            tabSandboxes[previousId]?.let { prevView ->
                // Keep previous tab 100% Hot-Active in RAM (never destroy its DOM!)
                prevView.markBackgroundHotActive()
                prevView.visibility = View.GONE
            }
        }

        setActiveTabId(targetTabId)
        MantaNative.switchTab(targetTabId)
        onScrollDirectionChanged(false)

        val tabInfo = openTabs.find { it.id == targetTabId }
        val targetUrl = tabInfo?.url ?: "manta://start"
        currentUrl = targetUrl

        if (targetUrl == "manta://start") {
            showStartOverlayForTab(targetTabId)
            return
        }

        startOverlay.visibility = View.GONE
        val existingSandbox = tabSandboxes[targetTabId]
        if (existingSandbox != null && !useRustReaderMode) {
            rustRenderView.visibility = View.GONE
            existingSandbox.visibility = View.VISIBLE
            existingSandbox.wakeToForeground()
            val blocked = existingSandbox.blockedCountOnThisTab
            topBar.updateTopBar(targetUrl, 100, blocked)
            onStateUpdated(tabInfo?.title ?: "Manta", targetUrl, 100, blocked)
        } else {
            loadInputUrlOrQuery(targetUrl, useRustReaderMode)
        }
    }

    fun createNewTabAndSwitch(initialUrl: String = "manta://start") {
        val prevId = getActiveTabId()
        tabSandboxes[prevId]?.let {
            it.markBackgroundHotActive()
            it.visibility = View.GONE
        }

        val newId = MantaNative.createNewTab()
        openTabs.add(EphemeralTabInfo(newId, "New Tab", initialUrl))
        setActiveTabId(newId)
        MantaNative.switchTab(newId)

        if (initialUrl == "manta://start") {
            showStartOverlayForTab(newId)
        } else {
            loadInputUrlOrQuery(initialUrl, false)
        }
    }

    fun closeTabById(tabId: Int) {
        tabSandboxes.remove(tabId)?.let { sandbox ->
            sandbox.releaseTabResources()
            viewportFrame.removeView(sandbox)
            sandbox.destroy()
        }
        MantaNative.closeTabAndZeroize(tabId)
        openTabs.removeAll { it.id == tabId }

        if (openTabs.isEmpty()) {
            val freshId = MantaNative.createNewTab()
            openTabs.add(EphemeralTabInfo(freshId, "New Tab", "manta://start"))
            setActiveTabId(freshId)
            showStartOverlayForTab(freshId)
        } else if (getActiveTabId() == tabId) {
            val nextTab = openTabs.last()
            switchToExistingTab(nextTab.id)
        }
    }

    fun toggleReaderMode() {
        useRustReaderMode = !useRustReaderMode
        if (currentUrl != "manta://start") {
            loadUrlInternal(currentUrl, forceReload = true)
        }
    }

    fun setDesktopSiteMode(enabled: Boolean) {
        val activeId = getActiveTabId()
        MantaNative.setDesktopMode(activeId, enabled)
        tabSandboxes[activeId]?.let { sandbox ->
            sandbox.applyDesktopModeSetting(enabled)
            if (currentUrl != "manta://start") {
                sandbox.reload()
            }
        }
    }

    fun setActiveTabZoom(zoomPercent: Int) {
        val activeId = getActiveTabId()
        MantaNative.setTabZoom(activeId, zoomPercent)
        tabSandboxes[activeId]?.applyZoomPercent(zoomPercent)
    }

    fun openFindInPage() {
        if (currentUrl != "manta://start" && !useRustReaderMode) {
            findBar.showBar()
        } else {
            Toast.makeText(context, "Open a webpage first to Find in Page", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveCurrentPageOffline() {
        val activeSandbox = tabSandboxes[getActiveTabId()]
        if (currentUrl == "manta://start" || activeSandbox == null) {
            Toast.makeText(context, "Open a webpage first to save offline", Toast.LENGTH_SHORT).show()
            return
        }
        activeSandbox.capturePageHtmlAndSave { savedName ->
            if (savedName != null) {
                startOverlay.refreshHomeDashboard()
                Toast.makeText(context, "Saved offline: $savedName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not save page snapshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun goForwardIfPossible() {
        val activeSandbox = tabSandboxes[getActiveTabId()]
        if (activeSandbox != null && activeSandbox.canGoForward()) {
            activeSandbox.goForward()
        }
    }

    fun loadInputUrlOrQuery(rawInput: String, preferRustReader: Boolean) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        if (trimmed == "manta://start") {
            showStartOverlayForTab(getActiveTabId())
            return
        }

        val resolvedUrl = MantaPreferences.resolveInputToUrl(trimmed)
        useRustReaderMode = preferRustReader
        MantaNative.prewarmDns(resolvedUrl)
        loadUrlInternal(resolvedUrl, forceReload = false)
    }

    fun reloadCurrentPage() {
        if (currentUrl != "manta://start") {
            val activeSandbox = tabSandboxes[getActiveTabId()]
            if (activeSandbox != null && !useRustReaderMode) {
                activeSandbox.reload()
            } else {
                loadUrlInternal(currentUrl, forceReload = true)
            }
        } else {
            startOverlay.refreshHomeDashboard()
        }
    }

    fun wipeAllTabsAndSandboxes() {
        for ((_, sandbox) in tabSandboxes) {
            sandbox.wipeAllEphemeralData()
            viewportFrame.removeView(sandbox)
            sandbox.destroy()
        }
        tabSandboxes.clear()
        showStartOverlayForTab(getActiveTabId())
    }

    fun handleBackNavigation(): Boolean {
        if (fullscreenVideoContainer.visibility == View.VISIBLE) {
            fullscreenCustomCallback?.onCustomViewHidden()
            fullscreenVideoContainer.removeAllViews()
            fullscreenVideoContainer.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            return true
        }
        if (findBar.visibility == View.VISIBLE) {
            findBar.hideBar()
            return true
        }
        val activeSandbox = tabSandboxes[getActiveTabId()]
        if (startOverlay.visibility != View.VISIBLE && !useRustReaderMode && activeSandbox != null) {
            if (activeSandbox.canGoBack()) {
                activeSandbox.goBack()
                return true
            } else {
                showStartOverlayForTab(getActiveTabId())
                return true
            }
        }
        if (useRustReaderMode && startOverlay.visibility != View.VISIBLE) {
            useRustReaderMode = false
            showStartOverlayForTab(getActiveTabId())
            return true
        }
        return false
    }

    private fun getOrCreateSandboxForTab(tabId: Int): EphemeralWebSandbox? {
        tabSandboxes[tabId]?.let { return it }

        return try {
            val created = EphemeralWebSandbox(
                context = context,
                tabId = tabId,
                isDesktopModeEnabled = isDesktopModeEnabled,
                onPageMetaUpdate = { updatedTabId, title, url ->
                    openTabs.find { it.id == updatedTabId }?.let {
                        it.title = title
                        if (url.isNotBlank()) it.url = url
                    }
                    if (updatedTabId == getActiveTabId()) {
                        if (url.isNotBlank()) currentUrl = url
                        val blocked = getActiveTabBlockedCount()
                        topBar.updateTopBar(currentUrl, currentProgress, blocked)
                        onStateUpdated(title, currentUrl, currentProgress, blocked)
                    }
                },
                onProgressUpdate = { updatedTabId, pct ->
                    if (updatedTabId == getActiveTabId()) {
                        currentProgress = pct
                        val activeTab = openTabs.find { it.id == updatedTabId }
                        val blocked = getActiveTabBlockedCount()
                        topBar.updateTopBar(currentUrl, pct, blocked)
                        onStateUpdated(activeTab?.title ?: "Loading...", currentUrl, pct, blocked)
                    }
                },
                onShowFilePicker = onShowFilePicker,
                onEnterFullscreenVideo = { videoView, callback ->
                    fullscreenCustomCallback = callback
                    fullscreenVideoContainer.removeAllViews()
                    fullscreenVideoContainer.addView(
                        videoView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    topBar.visibility = View.GONE
                    fullscreenVideoContainer.visibility = View.VISIBLE
                },
                onExitFullscreenVideo = {
                    fullscreenVideoContainer.removeAllViews()
                    fullscreenVideoContainer.visibility = View.GONE
                    topBar.visibility = View.VISIBLE
                    fullscreenCustomCallback = null
                },
                onDownloadFinished = { fileName ->
                    startOverlay.refreshHomeDashboard()
                    Toast.makeText(context, "Saved to Manta Files: $fileName", Toast.LENGTH_SHORT).show()
                },
                onTrackerOrThreatBlocked = { updatedTabId, detail ->
                    onSecurityLog(detail)
                    if (updatedTabId == getActiveTabId()) {
                        topBar.updateTopBar(currentUrl, currentProgress, getActiveTabBlockedCount())
                    }
                }
            )

            created.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                post { findBar.updateMatches(activeMatchOrdinal, numberOfMatches) }
            }

            created.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val dy = scrollY - oldScrollY
                if (dy > 18 && scrollY > 120) {
                    onScrollDirectionChanged(true)
                } else if (dy < -18 || scrollY < 60) {
                    onScrollDirectionChanged(false)
                }
            }

            viewportFrame.addView(
                created,
                0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            tabSandboxes[tabId] = created
            created
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadUrlInternal(targetUrl: String, forceReload: Boolean) {
        val activeId = getActiveTabId()
        startOverlay.visibility = View.GONE
        onScrollDirectionChanged(false)
        openTabs.find { it.id == activeId }?.url = targetUrl

        val verdict = MantaNative.validateUrl("", targetUrl, true)
        if (verdict == "BLOCKED") {
            onSecurityLog("Blocked unsafe navigation: $targetUrl")
            Toast.makeText(context, "Manta Site Isolation blocked unsafe URL scheme", Toast.LENGTH_SHORT).show()
            return
        }

        currentUrl = verdict
        topBar.updateTopBar(verdict, 15, getActiveTabBlockedCount())

        if (useRustReaderMode) {
            tabSandboxes.values.forEach { it.visibility = View.GONE }
            rustRenderView.visibility = View.VISIBLE
            streamRemoteUrlViaRust(verdict)
        } else {
            rustRenderView.visibility = View.GONE
            tabSandboxes.forEach { (id, view) ->
                if (id != activeId) {
                    view.markBackgroundHotActive()
                    view.visibility = View.GONE
                }
            }
            val sandbox = getOrCreateSandboxForTab(activeId)
            if (sandbox != null) {
                sandbox.visibility = View.VISIBLE
                sandbox.wakeToForeground()
                if (forceReload || sandbox.url != verdict) {
                    val extraHeaders = HashMap<String, String>()
                    if (MantaPreferences.doNotTrack) extraHeaders["DNT"] = "1"
                    if (MantaPreferences.globalPrivacyControl) extraHeaders["Sec-GPC"] = "1"
                    sandbox.loadUrl(verdict, extraHeaders)
                }
            }
        }
    }

    private fun streamRemoteUrlViaRust(cleanUrl: String) {
        val tabId = getActiveTabId()
        onStateUpdated("Reader Mode...", cleanUrl, 40, getActiveTabBlockedCount())

        thread(start = true) {
            try {
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty(
                        "User-Agent",
                        EphemeralWebSandbox.buildStandardUserAgent(context, isDesktopModeEnabled())
                    )
                }
                val stream = conn.inputStream
                val buf = ByteArray(4096)
                var totalRead = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    totalRead += n
                    MantaNative.streamHtmlChunk(tabId, cleanUrl, String(buf, 0, n, Charsets.UTF_8), false)
                    if (totalRead > 256 * 1024) break
                }
                stream.close()

                val finalWire = MantaNative.streamHtmlChunk(tabId, cleanUrl, "", true)
                val parsed = DisplayListParser.parse(finalWire)
                post {
                    rustRenderView.setFrame(parsed)
                    onStateUpdated(parsed.title, cleanUrl, 100, getActiveTabBlockedCount())
                }
            } catch (_: Throwable) {
                post {
                    useRustReaderMode = false
                    loadUrlInternal(cleanUrl, forceReload = true)
                }
            }
        }
    }
}
