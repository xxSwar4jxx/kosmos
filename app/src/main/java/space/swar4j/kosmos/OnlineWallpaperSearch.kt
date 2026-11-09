package space.swar4j.kosmos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineWallpaperResult(
    val id: Long,
    val title: String,
    val previewUrl: String,
    val downloadUrl: String,
    val creator: String,
    val license: String
)

class OnlineWallpaperSearch {
    suspend fun search(query: String): List<OnlineWallpaperResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().replace(Regex("\\s+"), " ").take(100)
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val primaryQuery = cleanQuery.replace(Regex("(?i)zero\\s*two|zerotwo"), "Zero Two Darling in the Franxx")
        val queries = linkedSetOf(
            "intitle:\"$primaryQuery\"",
            "\"$primaryQuery\"",
            "$primaryQuery wallpaper"
        )
        val results = LinkedHashMap<Long, OnlineWallpaperResult>()

        for (searchTerm in queries) {
            if (results.size >= MAX_RESULTS) break
            val encoded = URLEncoder.encode(searchTerm, Charsets.UTF_8.name())
            val endpoint = URL(
                "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
                    "&gsrsearch=$encoded&gsrnamespace=6&gsrlimit=50&gsrsort=relevance" +
                    "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640&format=json"
            )
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "KosmosAndroid/0.5 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
                )
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    if (status == 429) throw IOException("Wikimedia is rate-limiting requests. Wait a moment and try again.")
                    throw IOException("Image search failed (HTTP $status)")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
                    ?: continue
                val keys = pages.keys()
                while (keys.hasNext() && results.size < MAX_RESULTS) {
                    val page = pages.optJSONObject(keys.next()) ?: continue
                    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val preview = info.optString("thumburl")
                    val original = info.optString("url")
                    if (!isCommonsImageUrl(preview) || !isCommonsImageUrl(original)) continue
                    val extension = URL(original).path.substringAfterLast('.', "").lowercase()
                    if (extension !in SUPPORTED_IMAGE_EXTENSIONS) continue
                    val metadata = info.optJSONObject("extmetadata")
                    val id = page.optLong("pageid")
                    if (id <= 0 || results.containsKey(id)) continue
                    results[id] = OnlineWallpaperResult(
                        id = id,
                        title = page.optString("title").removePrefix("File:").take(100),
                        previewUrl = preview,
                        downloadUrl = original,
                        creator = metadata?.optJSONObject("Artist")?.optString("value")
                            ?.replace(Regex("<[^>]*>"), "")?.take(120).orEmpty()
                            .ifBlank { "Unknown creator" },
                        license = metadata?.optJSONObject("LicenseShortName")?.optString("value")
                            ?.take(60).orEmpty().ifBlank { "See source for license" }
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        results.values.toList()
    }

    private fun isCommonsImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.equals("upload.wikimedia.org", ignoreCase = true) &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    companion object {
        private const val MAX_RESULTS = 60
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
