package app.manta.browser.ui.components

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.theme.MantaTheme

/**
 * Manta Floating Omnibox & Full Control Center (`MiniStatusPill.kt`):
 * - Instant search with switchable Search Engine badge (`G`, `DDG`, `B`, `SP`, `GH`).
 * - Full Page Controls: Back, Forward, Reload, Bookmark Star (`★`), Find in Page,
 *   Save Offline / Download, Reader Mode, Desktop Site, and Interactive Page Zoom (`-` `100%` `+`).
 * - Opens the full Settings, Downloads & MantaDB Storage Inspector Sheet.
 */
class MiniStatusPill(
    context: Context,
    private val onNavigateToUrlOrSearch: (queryOrUrl: String, forceReader: Boolean) -> Unit,
    private val onReloadPage: () -> Unit,
    private val onGoForward: () -> Unit,
    private val onToggleBookmark: () -> Boolean,
    private val onOpenFindInPage: () -> Unit,
    private val onSavePageOffline: () -> Unit,
    private val onToggleReaderMode: () -> Unit,
    private val onToggleDesktopMode: (Boolean) -> Unit,
    private val onZoomChanged: (Int) -> Unit,
    private val onOpenSettingsAndVault: () -> Unit,
    private val onZeroizeRam: () -> Unit
) : FrameLayout(context) {

    private val engineBadge: TextView
    private val addressInput: EditText
    private val telemetrySubtitle: TextView
    private val bookmarkBtn: TextView
    private val desktopPillBtn: TextView
    private val readerPillBtn: TextView
    private val zoomValueTv: TextView

    private var desktopEnabled: Boolean = false
    private var readerEnabled: Boolean = false
    private var currentZoom: Int = 100

    init {
        visibility = View.GONE
        setBackgroundColor(0xC405060A.toInt())
        setOnClickListener { dismissOverlay() }

        val sheetCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = MantaTheme.dp(context, 16f)
            setPadding(pad, pad, pad, pad)
            background = MantaTheme.roundedBg(MantaTheme.BG_SURFACE, 28f, context)
            val margin = MantaTheme.dp(context, 12f)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins(margin, margin, margin, MantaTheme.dp(context, 74f))
            }
            setOnClickListener { /* Consume inside card */ }
        }

        // 1. Top Search / URL Input Row with 1-Tap Search Engine Switcher
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pH = MantaTheme.dp(context, 12f)
            val pV = MantaTheme.dp(context, 6f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(MantaTheme.BG_CONTAINER_HIGH, 22f, context)
        }

        engineBadge = TextView(context).apply {
            val eng = MantaPreferences.currentSearchEngine()
            text = eng.badge
            setTextColor(MantaTheme.BG_OBSIDIAN)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = MantaTheme.roundedBg(MantaTheme.ACCENT_PRIMARY, 12f, context)
            val pH = MantaTheme.dp(context, 9f)
            val pV = MantaTheme.dp(context, 5f)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = MantaTheme.dp(context, 10f)
            }
            setOnClickListener {
                val next = MantaPreferences.cycleSearchEngine()
                text = next.badge
                addressInput.hint = "Search ${next.name} or type URL..."
                Toast.makeText(context, "Search Engine: ${next.name}", Toast.LENGTH_SHORT).show()
            }
        }
        inputRow.addView(engineBadge)

        addressInput = EditText(context).apply {
            hint = "Search ${MantaPreferences.currentSearchEngine().name} or type URL..."
            setHintTextColor(MantaTheme.TEXT_MUTED)
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 15f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    val query = text.toString().trim()
                    if (query.isNotEmpty()) {
                        dismissOverlay()
                        onNavigateToUrlOrSearch(query, false)
                    }
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(addressInput)

        val clearBtn = TextView(context).apply {
            text = "✕"
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 14f
            val p = MantaTheme.dp(context, 8f)
            setPadding(p, p, p, p)
            setOnClickListener { addressInput.setText("") }
        }
        inputRow.addView(clearBtn)
        sheetCard.addView(inputRow)

        // 2. Quick Domain & Helper Tokens
        val tokensScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 10f)
                bottomMargin = MantaTheme.dp(context, 12f)
            }
        }
        val tokensRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val quickTokens = listOf("https://", ".com", ".org", ".dev", ".io", "/", "google.com", "github.com")
        for (tok in quickTokens) {
            val chip = TextView(context).apply {
                text = tok
                setTextColor(MantaTheme.TEXT_SECONDARY)
                textSize = 12f
                val pH = MantaTheme.dp(context, 11f)
                val pV = MantaTheme.dp(context, 5f)
                setPadding(pH, pV, pH, pV)
                background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 14f, context)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = MantaTheme.dp(context, 6f)
                }
                setOnClickListener {
                    val cur = addressInput.text.toString()
                    addressInput.setText("$cur$tok")
                    addressInput.setSelection(addressInput.text.length)
                }
            }
            tokensRow.addView(chip)
        }
        tokensScroll.addView(tokensRow)
        sheetCard.addView(tokensScroll)

        // 3. Row 1: Navigation & Quick Actions (Reload, Forward, Bookmark, Find in Page, Save Page)
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        navRow.addView(makePillButton("↻ Reload", MantaTheme.BG_CARD, MantaTheme.TEXT_PRIMARY) {
            dismissOverlay()
            onReloadPage()
        })

        navRow.addView(makePillButton("→ Forward", MantaTheme.BG_CARD, MantaTheme.TEXT_PRIMARY) {
            dismissOverlay()
            onGoForward()
        })

        bookmarkBtn = makePillButton("☆ Bookmark", MantaTheme.BG_CARD, MantaTheme.TEXT_PRIMARY) {
            val added = onToggleBookmark()
            updateBookmarkBadge(added)
            Toast.makeText(
                context,
                if (added) "Saved to Manta Bookmarks" else "Removed Bookmark",
                Toast.LENGTH_SHORT
            ).show()
        }
        navRow.addView(bookmarkBtn)
        sheetCard.addView(navRow)

        // 4. Row 2: Find in Page, Save Offline / Download, Copy URL
        val toolsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 7f)
            }
        }

        toolsRow.addView(makePillButton("🔍 Find", MantaTheme.BG_CARD, MantaTheme.TEXT_PRIMARY) {
            dismissOverlay()
            onOpenFindInPage()
        })

        toolsRow.addView(makePillButton("⬇ Save Page", MantaTheme.BG_CARD, MantaTheme.ACCENT_CYAN) {
            dismissOverlay()
            onSavePageOffline()
        })

        toolsRow.addView(makePillButton("📋 Copy URL", MantaTheme.BG_CARD, MantaTheme.TEXT_PRIMARY) {
            MantaNative.copyToClipboard(addressInput.text.toString())
            Toast.makeText(context, "Copied URL", Toast.LENGTH_SHORT).show()
            dismissOverlay()
        })
        sheetCard.addView(toolsRow)

        // 5. Row 3: Reader Mode, Desktop Site & Page Zoom Stepper (`-` `100%` `+`)
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 7f)
            }
        }

        readerPillBtn = makePillButton("Reader: OFF", MantaTheme.BG_CARD, MantaTheme.TEXT_SECONDARY) {
            readerEnabled = !readerEnabled
            updateToggleLabels()
            dismissOverlay()
            onToggleReaderMode()
        }
        modeRow.addView(readerPillBtn)

        desktopPillBtn = makePillButton("Desktop: OFF", MantaTheme.BG_CARD, MantaTheme.TEXT_SECONDARY) {
            desktopEnabled = !desktopEnabled
            updateToggleLabels()
            dismissOverlay()
            onToggleDesktopMode(desktopEnabled)
        }
        modeRow.addView(desktopPillBtn)

        // Compact Zoom Control Box (`-` `100%` `+`)
        val zoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 18f, context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
        }

        val zoomMinus = TextView(context).apply {
            text = "−"
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 15f
            gravity = Gravity.CENTER
            val p = MantaTheme.dp(context, 9f)
            setPadding(p, p, p, p)
            setOnClickListener {
                currentZoom = (currentZoom - 15).coerceAtLeast(50)
                zoomValueTv.text = "${currentZoom}%"
                onZoomChanged(currentZoom)
            }
        }
        zoomValueTv = TextView(context).apply {
            text = "100%"
            setTextColor(MantaTheme.ACCENT_PRIMARY)
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener {
                currentZoom = 100
                text = "100%"
                onZoomChanged(100)
            }
        }
        val zoomPlus = TextView(context).apply {
            text = "+"
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 15f
            gravity = Gravity.CENTER
            val p = MantaTheme.dp(context, 9f)
            setPadding(p, p, p, p)
            setOnClickListener {
                currentZoom = (currentZoom + 15).coerceAtMost(300)
                zoomValueTv.text = "${currentZoom}%"
                onZoomChanged(currentZoom)
            }
        }
        zoomBox.addView(zoomMinus)
        zoomBox.addView(zoomValueTv)
        zoomBox.addView(zoomPlus)
        modeRow.addView(zoomBox)
        sheetCard.addView(modeRow)

        // 6. Row 4: Full Settings / Downloads / MantaDB Vault & 1-Tap Shred
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 8f)
            }
        }

        bottomRow.addView(
            makePillButton(
                "⚙ Settings, Downloads & DB Vault",
                MantaTheme.CONTAINER_PRIMARY,
                MantaTheme.ON_CONTAINER_PRIMARY
            ) {
                dismissOverlay()
                onOpenSettingsAndVault()
            }
        )

        bottomRow.addView(
            makePillButton(
                "⚡ Shred All",
                MantaTheme.CONTAINER_TERTIARY,
                MantaTheme.ON_CONTAINER_TERTIARY
            ) {
                dismissOverlay()
                onZeroizeRam()
            }
        )
        sheetCard.addView(bottomRow)

        // 7. Live Manta Rust Core Telemetry Footer
        telemetrySubtitle = TextView(context).apply {
            text = "Manta Core v2.0 • Hot-Active Tabs • 0 B Ghost Cache"
            setTextColor(MantaTheme.TEXT_MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 10f)
            }
        }
        sheetCard.addView(telemetrySubtitle)

        addView(sheetCard)
    }

    private fun makePillButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val pV = MantaTheme.dp(context, 10f)
            setPadding(MantaTheme.dp(context, 6f), pV, MantaTheme.dp(context, 6f), pV)
            background = MantaTheme.roundedBg(bgColor, 18f, context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MantaTheme.dp(context, 6f)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateBookmarkBadge(isBookmarked: Boolean) {
        bookmarkBtn.text = if (isBookmarked) "★ Bookmarked" else "☆ Bookmark"
        bookmarkBtn.setTextColor(if (isBookmarked) MantaTheme.ACCENT_AMBER else MantaTheme.TEXT_PRIMARY)
    }

    private fun updateToggleLabels() {
        desktopPillBtn.text = if (desktopEnabled) "Desktop: ON" else "Desktop: OFF"
        desktopPillBtn.background = MantaTheme.roundedBg(
            if (desktopEnabled) MantaTheme.CONTAINER_SECONDARY else MantaTheme.BG_CARD,
            18f,
            context
        )
        desktopPillBtn.setTextColor(
            if (desktopEnabled) MantaTheme.ON_CONTAINER_SECONDARY else MantaTheme.TEXT_SECONDARY
        )

        readerPillBtn.text = if (readerEnabled) "Reader: ON" else "Reader: OFF"
        readerPillBtn.background = MantaTheme.roundedBg(
            if (readerEnabled) MantaTheme.CONTAINER_PRIMARY else MantaTheme.BG_CARD,
            18f,
            context
        )
        readerPillBtn.setTextColor(
            if (readerEnabled) MantaTheme.ON_CONTAINER_PRIMARY else MantaTheme.TEXT_SECONDARY
        )
    }

    fun showOmnibox(currentUrl: String, focusKeyboard: Boolean) {
        val eng = MantaPreferences.currentSearchEngine()
        engineBadge.text = eng.badge
        addressInput.hint = "Search ${eng.name} or type URL..."
        val editableUrl = if (currentUrl == "manta://start") "" else currentUrl
        addressInput.setText(editableUrl)
        updateBookmarkBadge(MantaPreferences.isBookmarked(currentUrl))
        visibility = View.VISIBLE
        if (focusKeyboard) {
            addressInput.requestFocus()
            addressInput.selectAll()
            postDelayed({
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(addressInput, InputMethodManager.SHOW_IMPLICIT)
            }, 60L)
        }
    }

    fun dismissOverlay() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(addressInput.windowToken, 0)
            addressInput.clearFocus()
        } catch (_: Throwable) {
        }
        visibility = View.GONE
    }

    fun updateStatus(
        title: String,
        ramBytes: Int,
        isDesktopOn: Boolean,
        tabCount: Int,
        zoomPercent: Int
    ) {
        desktopEnabled = isDesktopOn
        currentZoom = zoomPercent
        zoomValueTv.text = "${zoomPercent}%"
        updateToggleLabels()
        val cores = MantaNative.getWorkerCoreCount()
        val storageKb = MantaNative.getCustomStorageBytes() / 1024f
        val ramKb = ramBytes / 1024f
        telemetrySubtitle.text = String.format(
            "%s • %.1f KB RAM • %.1f KB MantaDB • %d Cores • %d Hot Tab%s",
            title.take(18),
            ramKb,
            storageKb,
            cores,
            tabCount,
            if (tabCount == 1) "" else "s"
        )
    }
}
