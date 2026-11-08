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
    private val wallpaperManager = WallpaperManager.getInstance(context.applicationContext)

    suspend fun apply(file: java.io.File, destination: WallpaperDestination) {
        withContext(Dispatchers.IO) {
            if (!file.isFile || !file.canRead()) {
                throw IOException("The wallpaper file is unavailable")
            }

            val flags = when (destination) {
                WallpaperDestination.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperDestination.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperDestination.BOTH ->
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }

            file.inputStream().buffered().use { input ->
                wallpaperManager.setStream(input, null, true, flags)
            }
        }
    }
}
