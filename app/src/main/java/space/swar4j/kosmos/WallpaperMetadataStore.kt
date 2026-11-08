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
            else -> "Unsorted"
        }
    }

    fun setCategory(fileName: String, category: String) {
        require(category in CATEGORIES)
        preferences.edit { putString(categoryKey(fileName), category) }
    }

    companion object {
        val CATEGORIES = listOf("Nature", "Space", "Abstract", "Minimal", "Unsorted")
        private const val KEY_FAVORITES = "favorites"
        private fun categoryKey(fileName: String) = "category:$fileName"
    }
}
