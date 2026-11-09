package space.swar4j.kosmos

import android.content.Context
import androidx.core.content.edit

class WallpaperMetadataStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("wallpaper_metadata", Context.MODE_PRIVATE)

    fun isFavorite(fileName: String): Boolean =
        preferences.getStringSet(KEY_FAVORITES, emptySet())?.contains(fileName) == true

    fun setFavorite(fileName: String, favorite: Boolean) {
        val favorites = preferences.getStringSet(KEY_FAVORITES, emptySet())
            .orEmpty().toMutableSet()
        if (favorite) favorites.add(fileName) else favorites.remove(fileName)
        preferences.edit { putStringSet(KEY_FAVORITES, favorites) }
    }

    fun category(fileName: String): String {
        preferences.getString(categoryKey(fileName), null)?.let { return it }
        return when {
            fileName.startsWith("preset_aurora") -> "Nature"
            fileName.startsWith("preset_cosmos") -> "Space"
            fileName.startsWith("preset_dusk") -> "Abstract"
            fileName.startsWith("preset_mono") -> "Minimal"
            fileName.startsWith("preset_solar") || fileName.startsWith("preset_ember") -> "Abstract"
            fileName.startsWith("preset_violet") -> "Space"
            fileName.startsWith("preset_ocean") -> "Nature"
            else -> "Unsorted"
        }
    }

    fun remove(fileName: String) {
        val favorites = preferences.getStringSet(KEY_FAVORITES, emptySet())
            .orEmpty().toMutableSet()
        val presets = preferences.getStringSet(KEY_PRESETS, emptySet())
            .orEmpty().toMutableSet()
        favorites.remove(fileName)
        presets.remove(fileName)
        preferences.edit {
            putStringSet(KEY_FAVORITES, favorites)
            putStringSet(KEY_PRESETS, presets)
            remove(categoryKey(fileName))
        }
    }

    fun rename(oldFileName: String, newFileName: String) {
        val favorites = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toMutableSet()
        val presets = preferences.getStringSet(KEY_PRESETS, emptySet()).orEmpty().toMutableSet()
        val oldCategory = preferences.getString(categoryKey(oldFileName), null)
        if (favorites.remove(oldFileName)) favorites.add(newFileName)
        if (presets.remove(oldFileName)) presets.add(newFileName)
        preferences.edit {
            putStringSet(KEY_FAVORITES, favorites)
            putStringSet(KEY_PRESETS, presets)
            remove(categoryKey(oldFileName))
            if (oldCategory != null) putString(categoryKey(newFileName), oldCategory)
        }
    }

    fun isPreset(fileName: String): Boolean =
        preferences.getStringSet(KEY_PRESETS, emptySet())?.contains(fileName) == true

    fun setPreset(fileName: String, preset: Boolean) {
        val presets = preferences.getStringSet(KEY_PRESETS, emptySet()).orEmpty().toMutableSet()
        if (preset) presets.add(fileName) else presets.remove(fileName)
        preferences.edit { putStringSet(KEY_PRESETS, presets) }
    }

    fun presetFileNames(): Set<String> = preferences.getStringSet(KEY_PRESETS, emptySet()).orEmpty().toSet()

    fun setCategory(fileName: String, category: String) {
        require(category in CATEGORIES)
        preferences.edit { putString(categoryKey(fileName), category) }
    }

    companion object {
        val CATEGORIES = listOf("Nature", "Space", "Abstract", "Minimal", "Anime", "Unsorted")
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PRESETS = "user_presets"
        private fun categoryKey(fileName: String) = "category:$fileName"
    }
}
