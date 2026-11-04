package app.manta.browser.core

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.File

/**
 * Manta Zero-Ghost-Cache & Custom Vault Manager (`ZeroDiskJanitor.kt`):
 * 1. Automatically wipes transient OS HTTP/GPU/Shader caches on pause/exit so 0 B of un-clearable cache accumulates.
 * 2. Persists user-saved data inside Manta's single clearable Rust container (`manta_vault.db`).
 * 3. Provides 1-tap per-origin or full permanent shredding.
 */
object ZeroDiskJanitor {

    private const val CUSTOM_STORAGE_FILENAME = "manta_vault.db"

    fun getCustomStorageFilePath(context: Context): String {
        return File(context.filesDir, CUSTOM_STORAGE_FILENAME).absolutePath
    }

    fun enforceZeroCacheAndFlushCustomStorage(context: Context) {
        MantaNative.flushCustomStorage()
        wipeTransientCachesOnly(context)
    }

    fun purgeAllDiskAndRamTraces(context: Context, alsoDeleteDownloads: Boolean = false) {
        MantaNative.purgeAllVolatileRam()

        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
        }

        try {
            File(context.filesDir, CUSTOM_STORAGE_FILENAME).let {
                if (it.exists()) it.delete()
            }
        } catch (_: Throwable) {
        }

        if (alsoDeleteDownloads) {
            MantaDownloadController.shredAllDownloads(context)
        }

        wipeTransientCachesOnly(context)
    }

    private fun wipeTransientCachesOnly(context: Context) {
        try {
            deleteDirContents(context.cacheDir)
            context.codeCacheDir?.let { deleteDirContents(it) }
            val dataDir = context.dataDir
            if (dataDir != null) {
                val webviewDir = File(dataDir, "app_webview")
                if (webviewDir.exists()) {
                    File(webviewDir, "Cache").let { if (it.exists()) deleteDirContents(it) }
                    File(webviewDir, "GPUCache").let { if (it.exists()) deleteDirContents(it) }
                    File(webviewDir, "Code Cache").let { if (it.exists()) deleteDirContents(it) }
                }
                File(dataDir, "app_textures").let { if (it.exists()) deleteDirContents(it) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun deleteDirContents(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                deleteDirContents(child)
            }
            try {
                child.delete()
            } catch (_: Throwable) {
            }
        }
    }
}
