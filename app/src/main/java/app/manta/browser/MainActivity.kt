package app.manta.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.core.ZeroDiskJanitor
import app.manta.browser.ui.components.MiniStatusPill
import app.manta.browser.ui.components.SettingsVaultSheet
import app.manta.browser.ui.components.SlidingBottomDock
import app.manta.browser.ui.screens.EphemeralTabInfo
import app.manta.browser.ui.screens.LibraryTabsScreen
import app.manta.browser.ui.screens.SearchBrowserScreen
import app.manta.browser.ui.theme.MantaTheme
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manta Universal Android Browser Activity (`MainActivity.kt`):
 * - Driven by `libmanta_core.so` (Manta Core v2.0).
 * - Full-screen edge-to-edge browser viewport + God-Tier Homepage.
 * - Supports `<input type="file">` system file picker for uploading files/photos to any website.
 * - Supports external `http://` and `https://` deep links.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_FILE_PICKER = 9001
    }

    private val openTabs = CopyOnWriteArrayList<EphemeralTabInfo>()
    private val securityLogs = CopyOnWriteArrayList<String>()
    private var activeTabId: Int = 1
    private var desktopModeEnabled: Boolean = false
    private var activeLoadProgress: Int = 100
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var searchScreen: SearchBrowserScreen? = null
    private var tabChooserScreen: LibraryTabsScreen? = null
    private var omniboxSheet: MiniStatusPill? = null
    private var settingsVaultSheet: SettingsVaultSheet? = null
    private var bottomDock: SlidingBottomDock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val customStoragePath = ZeroDiskJanitor.getCustomStorageFilePath(this)
        activeTabId = MantaNative.initEngine(customStoragePath)
        MantaPreferences.loadFromVault()

        openTabs.add(
            EphemeralTabInfo(
                id = activeTabId,
                title = "New Tab",
                url = "manta://start"
            )
        )

        val rootContainer = FrameLayout(this).apply {
            setBackgroundColor(MantaTheme.BG_OBSIDIAN)
        }

        // 1. Floating Gesture Bottom Dock
        val dock = SlidingBottomDock(
            context = this,
            onAddressBarTap = {
                val curUrl = searchScreen?.currentUrl ?: ""
                omniboxSheet?.showOmnibox(curUrl, focusKeyboard = true)
            },
            onTabSwitcherTap = {
                openTabChooser()
            },
            onMenuTap = {
                val curUrl = searchScreen?.currentUrl ?: ""
                omniboxSheet?.showOmnibox(curUrl, focusKeyboard = false)
            },
            onHorizontalTabSwipe = { direction ->
                searchScreen?.cycleTabByOffset(direction)
                syncDockAndTelemetry()
            },
            onBackTap = {
                if (searchScreen?.handleBackNavigation() != true) {
                    searchScreen?.loadInputUrlOrQuery("manta://start", false)
                }
                syncDockAndTelemetry()
            }
        )
        bottomDock = dock

        // 2. Full Settings, Downloads & MantaDB Vault Sheet
        val vaultSheet = SettingsVaultSheet(
            context = this,
            onSettingsChanged = {
                searchScreen?.applyPreferencesToAllOpenSandboxes()
                syncDockAndTelemetry()
            },
            onShredEverything = {
                performClearCustomStorageAndRam(alsoDeleteDownloads = true)
            }
        )
        settingsVaultSheet = vaultSheet

        // 3. Floating Omnibox & Full Control Center Sheet
        val omni = MiniStatusPill(
            context = this,
            onNavigateToUrlOrSearch = { queryOrUrl, forceReader ->
                searchScreen?.loadInputUrlOrQuery(queryOrUrl, forceReader)
                syncDockAndTelemetry()
            },
            onReloadPage = {
                searchScreen?.reloadCurrentPage()
            },
            onGoForward = {
                searchScreen?.goForwardIfPossible()
            },
            onToggleBookmark = {
                val activeTab = openTabs.find { it.id == activeTabId }
                val url = searchScreen?.currentUrl ?: ""
                val added = MantaPreferences.toggleBookmark(activeTab?.title ?: url, url)
                searchScreen?.refreshHomepageDashboard()
                added
            },
            onOpenFindInPage = {
                searchScreen?.openFindInPage()
            },
            onSavePageOffline = {
                searchScreen?.saveCurrentPageOffline()
            },
            onToggleReaderMode = {
                searchScreen?.toggleReaderMode()
            },
            onToggleDesktopMode = { enabled ->
                desktopModeEnabled = enabled
                searchScreen?.setDesktopSiteMode(enabled)
                securityLogs.add("Desktop Site Mode: ${if (enabled) "ON" else "OFF"}")
                syncDockAndTelemetry()
            },
            onZoomChanged = { zoomPct ->
                searchScreen?.setActiveTabZoom(zoomPct)
                syncDockAndTelemetry()
            },
            onOpenSettingsAndVault = {
                vaultSheet.showSheet()
            },
            onZeroizeRam = {
                performClearCustomStorageAndRam(alsoDeleteDownloads = false)
            }
        )
        omniboxSheet = omni

        // 4. Full-Screen Browser Viewport & God-Tier Homepage
        val browser = SearchBrowserScreen(
            context = this,
            openTabs = openTabs,
            getActiveTabId = { activeTabId },
            setActiveTabId = { newId -> activeTabId = newId },
            isDesktopModeEnabled = { desktopModeEnabled },
            onStateUpdated = { title, url, progress ->
                activeLoadProgress = progress
                openTabs.find { it.id == activeTabId }?.let {
                    it.title = title
                    it.url = url
                }
                syncDockAndTelemetry()
            },
            onScrollDirectionChanged = { scrollingDown ->
                dock.setDockVisible(!scrollingDown)
            },
            onOpenOmnibox = {
                val curUrl = searchScreen?.currentUrl ?: ""
                omni.showOmnibox(curUrl, focusKeyboard = true)
            },
            onOpenVaultSettings = {
                vaultSheet.showSheet()
            },
            onShowFilePicker = { callback, params ->
                launchSystemFilePicker(callback, params)
            },
            onSecurityLog = { event ->
                securityLogs.add(event)
            }
        )
        searchScreen = browser

        // 5. 2-Column M3 Expressive Hot-Active Tab Switcher
        val tabChooser = LibraryTabsScreen(
            context = this,
            tabs = openTabs,
            getActiveTabId = { activeTabId },
            onSelectTab = { tabId ->
                closeTabChooser()
                browser.switchToExistingTab(tabId)
                syncDockAndTelemetry()
            },
            onCreateNewTab = {
                closeTabChooser()
                browser.createNewTabAndSwitch("manta://start")
                syncDockAndTelemetry()
            },
            onCloseTab = { tabId ->
                browser.closeTabById(tabId)
                tabChooserScreen?.refreshLibraryState(securityLogs)
                syncDockAndTelemetry()
            },
            onZeroizeAll = {
                closeTabChooser()
                performClearCustomStorageAndRam(alsoDeleteDownloads = false)
            },
            onDismissSheet = {
                closeTabChooser()
            }
        )
        tabChooserScreen = tabChooser

        rootContainer.addView(
            browser,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dockMarginH = MantaTheme.dp(this, 14f)
        val dockMarginB = MantaTheme.dp(this, 12f)
        rootContainer.addView(
            dock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                MantaTheme.dp(this, 56f),
                Gravity.BOTTOM
            ).apply {
                setMargins(dockMarginH, 0, dockMarginH, dockMarginB)
            }
        )

        rootContainer.addView(
            omni,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootContainer.addView(
            tabChooser,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootContainer.addView(
            vaultSheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootContainer)

        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank() && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
            browser.loadInputUrlOrQuery(incomingUrl, false)
        } else {
            browser.bootInitialPage()
        }
        syncDockAndTelemetry()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank() && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
            searchScreen?.createNewTabAndSwitch(incomingUrl)
            syncDockAndTelemetry()
        }
    }

    private fun launchSystemFilePicker(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = callback

        return try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            startActivityForResult(
                Intent.createChooser(intent, "Select File to Upload"),
                REQUEST_CODE_FILE_PICKER
            )
            true
        } catch (_: Throwable) {
            pendingFileChooserCallback = null
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_FILE_PICKER) {
            val cb = pendingFileChooserCallback ?: return
            pendingFileChooserCallback = null
            if (resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null)
                return
            }
            val uris = mutableListOf<Uri>()
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            cb.onReceiveValue(uris.toTypedArray())
        }
    }

    private fun openTabChooser() {
        omniboxSheet?.dismissOverlay()
        tabChooserScreen?.refreshLibraryState(securityLogs)
        tabChooserScreen?.apply {
            alpha = 0f
            translationY = MantaTheme.dpF(context, 40f)
            visibility = View.VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(200L).start()
        }
    }

    private fun closeTabChooser() {
        tabChooserScreen?.visibility = View.GONE
    }

    private fun syncDockAndTelemetry() {
        val activeTab = openTabs.find { it.id == activeTabId }
        val urlOrTitle = activeTab?.url ?: "manta://start"
        val ramBytes = MantaNative.getEngineRamBytes()
        val zoomPct = MantaNative.getTabZoom(activeTabId)

        bottomDock?.updateDockState(
            urlOrTitle = urlOrTitle,
            tabsCount = openTabs.size,
            progress = activeLoadProgress,
            isDesktopEnabled = desktopModeEnabled
        )

        omniboxSheet?.updateStatus(
            title = activeTab?.title ?: "Manta",
            ramBytes = ramBytes,
            isDesktopOn = desktopModeEnabled,
            tabCount = openTabs.size,
            zoomPercent = zoomPct
        )
    }

    private fun performClearCustomStorageAndRam(alsoDeleteDownloads: Boolean) {
        searchScreen?.wipeAllTabsAndSandboxes()
        ZeroDiskJanitor.purgeAllDiskAndRamTraces(this, alsoDeleteDownloads)
        openTabs.clear()
        securityLogs.clear()

        val customStoragePath = ZeroDiskJanitor.getCustomStorageFilePath(this)
        activeTabId = MantaNative.initEngine(customStoragePath)
        MantaPreferences.saveToVault()

        openTabs.add(EphemeralTabInfo(activeTabId, "New Tab", "manta://start"))
        searchScreen?.bootInitialPage()
        tabChooserScreen?.refreshLibraryState(securityLogs)
        syncDockAndTelemetry()
        Toast.makeText(
            this,
            if (alsoDeleteDownloads) "Shredded All MantaDB, Downloads, Cookies & Tabs (0 B)"
            else "Shredded Site Storage, Cookies & Tabs (0 B)",
            Toast.LENGTH_SHORT
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsVaultSheet?.visibility == View.VISIBLE) {
            settingsVaultSheet?.hideSheet()
            return
        }
        if (omniboxSheet?.visibility == View.VISIBLE) {
            omniboxSheet?.dismissOverlay()
            return
        }
        if (tabChooserScreen?.visibility == View.VISIBLE) {
            closeTabChooser()
            return
        }
        if (searchScreen?.handleBackNavigation() == true) {
            syncDockAndTelemetry()
            return
        }
        ZeroDiskJanitor.enforceZeroCacheAndFlushCustomStorage(this)
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        ZeroDiskJanitor.enforceZeroCacheAndFlushCustomStorage(this)
    }

    override fun onDestroy() {
        ZeroDiskJanitor.enforceZeroCacheAndFlushCustomStorage(this)
        super.onDestroy()
    }
}
