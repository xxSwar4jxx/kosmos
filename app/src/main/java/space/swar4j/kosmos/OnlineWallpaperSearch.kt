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

/**
 * Searches two independent image indexes and keeps only results whose searchable
 * metadata matches the complete query. Results are deduplicated across providers.
 */
class OnlineWallpaperSearch {
    suspend fun search(query: String): List<OnlineWallpaperResult> = withContext(Dispatchers.IO) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(100)
        if (clean.isBlank()) return@withContext emptyList()
        val terms = clean.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }.distinct()
        if (terms.isEmpty()) return@withContext emptyList()

        val unsplashAttempt = runCatching { searchUnsplash(clean, terms) }
        val commonsAttempt = runCatching { searchCommons(clean, terms) }
        if (unsplashAttempt.isFailure && commonsAttempt.isFailure) {
            throw IOException("Image providers are unavailable. Please try again.")
        }
        (unsplashAttempt.getOrDefault(emptyList()) + commonsAttempt.getOrDefault(emptyList()))
            .distinctBy { it.downloadUrl.substringBefore('?') }
            .take(40)
    }

    private fun searchUnsplash(query: String, terms: List<String>): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL("https://unsplash.com/napi/search/photos?query=$encoded&per_page=30&page=1")
        val root = requestJson(endpoint, "https://unsplash.com/")
        val results = root.optJSONArray("results") ?: return emptyList()
        val seen = HashSet<String>()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val urls = item.optJSONObject("urls") ?: continue
                val preview = urls.optString("small")
                val download = urls.optString("regular")
                val description = item.optString("alt_description").ifBlank {
                    item.optString("description")
                }.replace(Regex("\\s+"), " ").trim()
                val tags = item.optJSONArray("tags")
                val searchable = buildString {
                    append(description.lowercase())
                    if (tags != null) for (tagIndex in 0 until tags.length()) {
                        append(' ')
                        append(tags.optJSONObject(tagIndex)?.optString("title").orEmpty().lowercase())
                    }
                }
                if (id.isBlank() || !seen.add(id) || !matchesAllTerms(searchable, terms) ||
                    !isAllowedImageUrl(preview, setOf("images.unsplash.com")) ||
                    !isAllowedImageUrl(download, setOf("images.unsplash.com"))
                ) continue
                val creator = item.optJSONObject("user")?.optString("name").orEmpty()
                    .ifBlank { "Unsplash contributor" }
                add(
                    OnlineWallpaperResult(
                        id = "unsplash-$id",
                        title = description.ifBlank { query }.take(120),
                        previewUrl = preview,
                        downloadUrl = download,
                        fileExtension = "jpg",
                        creator = creator.take(100),
                        license = "Unsplash License"
                    )
                )
            }
        }
    }

    private fun searchCommons(query: String, terms: List<String>): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=$encoded&gsrnamespace=6&gsrlimit=30&prop=imageinfo" +
                "&iiprop=url%7Cextmetadata&iiurlwidth=640&format=json"
        )
        val root = requestJson(endpoint, "https://commons.wikimedia.org/")
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return buildList {
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                val title = page.optString("title").removePrefix("File:")
                val imageInfo = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val preview = imageInfo.optString("thumburl")
                val download = imageInfo.optString("url")
                val metadata = imageInfo.optJSONObject("extmetadata")
                val description = metadata?.optJSONObject("ImageDescription")?.optString("value").orEmpty()
                    .replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val searchable = "$title $description".lowercase()
                if (!matchesAllTerms(searchable, terms) ||
                    !isAllowedImageUrl(preview, setOf("upload.wikimedia.org")) ||
                    !isAllowedImageUrl(download, setOf("upload.wikimedia.org"))
                ) continue
                val extension = download.substringBefore('?').substringAfterLast('.', "").lowercase()
                if (extension !in setOf("jpg", "jpeg", "png", "webp", "gif")) continue
                val creator = metadata?.optJSONObject("Artist")?.optString("value")
                    ?.replace(Regex("<[^>]*>"), " ")?.trim().orEmpty().ifBlank { "Wikimedia Commons contributor" }
                val license = metadata?.optJSONObject("LicenseShortName")?.optString("value")
                    ?.takeIf { it.isNotBlank() } ?: "See source license"
                add(
                    OnlineWallpaperResult(
                        id = "commons-${page.optString("pageid")}",
                        title = title.removeSuffix(".$extension").replace('_', ' ').take(120),
                        previewUrl = preview,
                        downloadUrl = download,
                        fileExtension = if (extension == "jpeg") "jpg" else extension,
                        creator = creator.take(100),
                        license = license.take(64)
                    )
                )
            }
        }
    }

    private fun requestJson(url: URL, referer: String): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 18_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "KosmosAndroid/0.8 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Image provider returned HTTP $status")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun matchesAllTerms(value: String, terms: List<String>): Boolean {
        val normalized = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val tokens = normalized.split(Regex("\\s+")).toSet()
        return terms.all { term -> term in tokens }
    }

    private fun isAllowedImageUrl(value: String, hosts: Set<String>): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.lowercase() in hosts &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)
}
