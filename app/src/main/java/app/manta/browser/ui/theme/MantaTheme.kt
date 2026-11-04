package app.manta.browser.ui.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/**
 * Manta Material 3 Expressive × Obsidian Tonal Design System:
 * - Zero harsh borders; pure tonal elevation with squircle & pill geometry.
 * - Adaptive scaling for phones, foldables, and tablets.
 */
object MantaTheme {
    const val BG_OBSIDIAN = 0xFF08090E.toInt()
    const val BG_SURFACE = 0xFF11131B.toInt()
    const val BG_CARD = 0xFF181B26.toInt()
    const val BG_CONTAINER_HIGH = 0xFF222634.toInt()
    const val BG_DOCK = 0xF5141722.toInt()
    const val BG_DOCK_PILL = 0xFF242938.toInt()

    const val CONTAINER_PRIMARY = 0xFF1D3354.toInt()
    const val ON_CONTAINER_PRIMARY = 0xFFD3E3FD.toInt()
    const val CONTAINER_SECONDARY = 0xFF14352C.toInt()
    const val ON_CONTAINER_SECONDARY = 0xFF6DD58C.toInt()
    const val CONTAINER_TERTIARY = 0xFF3D1923.toInt()
    const val ON_CONTAINER_TERTIARY = 0xFFFFB3B8.toInt()

    const val TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    const val TEXT_SECONDARY = 0xFF94A3B8.toInt()
    const val TEXT_MUTED = 0xFF64748B.toInt()

    const val ACCENT_PRIMARY = 0xFFA8C7FA.toInt()
    const val ACCENT_EMERALD = 0xFF6DD58C.toInt()
    const val ACCENT_CYAN = 0xFF5EEAD4.toInt()
    const val ACCENT_ROSE = 0xFFFF897D.toInt()
    const val ACCENT_AMBER = 0xFFFBBF24.toInt()

    fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
    }

    fun dpF(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }

    fun roundedBg(
        fillColor: Int,
        radiusDp: Float,
        context: Context,
        strokeColor: Int = 0,
        strokeWidthDp: Float = 0f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, radiusDp)
            setColor(fillColor)
            if (strokeColor != 0 && strokeWidthDp > 0f) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    fun extractCleanHost(url: String): String {
        if (url.isBlank() || url == "manta://start") return "Search or type URL"
        if (url.startsWith("manta://")) return url
        return try {
            val withoutScheme = url.substringAfter("://")
            val host = withoutScheme.substringBefore('/').substringBefore('?')
            host.removePrefix("www.")
        } catch (_: Throwable) {
            url
        }
    }
}
