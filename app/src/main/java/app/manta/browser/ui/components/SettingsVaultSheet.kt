package app.manta.browser.ui.components

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.manta.browser.core.MantaDownloadController
import app.manta.browser.core.MantaNative
import app.manta.browser.core.MantaPreferences
import app.manta.browser.ui.theme.MantaTheme
import org.json.JSONObject

/**
 * Full-Screen Manta Settings, Downloads & Clearable MantaDB Storage Vault Sheet (`SettingsVaultSheet.kt`):
 * 1. Search Engine Selector (Google, DuckDuckGo, Brave, Startpage, GitHub).
 * 2. Browser & Privacy Controls (JavaScript, Images, Block Pop-ups, HTTPS-Only, Do-Not-Track, Global Privacy Control).
 * 3. Clearable MantaDB & Per-Origin Storage Inspector (lists every site storing data + 1-tap Shred Origin).
 * 4. Saved Files & Downloads Manager (Open/Share or Permanently Delete any saved file).
 */
class SettingsVaultSheet(
    context: Context,
    private val onSettingsChanged: () -> Unit,
    private val onShredEverything: () -> Unit
) : FrameLayout(context) {

    private val dynamicContainer: LinearLayout

    init {
        visibility = View.GONE
        setBackgroundColor(MantaTheme.BG_OBSIDIAN)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        dynamicContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = MantaTheme.dp(context, 18f)
            setPadding(pad, MantaTheme.dp(context, 28f), pad, MantaTheme.dp(context, 96f))
        }
        scroll.addView(dynamicContainer)
        addView(scroll)
    }

    fun showSheet() {
        rebuildContent()
        visibility = View.VISIBLE
    }

    fun hideSheet() {
        visibility = View.GONE
    }

    private fun rebuildContent() {
        dynamicContainer.removeAllViews()

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 20f)
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = "Manta Control & Vault"
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleCol.addView(TextView(context).apply {
            val cores = MantaNative.getWorkerCoreCount()
            val dbKb = MantaNative.getCustomStorageBytes() / 1024f
            text = "Manta Core v2.0 ($cores Rust Cores) • ${String.format("%.1f KB", dbKb)} Stored"
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 12f
        })
        header.addView(titleCol)

        val closeBtn = TextView(context).apply {
            text = "Done"
            setTextColor(MantaTheme.ON_CONTAINER_PRIMARY)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val pH = MantaTheme.dp(context, 18f)
            val pV = MantaTheme.dp(context, 10f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(MantaTheme.CONTAINER_PRIMARY, 20f, context)
            setOnClickListener { hideSheet() }
        }
        header.addView(closeBtn)
        dynamicContainer.addView(header)

        // SECTION 1: Search Engine Selector
        dynamicContainer.addView(makeSectionTitle("Default Search Engine"))
        val engineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 18f)
            }
        }
        for (eng in MantaPreferences.SEARCH_ENGINES) {
            val active = eng.id == MantaPreferences.searchEngineId
            val chip = TextView(context).apply {
                text = eng.name.substringBefore(' ')
                setTextColor(if (active) MantaTheme.BG_OBSIDIAN else MantaTheme.TEXT_PRIMARY)
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val pV = MantaTheme.dp(context, 10f)
                setPadding(MantaTheme.dp(context, 6f), pV, MantaTheme.dp(context, 6f), pV)
                background = MantaTheme.roundedBg(
                    if (active) MantaTheme.ACCENT_PRIMARY else MantaTheme.BG_CARD,
                    16f,
                    context
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = MantaTheme.dp(context, 5f)
                }
                setOnClickListener {
                    MantaPreferences.searchEngineId = eng.id
                    MantaPreferences.saveToVault()
                    onSettingsChanged()
                    rebuildContent()
                }
            }
            engineRow.addView(chip)
        }
        dynamicContainer.addView(engineRow)

        // SECTION 2: Privacy, Security & Site Capabilities
        dynamicContainer.addView(makeSectionTitle("Browser Controls & Security"))
        dynamicContainer.addView(
            makeToggleRow("JavaScript Execution", "Allow sites to run scripts", MantaPreferences.javascriptEnabled) {
                MantaPreferences.javascriptEnabled = it
                MantaPreferences.saveToVault()
                onSettingsChanged()
                rebuildContent()
            }
        )
        dynamicContainer.addView(
            makeToggleRow("Load Images Automatically", "Render inline & network images", MantaPreferences.imagesEnabled) {
                MantaPreferences.imagesEnabled = it
                MantaPreferences.saveToVault()
                onSettingsChanged()
                rebuildContent()
            }
        )
        dynamicContainer.addView(
            makeToggleRow("HTTPS-Only Mode", "Automatically upgrade cleartext HTTP navigations", MantaPreferences.httpsOnlyMode) {
                MantaPreferences.httpsOnlyMode = it
                MantaPreferences.saveToVault()
                onSettingsChanged()
                rebuildContent()
            }
        )
        dynamicContainer.addView(
            makeToggleRow("Global Privacy Control (Sec-GPC: 1)", "Signal sites not to sell or share data", MantaPreferences.globalPrivacyControl) {
                MantaPreferences.globalPrivacyControl = it
                MantaPreferences.saveToVault()
                onSettingsChanged()
                rebuildContent()
            }
        )
        dynamicContainer.addView(
            makeToggleRow("Block Pop-up Windows", "Prevent unrequested window openings", MantaPreferences.blockPopups) {
                MantaPreferences.blockPopups = it
                MantaPreferences.saveToVault()
                onSettingsChanged()
                rebuildContent()
            }
        )

        // SECTION 3: Saved Downloads & Offline Files
        val files = MantaDownloadController.listSavedFiles(context)
        dynamicContainer.addView(makeSectionTitle("Saved Files & Downloads (${files.size})"))
        if (files.isEmpty()) {
            dynamicContainer.addView(makeEmptyHint("No downloaded files saved on disk."))
        } else {
            for (file in files) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val p = MantaTheme.dp(context, 14f)
                    setPadding(p, p, p, p)
                    background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 18f, context)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = MantaTheme.dp(context, 8f)
                    }
                }
                val infoCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { MantaDownloadController.shareOrExportFile(context, file) }
                }
                infoCol.addView(TextView(context).apply {
                    text = file.name
                    setTextColor(MantaTheme.TEXT_PRIMARY)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                infoCol.addView(TextView(context).apply {
                    text = "${MantaDownloadController.formatBytes(file.sizeBytes)} • Tap to Open / Share"
                    setTextColor(MantaTheme.TEXT_SECONDARY)
                    textSize = 11.5f
                })
                row.addView(infoCol)

                val delBtn = TextView(context).apply {
                    text = "Delete"
                    setTextColor(MantaTheme.ON_CONTAINER_TERTIARY)
                    textSize = 11.5f
                    val pH = MantaTheme.dp(context, 12f)
                    val pV = MantaTheme.dp(context, 7f)
                    setPadding(pH, pV, pH, pV)
                    background = MantaTheme.roundedBg(MantaTheme.CONTAINER_TERTIARY, 14f, context)
                    setOnClickListener {
                        MantaDownloadController.deleteSavedFile(context, file.name)
                        rebuildContent()
                        onSettingsChanged()
                    }
                }
                row.addView(delBtn)
                dynamicContainer.addView(row)
            }
        }

        // SECTION 4: MantaDB & Per-Origin Clearable Storage Inspector
        dynamicContainer.addView(makeSectionTitle("MantaDB Per-Site Storage Inspector"))
        val manifestRaw = MantaNative.inspectStorageManifest()
        var renderedOrigins = 0
        try {
            val root = JSONObject(manifestRaw)
            val originsArr = root.optJSONArray("origins")
            if (originsArr != null) {
                for (i in 0 until originsArr.length()) {
                    val o = originsArr.getJSONObject(i)
                    val originUrl = o.optString("origin", "")
                    if (originUrl.isBlank() || originUrl.contains("settings.manta.local")) continue
                    renderedOrigins++
                    val bytes = o.optLong("used_bytes", 0L)
                    val kvKeys = o.optInt("local_storage_keys", 0)
                    val cookies = o.optInt("cookies", 0)
                    val savedFilesArr = o.optJSONArray("saved_files")
                    val vfsCount = savedFilesArr?.length() ?: 0

                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val p = MantaTheme.dp(context, 14f)
                        setPadding(p, p, p, p)
                        background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 18f, context)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = MantaTheme.dp(context, 8f)
                        }
                    }

                    val col = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(context).apply {
                        text = originUrl
                        setTextColor(MantaTheme.ACCENT_PRIMARY)
                        textSize = 13.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    col.addView(TextView(context).apply {
                        text = "${MantaDownloadController.formatBytes(bytes)} • $kvKeys KV Keys • $cookies Cookies • $vfsCount VFS Files"
                        setTextColor(MantaTheme.TEXT_SECONDARY)
                        textSize = 11.5f
                    })
                    card.addView(col)

                    val shredOriginBtn = TextView(context).apply {
                        text = "Shred Site"
                        setTextColor(MantaTheme.ON_CONTAINER_TERTIARY)
                        textSize = 11.5f
                        val pH = MantaTheme.dp(context, 12f)
                        val pV = MantaTheme.dp(context, 7f)
                        setPadding(pH, pV, pH, pV)
                        background = MantaTheme.roundedBg(MantaTheme.CONTAINER_TERTIARY, 14f, context)
                        setOnClickListener {
                            MantaNative.storageClearOrigin(originUrl)
                            MantaNative.flushCustomStorage()
                            Toast.makeText(context, "Shredded storage for $originUrl", Toast.LENGTH_SHORT).show()
                            rebuildContent()
                            onSettingsChanged()
                        }
                    }
                    card.addView(shredOriginBtn)
                    dynamicContainer.addView(card)
                }
            }
        } catch (_: Throwable) {
        }

        if (renderedOrigins == 0) {
            dynamicContainer.addView(makeEmptyHint("0 sites currently storing data in MantaDB."))
        }

        // Global Permanent Shred Button
        val shredAllBtn = TextView(context).apply {
            text = "⚡ Permanently Shred All Site Storage, Downloads & RAM"
            setTextColor(MantaTheme.ON_CONTAINER_TERTIARY)
            textSize = 13.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            val pV = MantaTheme.dp(context, 15f)
            setPadding(pV, pV, pV, pV)
            background = MantaTheme.roundedBg(MantaTheme.CONTAINER_TERTIARY, 22f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 18f)
            }
            setOnClickListener {
                hideSheet()
                onShredEverything()
            }
        }
        dynamicContainer.addView(shredAllBtn)
    }

    private fun makeSectionTitle(title: String): TextView {
        return TextView(context).apply {
            text = title.uppercase()
            setTextColor(MantaTheme.ACCENT_PRIMARY)
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = MantaTheme.dp(context, 10f)
                bottomMargin = MantaTheme.dp(context, 8f)
            }
        }
    }

    private fun makeEmptyHint(msg: String): TextView {
        return TextView(context).apply {
            text = msg
            setTextColor(MantaTheme.TEXT_MUTED)
            textSize = 12.5f
            val p = MantaTheme.dp(context, 14f)
            setPadding(p, p, p, p)
            background = MantaTheme.roundedBg(MantaTheme.BG_SURFACE, 16f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 12f)
            }
        }
    }

    private fun makeToggleRow(
        title: String,
        subtitle: String,
        isChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = MantaTheme.dp(context, 14f)
            setPadding(p, p, p, p)
            background = MantaTheme.roundedBg(MantaTheme.BG_CARD, 18f, context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MantaTheme.dp(context, 8f)
            }
            setOnClickListener { onToggle(!isChecked) }
        }

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(context).apply {
            text = title
            setTextColor(MantaTheme.TEXT_PRIMARY)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextView(context).apply {
            text = subtitle
            setTextColor(MantaTheme.TEXT_SECONDARY)
            textSize = 11.5f
        })
        row.addView(col)

        val pill = TextView(context).apply {
            text = if (isChecked) "ON" else "OFF"
            setTextColor(if (isChecked) MantaTheme.ON_CONTAINER_SECONDARY else MantaTheme.TEXT_MUTED)
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            val pH = MantaTheme.dp(context, 14f)
            val pV = MantaTheme.dp(context, 6f)
            setPadding(pH, pV, pH, pV)
            background = MantaTheme.roundedBg(
                if (isChecked) MantaTheme.CONTAINER_SECONDARY else MantaTheme.BG_CONTAINER_HIGH,
                14f,
                context
            )
        }
        row.addView(pill)
        return row
    }
}
