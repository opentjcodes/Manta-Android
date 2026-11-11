package app.manta.browser.ui.components

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.theme.MantaTheme

/**
 * Brave-Style Top Omnibox & Shields Bar (`BraveTopBar.kt`):
 * - Displays SSL Lock (`🔒` / `ⓘ`), clean domain/URL pill, Reload/Stop button, and
 *   the interactive **Manta Shields Badge (`🛡 N`)** that opens the per-site Shields panel.
 */
class BraveTopBar(
    context: Context,
    private val onUrlBarClick: () -> Unit,
    private val onShieldsClick: () -> Unit,
    private val onReloadClick: () -> Unit,
    private val onHomeClick: () -> Unit
) : LinearLayout(context) {

    private val lockIconTv: TextView
    private val urlTextTv: TextView
    private val reloadBtnTv: TextView
    private val shieldsBadgeTv: TextView
    private val progressBar: ProgressBar

    init {
        orientation = VERTICAL
        setBackgroundColor(MantaTheme.BG_SURFACE)

        val mainRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pH = MantaTheme.dp(context, 10f)
            val pV = MantaTheme.dp(context, 7f)
            setPadding(pH, pV, pH, pV)
        }

        // 1. Home Icon Button
        val homeBtn = TextView(context).apply {
            text = "⌂"
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 17f
            gravity = Gravity.CENTER
            background = MantaTheme.roundedBg(MantaTheme.BG_CONTAINER_HIGH, 16f, context)
            val s = MantaTheme.dp(context, 36f)
            layoutParams = LayoutParams(s, s).apply {
                marginEnd = MantaTheme.dp(context, 8f)
            }
            setOnClickListener { onHomeClick() }
        }
        mainRow.addView(homeBtn)

        // 2. Center Brave-Style URL & SSL Pill
        val urlPill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pH = MantaTheme.dp(context, 12f)
            val pV = MantaTheme.dp(context, 7f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 20f, context)
            layoutParams = LayoutParams(0, MantaTheme.dp(context, 38f), 1f)
            setOnClickListener { onUrlBarClick() }
        }

        lockIconTv = TextView(context).apply {
            text = "🔍"
            setTextColor(MantaTheme.ACCENT_EMERALD)
            textSize = 12f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = MantaTheme.dp(context, 7f)
            }
        }
        urlPill.addView(lockIconTv)

        urlTextTv = TextView(context).apply {
            text = "Search or type URL"
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 13.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        urlPill.addView(urlTextTv)

        reloadBtnTv = TextView(context).apply {
            text = "↻"
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 15f
            gravity = Gravity.CENTER
            val s = MantaTheme.dp(context, 26f)
            layoutParams = LayoutParams(s, s).apply {
                marginStart = MantaTheme.dp(context, 4f)
            }
            setOnClickListener { onReloadClick() }
        }
        urlPill.addView(reloadBtnTv)
        mainRow.addView(urlPill)

        // 3. Brave-Style Manta Shields Pill (`🛡 N`)
        shieldsBadgeTv = TextView(context).apply {
            text = "🛡 0"
            setTextColor(MantaTheme.ON_CONTAINER_PRIMARY)
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            val pH = MantaTheme.dp(context, 11f)
            setPadding(pH, 0, pH, 0)
            background = MantaTheme.roundedBg(MantaTheme.CONTAINER_PRIMARY, 18f, context)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                MantaTheme.dp(context, 36f)
            ).apply {
                marginStart = MantaTheme.dp(context, 8f)
            }
            setOnClickListener { onShieldsClick() }
        }
        mainRow.addView(shieldsBadgeTv)
        addView(mainRow)

        // 4. Thin 2dp Top Progress Line
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, MantaTheme.dp(context, 2.5f))
        }
        addView(progressBar)
    }

    fun updateTopBar(url: String, progress: Int, blockedCountOnTab: Int) {
        val isHome = url.isBlank() || url == "manta://start"
        if (isHome) {
            lockIconTv.text = MantaPreferences.currentSearchEngine().badge
            lockIconTv.setTextColor(MantaTheme.ACCENT_PRIMARY)
            urlTextTv.text = "Search ${MantaPreferences.currentSearchEngine().name} or type URL"
            urlTextTv.setTextColor(MantaTheme.TEXT_SECONDARY)
            reloadBtnTv.visibility = View.GONE
        } else {
            val isHttps = url.startsWith("https://") || url.startsWith("manta://")
            lockIconTv.text = if (isHttps) "🔒" else "⚠"
            lockIconTv.setTextColor(if (isHttps) MantaTheme.ACCENT_EMERALD else MantaTheme.ACCENT_AMBER)
            urlTextTv.text = MantaTheme.extractCleanHost(url)
            urlTextTv.setTextColor(MantaTheme.TEXT_PRIMARY)
            reloadBtnTv.visibility = View.VISIBLE
        }

        val shieldsUp = MantaPreferences.isShieldsEnabledForUrl(url)
        shieldsBadgeTv.text = if (shieldsUp) "🛡 $blockedCountOnTab" else "🛡 OFF"
        shieldsBadgeTv.background = MantaTheme.roundedBg(
            if (shieldsUp) MantaTheme.CONTAINER_PRIMARY else MantaTheme.BG_CONTAINER_HIGH,
            18f,
            context
        )
        shieldsBadgeTv.setTextColor(
            if (shieldsUp) MantaTheme.ON_CONTAINER_PRIMARY else MantaTheme.TEXT_MUTED
        )

        if (progress in 1..99) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = progress
        } else {
            progressBar.visibility = View.GONE
        }
    }
}
