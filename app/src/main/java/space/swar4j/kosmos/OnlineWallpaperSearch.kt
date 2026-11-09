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

/** Relevance-ranked search for openly licensed images through Openverse. */
class OnlineWallpaperSearch {
    suspend fun search(query: String): List<OnlineWallpaperResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val terms = cleanQuery.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
            .distinct()
        if (terms.isEmpty()) return@withContext emptyList()

        val searchTerms = linkedSetOf(cleanQuery, "\"$cleanQuery\"")
        if (!cleanQuery.contains("wallpaper", ignoreCase = true)) searchTerms.add("$cleanQuery wallpaper")

        val candidates = LinkedHashMap<String, RankedResult>()
        for (searchTerm in searchTerms) {
            val encoded = URLEncoder.encode(searchTerm, Charsets.UTF_8.name())
            val endpoint = URL("https://api.openverse.org/v1/images/?q=$encoded&page_size=20&filter_dead=true&mature=false")
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 18_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "KosmosAndroid/0.6 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                if (status == 429) throw IOException("Openverse is rate-limiting searches. Wait a moment and try again.")
                if (status != HttpURLConnection.HTTP_OK) throw IOException("Image search failed (HTTP $status)")
                val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val results = payload.optJSONArray("results") ?: continue
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val thumbnail = item.optString("thumbnail")
                    val title = item.optString("title").cleanText().take(120)
                    val extension = item.optString("filetype").lowercase()
                    val width = item.optInt("width")
                    val height = item.optInt("height")
                    if (id.isBlank() || title.isBlank() || extension !in SUPPORTED_IMAGE_EXTENSIONS ||
                        width < MIN_IMAGE_EDGE || height < MIN_IMAGE_EDGE || !isOpenverseThumbnail(thumbnail)
                    ) continue

                    val tags = buildList {
                        val array = item.optJSONArray("tags") ?: return@buildList
                        for (tagIndex in 0 until array.length()) {
                            array.optJSONObject(tagIndex)?.optString("name")
                                ?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
                        }
                    }
                    val titleLower = title.lowercase()
                    val searchable = "$titleLower ${tags.joinToString(" ")}"
                    val matchingTerms = terms.count(searchable::contains)
                    if (matchingTerms == 0) continue

                    val phrase = cleanQuery.lowercase()
                    val titleScore = when {
                        titleLower.contains(phrase) -> 120
                        terms.all(titleLower::contains) -> 85
                        else -> terms.count(titleLower::contains) * 24
                    }
                    val tagScore = terms.count { term -> tags.any { it.contains(term) } } * 10
                    val fields = item.optJSONArray("fields_matched")
                    val titleMatched = fields?.let { array ->
                        (0 until array.length()).any { array.optString(it) == "title" }
                    } == true
                    val resolutionScore = ((width.toLong() * height) / 1_000_000L).coerceAtMost(20).toInt()
                    val ratio = width.toFloat() / height.toFloat()
                    val shapeScore = if (ratio in 0.45f..1.9f) 8 else 0
                    val score = titleScore + tagScore + resolutionScore + shapeScore + if (titleMatched) 18 else 0

                    val result = OnlineWallpaperResult(
                        id = id,
                        title = title,
                        previewUrl = thumbnail,
                        downloadUrl = "$thumbnail?full_size=true&compressed=false",
                        fileExtension = if (extension == "jpeg") "jpg" else extension,
                        creator = item.optString("creator").cleanText().ifBlank { "Unknown creator" }.take(100),
                        license = listOf(item.optString("license").uppercase(), item.optString("license_version"))
                            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "See source for license" }.take(60)
                    )
                    val previous = candidates[id]
                    if (previous == null || score > previous.score) candidates[id] = RankedResult(result, score)
                }
            } finally {
                connection.disconnect()
            }
            if (candidates.size >= MAX_RESULTS) break
        }

        candidates.values.sortedByDescending { it.score }.take(MAX_RESULTS).map { it.result }
    }

    private fun String.cleanText(): String = replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun isOpenverseThumbnail(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.equals("api.openverse.org", ignoreCase = true) &&
            url.path.startsWith("/v1/images/") && url.path.endsWith("/thumb/") &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private data class RankedResult(val result: OnlineWallpaperResult, val score: Int)

    companion object {
        private const val MAX_RESULTS = 40
        private const val MIN_IMAGE_EDGE = 480
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "wallpaper", "image", "images", "photo", "photos")
    }
}
