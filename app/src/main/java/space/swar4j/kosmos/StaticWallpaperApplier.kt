package space.swar4j.kosmos

import android.app.WallpaperManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

enum class WallpaperDestination {
    HOME,
    LOCK,
    BOTH
}

class StaticWallpaperApplier(context: Context) {
    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)

    suspend fun apply(file: java.io.File, destination: WallpaperDestination) {
        withContext(Dispatchers.IO) {
            val wallpaperDirectory = java.io.File(appContext.filesDir, "wallpapers").canonicalFile
            val safeFile = runCatching { file.canonicalFile }.getOrNull()
            if (safeFile == null || !safeFile.isFile || !safeFile.canRead() ||
                safeFile.parentFile != wallpaperDirectory ||
                safeFile.extension.lowercase() !in setOf("jpg", "png", "webp")) {
                throw IOException("The wallpaper file is unavailable")
            }

            val flags = when (destination) {
                WallpaperDestination.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperDestination.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperDestination.BOTH ->
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }

            safeFile.inputStream().buffered().use { input ->
                wallpaperManager.setStream(input, null, false, flags)
            }
        }
    }
}
