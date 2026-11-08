package space.swar4j.kosmos

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File

class LiveWallpaperLauncher(context: Context) {
    private val appContext = context.applicationContext

    fun launch(file: File) {
        require(file.isFile && file.canRead()) { "The selected live wallpaper is unavailable" }

        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_WALLPAPER_PATH, file.absolutePath)
            .apply()

        val component = ComponentName(appContext, KosmosLiveWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    companion object {
        const val PREFERENCES = "kosmos_wallpaper"
        const val ACTIVE_WALLPAPER_PATH = "active_wallpaper_path"
    }
}
