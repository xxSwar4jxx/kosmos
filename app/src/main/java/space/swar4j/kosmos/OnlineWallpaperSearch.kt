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
        val cleanQuery = query.trim().take(100)
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=filetype:bitmap%20$encoded&gsrnamespace=6&gsrlimit=30" +
                "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640&format=json"
        )
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Kosmos-Android/1.0 (wallpaper search)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Image search failed (HTTP ${connection.responseCode})")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
                ?: return@withContext emptyList()
            buildList {
                val keys = pages.keys()
                while (keys.hasNext() && size < 30) {
                    val page = pages.optJSONObject(keys.next()) ?: continue
                    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val preview = info.optString("thumburl")
                    val original = info.optString("url")
                    if (!isCommonsImageUrl(preview) || !isCommonsImageUrl(original)) continue
                    val extension = URL(original).path.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("jpg", "jpeg", "png", "webp", "gif")) continue
                    val metadata = info.optJSONObject("extmetadata")
                    add(
                        OnlineWallpaperResult(
                            id = page.optLong("pageid"),
                            title = page.optString("title").removePrefix("File:").take(100),
                            previewUrl = preview,
                            downloadUrl = original,
                            creator = metadata?.optJSONObject("Artist")?.optString("value")
                                ?.replace(Regex("<[^>]*>"), "")?.take(120).orEmpty().ifBlank { "Unknown creator" },
                            license = metadata?.optJSONObject("LicenseShortName")?.optString("value")
                                ?.take(60).orEmpty().ifBlank { "See source for license" }
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isCommonsImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.equals("upload.wikimedia.org", ignoreCase = true) &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)
}
