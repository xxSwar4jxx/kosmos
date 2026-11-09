package space.swar4j.kosmos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineWallpaperResult(
    val id: String,
    val title: String,
    val previewUrl: String,
    val downloadUrl: String,
    val fileExtension: String,
    val creator: String,
    val license: String
)

/** Unsplash's image search endpoint, with strict relevance checks and stable image URLs. */
class OnlineWallpaperSearch {
    suspend fun search(query: String): List<OnlineWallpaperResult> = withContext(Dispatchers.IO) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(100)
        if (clean.isBlank()) return@withContext emptyList()
        val terms = clean.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }.distinct()
        if (terms.isEmpty()) return@withContext emptyList()

        val encoded = URLEncoder.encode(clean, Charsets.UTF_8.name())
        val endpoint = URL("https://unsplash.com/napi/search/photos?query=$encoded&per_page=30&page=1")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 18_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 Kosmos/0.7")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://unsplash.com/")
        }
        try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                429 -> throw IOException("Unsplash is temporarily rate-limiting searches. Try again shortly.")
                else -> throw IOException("Unsplash search failed (HTTP $status)")
            }
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val results = root.optJSONArray("results") ?: return@withContext emptyList()
            val seen = HashSet<String>()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val urls = item.optJSONObject("urls") ?: continue
                    val preview = urls.optString("small")
                    val download = urls.optString("regular")
                    val description = item.optString("alt_description").ifBlank {
                        item.optString("description")
                    }.replace(Regex("\\s+"), " ").trim()
                    val title = description.ifBlank { "Untitled photograph" }.take(120)
                    val searchable = buildString {
                        append(title.lowercase())
                        val tags = item.optJSONArray("tags")
                        if (tags != null) for (tagIndex in 0 until tags.length()) {
                            append(' ')
                            append(tags.optJSONObject(tagIndex)?.optString("title").orEmpty().lowercase())
                        }
                    }
                    val matches = terms.count(searchable::contains)
                    if (id.isBlank() || !seen.add(id) || matches == 0 ||
                        !isImageUrl(preview) || !isImageUrl(download)
                    ) continue
                    val user = item.optJSONObject("user")
                    val creator = user?.optString("name").orEmpty().ifBlank { "Unsplash contributor" }
                    add(
                        OnlineWallpaperResult(
                            id = id,
                            title = title,
                            previewUrl = preview,
                            downloadUrl = download,
                            fileExtension = "jpg",
                            creator = creator.take(100),
                            license = "Unsplash License"
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.equals("images.unsplash.com", true) &&
            url.userInfo == null && (url.port == -1 || url.port == 443) &&
            url.path.startsWith("/photo-")
    }.getOrDefault(false)
}
