package app.manta.browser.ui.screens

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import app.manta.browser.core.DisplayListParser
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.components.FindInPageBar
import app.manta.browser.ui.render.EphemeralWebSandbox
import app.manta.browser.ui.render.MantaRenderView
import app.manta.browser.ui.theme.MantaTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Full-Screen Edge-to-Edge Browser Viewport (`SearchBrowserScreen.kt`):
 * - Keeps up to 12 open tabs Hot-Active in RAM simultaneously so switching tabs is 0ms
 *   and never loses `<input>` text, scroll position, or JS state.
 * - Integrates Find-in-Page, Page Zoom, File Uploads (`<input type="file">`),
 *   File Downloads, Offline Page Saving, and Rust Reader Mode.
 */
class SearchBrowserScreen(
    context: Context,
    private val openTabs: MutableList<EphemeralTabInfo>,
    private val getActiveTabId: () -> Int,
    private val setActiveTabId: (Int) -> Unit,
    private val isDesktopModeEnabled: () -> Boolean,
    private val onStateUpdated: (title: String, url: String, progress: Int) -> Unit,
    private val onScrollDirectionChanged: (scrollingDown: Boolean) -> Unit,
    private val onOpenOmnibox: () -> Unit,
    private val onOpenVaultSettings: () -> Unit,
    private val onShowFilePicker: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val onSecurityLog: (String) -> Unit
) : FrameLayout(context) {

    private val startOverlay: HomeScreen
    private val rustRenderView: MantaRenderView
    private val findBar: FindInPageBar
    private val tabSandboxes = HashMap<Int, EphemeralWebSandbox>()
    private val maxHotActiveSandboxesInRam = 12

    private var useRustReaderMode: Boolean = false
    var currentUrl: String = "manta://start"
        private set
    private var currentProgress: Int = 100

    init {
        setBackgroundColor(MantaTheme.BG_OBSIDIAN)

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
        addView(
            rustRenderView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        startOverlay = HomeScreen(
            context = context,
            onOpenOmniboxSearch = onOpenOmnibox,
            onLaunchUrl = { targetUrl, preferReader ->
                loadInputUrlOrQuery(targetUrl, preferReader)
            },
            onOpenVaultSettings = onOpenVaultSettings
        )
        addView(
            startOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
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
        val m = MantaTheme.dp(context, 14f)
        addView(
            findBar,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(m, MantaTheme.dp(context, 16f), m, 0)
            }
        )
    }

    fun bootInitialPage() {
        showStartOverlayForTab(getActiveTabId())
    }

    fun refreshHomepageDashboard() {
        startOverlay.refreshHomeDashboard()
    }

    fun applyPreferencesToAllOpenSandboxes() {
        tabSandboxes.values.forEach { it.syncPreferencesToWebSettings() }
        startOverlay.refreshHomeDashboard()
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
        onStateUpdated("New Tab", "manta://start", 100)
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
                // Keep previous tab Hot-Active in RAM (do not pause or destroy its DOM!)
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
            onStateUpdated(tabInfo?.title ?: "Manta", targetUrl, 100)
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
            removeView(sandbox)
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
            loadUrlInternal(currentUrl, forceReload = true)
        } else {
            startOverlay.refreshHomeDashboard()
        }
    }

    fun wipeAllTabsAndSandboxes() {
        for ((_, sandbox) in tabSandboxes) {
            sandbox.wipeAllEphemeralData()
            removeView(sandbox)
            sandbox.destroy()
        }
        tabSandboxes.clear()
        showStartOverlayForTab(getActiveTabId())
    }

    fun handleBackNavigation(): Boolean {
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

        if (tabSandboxes.size >= maxHotActiveSandboxesInRam) {
            val oldestId = tabSandboxes.keys.firstOrNull { it != tabId }
            if (oldestId != null) {
                tabSandboxes.remove(oldestId)?.let { oldView ->
                    oldView.markBackgroundHotActive()
                    removeView(oldView)
                    oldView.destroy()
                }
            }
        }

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
                        onStateUpdated(title, currentUrl, currentProgress)
                    }
                },
                onProgressUpdate = { updatedTabId, pct ->
                    if (updatedTabId == getActiveTabId()) {
                        currentProgress = pct
                        val activeTab = openTabs.find { it.id == updatedTabId }
                        onStateUpdated(activeTab?.title ?: "Loading...", currentUrl, pct)
                    }
                },
                onShowFilePicker = onShowFilePicker,
                onDownloadFinished = { fileName ->
                    startOverlay.refreshHomeDashboard()
                    Toast.makeText(context, "Saved to Manta Files: $fileName", Toast.LENGTH_SHORT).show()
                },
                onSecurityEvent = onSecurityLog
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

            addView(
                created,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
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
        onStateUpdated("Reader Mode...", cleanUrl, 40)

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
                    onStateUpdated(parsed.title, cleanUrl, 100)
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
