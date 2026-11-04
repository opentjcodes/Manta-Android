package app.manta.browser.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.manta.browser.core.MantaDownloadController
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.theme.MantaTheme
import org.json.JSONObject

data class QuickLaunchSite(
    val title: String,
    val url: String,
    val accentColor: Int,
    val badge: String
)

/**
 * Manta God-Tier Homepage (`HomeScreen.kt`):
 * 1. Hero Brand & Live Manta Core v2.0 Telemetry Pulse Pill.
 * 2. Expressive Omnibox Search Bar with 1-Tap Search Engine Switcher (`G`, `DDG`, `B`, `SP`, `GH`).
 * 3. Customizable Squircle Speed-Dials Grid (+ Pin Custom Site button).
 * 4. 3-Tab Interactive Hub Deck right on the Homepage:
 *    - Tab 0: Pinned Bookmarks
 *    - Tab 1: Saved Files & Downloads (Open/Share or Delete)
 *    - Tab 2: Live MantaDB Site Storage Inspector (with 1-Tap Shred Site)
 */
class HomeScreen(
    context: Context,
    private val onOpenOmniboxSearch: () -> Unit,
    private val onLaunchUrl: (url: String, preferRustReader: Boolean) -> Unit,
    private val onOpenVaultSettings: () -> Unit
) : ScrollView(context) {

    private val telemetryPill: TextView
    private val searchBadgeTv: TextView
    private val searchPromptTv: TextView
    private val speedDialGrid: GridLayout
    private val hubTabsRow: LinearLayout
    private val hubContentCol: LinearLayout

    private var activeHubTab: Int = 0 // 0 = Bookmarks, 1 = Downloads, 2 = MantaDB Vault

    private val customDials = mutableListOf(
        QuickLaunchSite("Google", "https://www.google.com", MantaTheme.CONTAINER_PRIMARY, "G"),
        QuickLaunchSite("GitHub", "https://github.com", MantaTheme.BG_CARD, "GH"),
        QuickLaunchSite("YouTube", "https://m.youtube.com", MantaTheme.CONTAINER_TERTIARY, "▶"),
        QuickLaunchSite("DuckDuckGo", "https://duckduckgo.com", MantaTheme.CONTAINER_SECONDARY, "DDG"),
        QuickLaunchSite("HackerNews", "https://news.ycombinator.com", MantaTheme.BG_CARD, "Y"),
        QuickLaunchSite("Wikipedia", "https://www.wikipedia.org", MantaTheme.BG_CARD, "W"),
        QuickLaunchSite("MDN Web", "https://developer.mozilla.org", MantaTheme.CONTAINER_PRIMARY, "MDN"),
        QuickLaunchSite("+ Pin Site", "manta://pin", MantaTheme.BG_CONTAINER_HIGH, "+")
    )

    init {
        setBackgroundColor(MantaTheme.BG_OBSIDIAN)
        isVerticalScrollBarEnabled = false
        isFillViewport = true

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = MantaTheme.dp(context, 20f)
            setPadding(padH, MantaTheme.dp(context, 56f), padH, MantaTheme.dp(context, 120f))
        }

        // 1. Minimalist Hero Wordmark & Live Manta Core v2.0 Telemetry Pill
        val logoBadge = TextView(context).apply {
            text = "manta"
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 38f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
        }
        container.addView(logoBadge)

        telemetryPill = TextView(context).apply {
            val cores = MantaNative.getWorkerCoreCount()
            text = "⚡ $cores Rust Cores • Hot-Active Tabs • MantaDB Ready"
            setTextColor(MantaTheme.ACCENT_CYAN)
            textSize = 11.5f
            gravity = Gravity.CENTER
            val pH = MantaTheme.dp(context, 14f)
            val pV = MantaTheme.dp(context, 6f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(MantaTheme.BG_SURFACE, 20f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 8f)
                bottomMargin = MantaTheme.dp(context, 26f)
            }
            setOnClickListener { onOpenVaultSettings() }
        }
        container.addView(telemetryPill)

        // 2. Expressive Pill Search Bar with 1-Tap Search Engine Switcher
        val searchPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pH = MantaTheme.dp(context, 16f)
            val pV = MantaTheme.dp(context, 14f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 30f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 28f)
            }
            setOnClickListener { onOpenOmniboxSearch() }
        }

        val currentEngine = MantaPreferences.currentSearchEngine()
        searchBadgeTv = TextView(context).apply {
            text = currentEngine.badge
            setTextColor(MantaTheme.BG_OBSIDIAN)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = MantaTheme.roundedBg(MantaTheme.ACCENT_PRIMARY, 14f, context)
            val sH = MantaTheme.dp(context, 10f)
            val sV = MantaTheme.dp(context, 5f)
            setPadding(sH, sV, sH, sV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = MantaTheme.dp(context, 12f)
            }
            setOnClickListener {
                val next = MantaPreferences.cycleSearchEngine()
                text = next.badge
                searchPromptTv.text = "Search ${next.name} or type URL"
                Toast.makeText(context, "Default Search: ${next.name}", Toast.LENGTH_SHORT).show()
            }
        }
        searchPill.addView(searchBadgeTv)

        searchPromptTv = TextView(context).apply {
            text = "Search ${currentEngine.name} or type URL"
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchPill.addView(searchPromptTv)

        val settingsIcon = TextView(context).apply {
            text = "⚙"
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 16f
            val p = MantaTheme.dp(context, 4f)
            setPadding(p, p, p, p)
            setOnClickListener { onOpenVaultSettings() }
        }
        searchPill.addView(settingsIcon)
        container.addView(searchPill)

        // 3. 4-Column Squircle Speed Dials Grid
        speedDialGrid = GridLayout(context).apply {
            columnCount = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 22f)
            }
        }
        container.addView(speedDialGrid)
        renderSpeedDials()

        // 4. Interactive 3-Tab Hub Deck (Bookmarks | Downloads & Files | MantaDB Vault)
        hubTabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = MantaTheme.roundedBg(MantaTheme.BG_SURFACE, 20f, context)
            val p = MantaTheme.dp(context, 4f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 12f)
            }
        }
        container.addView(hubTabsRow)

        hubContentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(hubContentCol)

        refreshHomeDashboard()
        addView(container)
    }

    fun refreshHomeDashboard() {
        val eng = MantaPreferences.currentSearchEngine()
        searchBadgeTv.text = eng.badge
        searchPromptTv.text = "Search ${eng.name} or type URL"

        val cores = MantaNative.getWorkerCoreCount()
        val ramKb = MantaNative.getEngineRamBytes() / 1024f
        val dbKb = MantaNative.getCustomStorageBytes() / 1024f
        telemetryPill.text = String.format(
            "⚡ %d Rust Cores • %.1f KB RAM • %.1f KB MantaDB",
            cores,
            ramKb,
            dbKb
        )

        renderHubTabs()
        renderHubBody()
    }

    private fun renderSpeedDials() {
        speedDialGrid.removeAllViews()
        for (dial in customDials) {
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = MantaTheme.dp(context, 6f)
                setPadding(pad, pad, pad, MantaTheme.dp(context, 14f))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setOnClickListener {
                    if (dial.url == "manta://pin") {
                        promptPinNewSpeedDial()
                    } else {
                        onLaunchUrl(dial.url, false)
                    }
                }
            }

            val squircle = TextView(context).apply {
                text = dial.badge
                setTextColor(MantaTheme.TEXT_PRIMARY)
                textSize = 14f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = MantaTheme.roundedBg(dial.accentColor, 20f, context)
                val s = MantaTheme.dp(context, 54f)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            itemLayout.addView(squircle)

            val label = TextView(context).apply {
                text = dial.title
                setTextColor(MantaTheme.TEXT_SECONDARY)
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = MantaTheme.dp(context, 6f)
                }
            }
            itemLayout.addView(label)
            speedDialGrid.addView(itemLayout)
        }
    }

    private fun promptPinNewSpeedDial() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = MantaTheme.dp(context, 16f)
            setPadding(p, p, p, 0)
        }
        val titleInput = EditText(context).apply { hint = "Site Title (e.g. Reddit)" }
        val urlInput = EditText(context).apply { hint = "URL (e.g. https://reddit.com)" }
        box.addView(titleInput)
        box.addView(urlInput)

        AlertDialog.Builder(context)
            .setTitle("Pin Site to Manta Home")
            .setView(box)
            .setPositiveButton("Pin") { _, _ ->
                val t = titleInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                if (u.isNotEmpty()) {
                    val resolved = MantaPreferences.resolveInputToUrl(u)
                    val label = t.ifEmpty { MantaTheme.extractCleanHost(resolved) }
                    val badge = label.take(2).uppercase()
                    customDials.add(
                        (customDials.size - 1).coerceAtLeast(0),
                        QuickLaunchSite(label, resolved, MantaTheme.CONTAINER_PRIMARY, badge)
                    )
                    renderSpeedDials()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderHubTabs() {
        hubTabsRow.removeAllViews()
        val titles = listOf("★ Bookmarks", "⬇ Files & Saved", "🛡 MantaDB Vault")
        for ((idx, label) in titles.withIndex()) {
            val selected = idx == activeHubTab
            val tabBtn = TextView(context).apply {
                text = label
                setTextColor(if (selected) MantaTheme.ON_CONTAINER_PRIMARY else MantaTheme.TEXT_SECONDARY)
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val pV = MantaTheme.dp(context, 9f)
                setPadding(MantaTheme.dp(context, 6f), pV, MantaTheme.dp(context, 6f), pV)
                background = MantaTheme.roundedBg(
                    if (selected) MantaTheme.CONTAINER_PRIMARY else 0x00000000,
                    16f,
                    context
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    activeHubTab = idx
                    refreshHomeDashboard()
                }
            }
            hubTabsRow.addView(tabBtn)
        }
    }

    private fun renderHubBody() {
        hubContentCol.removeAllViews()
        when (activeHubTab) {
            0 -> {
                val bookmarks = MantaPreferences.getBookmarks()
                if (bookmarks.isEmpty()) {
                    hubContentCol.addView(makeEmptyState("No saved bookmarks yet. Tap ☆ Bookmark inside the Omnibox menu on any site."))
                } else {
                    for (bm in bookmarks.take(8)) {
                        hubContentCol.addView(
                            makeHubItemCard(
                                title = bm.title,
                                subtitle = bm.url,
                                actionLabel = "✕",
                                onCardTap = { onLaunchUrl(bm.url, false) },
                                onActionTap = {
                                    MantaPreferences.removeBookmark(bm.url)
                                    refreshHomeDashboard()
                                }
                            )
                        )
                    }
                }
            }
            1 -> {
                val files = MantaDownloadController.listSavedFiles(context)
                if (files.isEmpty()) {
                    hubContentCol.addView(makeEmptyState("No downloaded files or offline pages yet. Use '⬇ Save Page' or download any file."))
                } else {
                    for (f in files.take(8)) {
                        hubContentCol.addView(
                            makeHubItemCard(
                                title = f.name,
                                subtitle = "${MantaDownloadController.formatBytes(f.sizeBytes)} • Tap to Open / Share",
                                actionLabel = "Delete",
                                onCardTap = { MantaDownloadController.shareOrExportFile(context, f) },
                                onActionTap = {
                                    MantaDownloadController.deleteSavedFile(context, f.name)
                                    refreshHomeDashboard()
                                }
                            )
                        )
                    }
                }
            }
            2 -> {
                val manifestRaw = MantaNative.inspectStorageManifest()
                var count = 0
                try {
                    val root = JSONObject(manifestRaw)
                    val arr = root.optJSONArray("origins")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val origin = o.optString("origin", "")
                            if (origin.isBlank() || origin.contains("settings.manta.local")) continue
                            count++
                            val bytes = o.optLong("used_bytes", 0L)
                            val kv = o.optInt("local_storage_keys", 0)
                            val cookies = o.optInt("cookies", 0)
                            hubContentCol.addView(
                                makeHubItemCard(
                                    title = origin,
                                    subtitle = "${MantaDownloadController.formatBytes(bytes)} • $kv KV Keys • $cookies Cookies",
                                    actionLabel = "Shred",
                                    onCardTap = { onLaunchUrl(origin, false) },
                                    onActionTap = {
                                        MantaNative.storageClearOrigin(origin)
                                        MantaNative.flushCustomStorage()
                                        refreshHomeDashboard()
                                    }
                                )
                            )
                        }
                    }
                } catch (_: Throwable) {
                }
                if (count == 0) {
                    hubContentCol.addView(makeEmptyState("0 sites storing data in MantaDB. All site storage is origin-isolated and 1-tap clearable."))
                }
            }
        }
    }

    private fun makeHubItemCard(
        title: String,
        subtitle: String,
        actionLabel: String,
        onCardTap: () -> Unit,
        onActionTap: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = MantaTheme.dp(context, 14f)
            setPadding(p, p, p, p)
            background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 20f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 8f)
            }
            setOnClickListener { onCardTap() }
        }

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(context).apply {
            text = title
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextView(context).apply {
            text = subtitle
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col)

        val actBtn = TextView(context).apply {
            text = actionLabel
            setTextColor(MantaTheme.ON_CONTAINER_TERTIARY)
            textSize = 11.5f
            val pH = MantaTheme.dp(context, 12f)
            val pV = MantaTheme.dp(context, 6f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(MantaTheme.CONTAINER_TERTIARY, 14f, context)
            setOnClickListener { onActionTap() }
        }
        row.addView(actBtn)
        return row
    }

    private fun makeEmptyState(msg: String): View {
        return TextView(context).apply {
            text = msg
            setTextColor(MantaTheme.TEXT_MUTED)
            textSize = 12.5f
            gravity = Gravity.CENTER
            val p = MantaTheme.dp(context, 20f)
            setPadding(p, p, p, p)
            background = MantaTheme.roundedBg(MantaTheme.BG_SURFACE, 20f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
