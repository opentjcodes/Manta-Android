package app.manta.browser.ui.components

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.manta.browser.ui.theme.MantaTheme

/**
 * Floating top pill bar for Find-in-Page (`FindInPageBar.kt`) with live match counter
 * and Previous (`▲`) / Next (`▼`) match navigation.
 */
class FindInPageBar(
    context: Context,
    private val onQueryChanged: (String) -> Unit,
    private val onFindNext: (forward: Boolean) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val queryInput: EditText
    private val countLabel: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val pH = MantaTheme.dp(context, 14f)
        val pV = MantaTheme.dp(context, 8f)
        setPadding(pH, pV, pH, pV)
        background = MantaTheme.roundedBg(MantaTheme.BG_DOCK, 26f, context)

        queryInput = EditText(context).apply {
            hint = "Find in page..."
            setHintTextColor(MantaTheme.TEXT_MUTED)
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 14f
            isSingleLine = true
            background = null
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onQueryChanged(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        addView(queryInput)

        countLabel = TextView(context).apply {
            text = "0/0"
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 12f
            val m = MantaTheme.dp(context, 8f)
            setPadding(m, 0, m, 0)
        }
        addView(countLabel)

        addView(makeActionBtn("▲") { onFindNext(false) })
        addView(makeActionBtn("▼") { onFindNext(true) })
        addView(makeActionBtn("✕") { hideBar() })
    }

    private fun makeActionBtn(symbol: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = symbol
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = MantaTheme.roundedBg(MantaTheme.BG_CONTAINER_HIGH, 14f, context)
            val s = MantaTheme.dp(context, 32f)
            layoutParams = LayoutParams(s, s).apply {
                marginStart = MantaTheme.dp(context, 4f)
            }
            setOnClickListener { onClick() }
        }
    }

    fun showBar() {
        visibility = View.VISIBLE
        queryInput.requestFocus()
        postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT)
        }, 50L)
    }

    fun hideBar() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(queryInput.windowToken, 0)
            queryInput.setText("")
        } catch (_: Throwable) {
        }
        visibility = View.GONE
        onClose()
    }

    fun updateMatches(activeOrdinal: Int, totalMatches: Int) {
        countLabel.text = if (totalMatches > 0) "${activeOrdinal + 1}/$totalMatches" else "0/0"
    }
}
