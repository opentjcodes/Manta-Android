package app.manta.browser.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class MantaSavedFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * Manages Manta's Clearable Downloads & Offline Saved Pages Vault (`filesDir/manta_downloads`).
 * Supports:
 * 1. Direct HTTP/HTTPS background file downloads with automatic filename extraction.
 * 2. `data:` and Base64 URI decoding & saving.
 * 3. 1-Tap "Save Page Offline (.html)" snapshots.
 * 4. Inspecting, opening/sharing, or permanently shredding saved files in 1 tap.
 */
object MantaDownloadController {

    private const val DOWNLOAD_FOLDER = "manta_downloads"

    fun getDownloadsDir(context: Context): File {
        val dir = File(context.filesDir, DOWNLOAD_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun listSavedFiles(context: Context): List<MantaSavedFile> {
        val dir = getDownloadsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .map {
                MantaSavedFile(
                    name = it.name,
                    path = it.absolutePath,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified()
                )
            }
    }

    fun deleteSavedFile(context: Context, filename: String): Boolean {
        val target = File(getDownloadsDir(context), sanitizeFileName(filename))
        return if (target.exists()) target.delete() else false
    }

    fun shredAllDownloads(context: Context) {
        val dir = getDownloadsDir(context)
        dir.listFiles()?.forEach {
            try {
                it.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun saveOfflineHtmlSnapshot(
        context: Context,
        pageTitle: String,
        htmlContent: String,
        onComplete: (MantaSavedFile?) -> Unit
    ) {
        thread(start = true) {
            try {
                val baseName = sanitizeFileName(pageTitle.ifBlank { "manta_page" })
                    .removeSuffix(".html")
                val fileName = "${baseName}.html"
                val dest = uniqueFile(getDownloadsDir(context), fileName)
                FileOutputStream(dest).use { out ->
                    out.write(htmlContent.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                val saved = MantaSavedFile(dest.name, dest.absolutePath, dest.length(), dest.lastModified())
                onComplete(saved)
            } catch (_: Throwable) {
                onComplete(null)
            }
        }
    }

    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        onFinished: (MantaSavedFile?, String?) -> Unit
    ) {
        if (url.startsWith("data:")) {
            saveDataUri(context, url, onFinished)
            return
        }

        val guessedName = sanitizeFileName(
            URLUtil.guessFileName(url, contentDisposition, mimeType).ifBlank { "download.bin" }
        )

        Toast.makeText(context, "Downloading $guessedName...", Toast.LENGTH_SHORT).show()

        thread(start = true) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    useCaches = false
                    if (userAgent.isNotBlank()) {
                        setRequestProperty("User-Agent", userAgent)
                    }
                }

                val destFile = uniqueFile(getDownloadsDir(context), guessedName)
                conn.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                        }
                        output.flush()
                    }
                }
                val saved = MantaSavedFile(
                    name = destFile.name,
                    path = destFile.absolutePath,
                    sizeBytes = destFile.length(),
                    lastModified = destFile.lastModified()
                )
                onFinished(saved, null)
            } catch (e: Throwable) {
                onFinished(null, e.message ?: "Download failed")
            }
        }
    }

    private fun saveDataUri(
        context: Context,
        dataUri: String,
        onFinished: (MantaSavedFile?, String?) -> Unit
    ) {
        thread(start = true) {
            try {
                val commaIdx = dataUri.indexOf(',')
                if (commaIdx == -1) {
                    onFinished(null, "Invalid data URI")
                    return@thread
                }
                val meta = dataUri.substring(5, commaIdx)
                val payload = dataUri.substring(commaIdx + 1)
                val isBase64 = meta.endsWith(";base64")
                val mime = meta.substringBefore(';').ifBlank { "text/plain" }
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                    mime.contains("pdf") -> "pdf"
                    mime.contains("json") -> "json"
                    mime.contains("html") -> "html"
                    else -> "txt"
                }
                val bytes = if (isBase64) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray(Charsets.UTF_8)
                }
                val dest = uniqueFile(getDownloadsDir(context), "manta_saved.$ext")
                FileOutputStream(dest).use {
                    it.write(bytes)
                    it.flush()
                }
                onFinished(
                    MantaSavedFile(dest.name, dest.absolutePath, dest.length(), dest.lastModified()),
                    null
                )
            } catch (e: Throwable) {
                onFinished(null, e.message ?: "Failed saving data URI")
            }
        }
    }

    fun shareOrExportFile(context: Context, file: MantaSavedFile) {
        try {
            val f = File(file.path)
            if (!f.exists()) return
            val previewText = if (f.length() < 64 * 1024 && (f.name.endsWith(".txt") || f.name.endsWith(".html") || f.name.endsWith(".json") || f.name.endsWith(".md"))) {
                f.readText(Charsets.UTF_8)
            } else {
                "Manta Saved File: ${f.name} (${formatBytes(f.length())})\nPath: ${f.absolutePath}"
            }
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, f.name)
                putExtra(Intent.EXTRA_TEXT, previewText)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share ${f.name}"))
        } catch (_: Throwable) {
            Toast.makeText(context, "Saved at: ${file.path}", Toast.LENGTH_LONG).show()
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024f)
            else -> String.format("%.2f MB", bytes / (1024f * 1024f))
        }
    }

    private fun uniqueFile(dir: File, filename: String): File {
        var candidate = File(dir, filename)
        if (!candidate.exists()) return candidate
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim()
        return clean.ifBlank { "manta_file.bin" }
    }
}
