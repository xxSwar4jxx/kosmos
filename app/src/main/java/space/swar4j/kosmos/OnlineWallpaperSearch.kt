package space.swar4j.kosmos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Uses Openverse's relevance-ranked, openly licensed image index first and
 * Wikimedia Commons as an independent fallback. Search terms are scored
 * against titles and tags instead of requiring every word to match exactly.
 */
class OnlineWallpaperSearch {
    suspend fun search(query: String, page: Int = 1): List<OnlineWallpaperResult> =
        withContext(Dispatchers.IO) {
            val clean = query.trim().replace(Regex("\\s+"), " ").take(100)
            if (clean.isBlank()) return@withContext emptyList()

            val terms = clean.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 1 }
                .distinct()
            if (terms.isEmpty()) return@withContext emptyList()

            val safePage = page.coerceAtLeast(1)
            val (openverse, commons) = coroutineScope {
                val openverseCall = async { runCatching { searchOpenverse(clean, terms, safePage) } }
                val commonsCall = async { runCatching { searchCommons(clean, terms, safePage) } }
                openverseCall.await() to commonsCall.await()
            }

            if (openverse.isFailure && commons.isFailure) {
                throw IOException("Image search providers are unavailable. Please try again.")
            }

            (openverse.getOrDefault(emptyList()) + commons.getOrDefault(emptyList()))
                .distinctBy { it.downloadUrl.substringBefore('?') }
                .take(40)
        }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=40&license_type=all-cc&mature=false"
        )
        val root = requestJson(endpoint)
        val results = root.optJSONArray("results") ?: return emptyList()

        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val title = item.optString("title").ifBlank { query }
                    .replace(Regex("\\s+"), " ").trim()
                val tags = item.optJSONArray("tags")
                val searchable = buildString {
                    append(title.lowercase())
                    if (tags != null) {
                        for (tagIndex in 0 until tags.length()) {
                            val tag = tags.optJSONObject(tagIndex)
                            append(' ')
                            append(tag?.optString("name").orEmpty().lowercase())
                        }
                    }
                }
                if (id.isBlank() || relevanceScore(searchable, terms) < minimumMatches(terms)) continue

                val fileType = item.optString("filetype").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isAllowedImageUrl(preview, setOf("api.openverse.org"))) continue

                // Openverse serves the full image through its own thumbnail endpoint,
                // so the app never has to trust arbitrary third-party download hosts.
                val download = "https://api.openverse.org/v1/images/$id/thumb/?full_size=true"
                val creator = item.optString("creator").ifBlank { "Openverse contributor" }
                val license = buildString {
                    append(item.optString("license").ifBlank { "Creative Commons" }.uppercase())
                    item.optString("license_version").takeIf { it.isNotBlank() }?.let {
                        append(' ')
                        append(it)
                    }
                }

                add(
                    OnlineWallpaperResult(
                        id = "openverse-$id",
                        title = title.take(120),
                        previewUrl = preview,
                        downloadUrl = download,
                        fileExtension = fileType,
                        creator = creator.take(100),
                        license = license.take(64)
                    )
                )
            }
        }
    }

    private fun searchCommons(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val offset = ((page - 1) * 30).coerceAtMost(300)
        val endpoint = URL(
            "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=$encoded&gsrnamespace=6&gsrlimit=30&gsroffset=$offset" +
                "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640&format=json"
        )
        val root = requestJson(endpoint, "https://commons.wikimedia.org/")
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()

        return buildList {
            val keys = pages.keys()
            while (keys.hasNext()) {
                val pageItem = pages.optJSONObject(keys.next()) ?: continue
                val title = pageItem.optString("title").removePrefix("File:")
                    .replace('_', ' ').trim()
                val imageInfo = pageItem.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val preview = imageInfo.optString("thumburl")
                val download = imageInfo.optString("url")
                val metadata = imageInfo.optJSONObject("extmetadata")
                val description = metadata?.optJSONObject("ImageDescription")
                    ?.optString("value").orEmpty()
                    .replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val searchable = "$title $description".lowercase()
                if (relevanceScore(searchable, terms) < minimumMatches(terms)) continue
                if (!isAllowedImageUrl(preview, setOf("upload.wikimedia.org")) ||
                    !isAllowedImageUrl(download, setOf("upload.wikimedia.org"))
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                if (extension !in SUPPORTED_IMAGE_TYPES) continue

                val creator = metadata?.optJSONObject("Artist")?.optString("value")
                    ?.replace(Regex("<[^>]*>"), " ")?.trim()
                    .orEmpty().ifBlank { "Wikimedia Commons contributor" }
                val license = metadata?.optJSONObject("LicenseShortName")?.optString("value")
                    ?.takeIf { it.isNotBlank() } ?: "See source license"

                add(
                    OnlineWallpaperResult(
                        id = "commons-" + pageItem.optString("pageid"),
                        title = title.removeSuffix(".$extension").take(120),
                        previewUrl = preview,
                        downloadUrl = download,
                        fileExtension = extension,
                        creator = creator.take(100),
                        license = license.take(64)
                    )
                )
            }
        }
    }

    private fun requestJson(url: URL, referer: String = "https://openverse.org/"): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 18_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "KosmosAndroid/0.7 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
            )
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Image provider returned HTTP $status")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeOpenverseUrl(value: String): String {
        if (value.startsWith("http://")) return "https://" + value.removePrefix("http://")
        return value
    }

    private fun relevanceScore(value: String, terms: List<String>): Int {
        val normalized = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        return terms.count { term -> term in tokens || normalized.contains(term) }
    }

    private fun minimumMatches(terms: List<String>): Int =
        if (terms.size <= 2) terms.size else (terms.size + 1) / 2

    private fun isAllowedImageUrl(value: String, hosts: Set<String>): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.lowercase() in hosts &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
