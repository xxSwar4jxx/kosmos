package space.swar4j.kosmos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
            val (animeResults, generalProviders) = coroutineScope {
                val animeCall = async { runCatching { searchAnimeProviders(clean, safePage) }.getOrDefault(emptyList()) }
                val openverseCall = async { runCatching { searchOpenverse(clean, terms, safePage) }.getOrDefault(emptyList()) }
                val commonsCall = async { runCatching { searchCommons(clean, terms, safePage) }.getOrDefault(emptyList()) }
                animeCall.await() to (openverseCall.await() to commonsCall.await())
            }.let { it.first to it.second }
            // Anime results take priority. General providers are only shown when
            // AniList/Danbooru cannot find a relevant anime illustration.
            val selected = if (animeResults.isNotEmpty()) animeResults
                else generalProviders.first + generalProviders.second

            if (selected.isEmpty()) throw IOException("Image search providers are unavailable. Please try again.")
            selected.distinctBy { it.downloadUrl.substringBefore('?') }
                .sortedByDescending { result ->
                    val titleScore = relevanceScore(result.title, terms)
                    val exactPhraseBonus = if (result.title.contains(clean, ignoreCase = true)) 4 else 0
                    titleScore * 4 + exactPhraseBonus
                }
                .take(40)
        }

    private data class AnimeCharacter(val name: String, val series: String, val image: String)

    private fun searchAnimeProviders(query: String, page: Int): List<OnlineWallpaperResult> {
        val characters = searchAniListCharacters(query)
        val explicitCosplaySearch = query.contains("cosplay", ignoreCase = true)
        val tagQueries = buildList {
            add(query.toDanbooruTagQuery(explicitCosplaySearch))
            characters.forEach { character ->
                add(character.name.toDanbooruTagQuery(explicitCosplaySearch))
                if (character.series.isNotBlank()) add((character.name + " " + character.series).toDanbooruTagQuery(explicitCosplaySearch))
            }
        }.filter { it.isNotBlank() }.distinct().take(8)

        val danbooru = tagQueries.flatMap { tags -> searchDanbooru(tags, page) }
            .distinctBy { it.id }.take(32)
        val anilist = characters.mapNotNull { character ->
            if (!isSecureImageUrl(character.image)) return@mapNotNull null
            OnlineWallpaperResult(
                id = "anilist-${character.name.hashCode()}",
                title = if (character.series.isBlank()) character.name else "${character.name} • ${character.series}",
                previewUrl = character.image,
                downloadUrl = character.image,
                fileExtension = "jpg",
                creator = "AniList character artwork",
                license = "See original AniList source"
            )
        }
        return (danbooru + anilist).distinctBy { it.downloadUrl.substringBefore('?') }
    }

    private fun searchAniListCharacters(query: String): List<AnimeCharacter> {
        val connection = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3")
        }
        val gql = """
            query (${' {
              Page(perPage: 10) {
                media(search: ${' ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: ${' {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search: String) {
              Page(perPage: 10) {
                media(search: $search, type: ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: $search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search, type: ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: $search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search: String) {
              Page(perPage: 10) {
                media(search: $search, type: ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: $search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search: String) {
              Page(perPage: 10) {
                media(search: $search, type: ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: $search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search, type: ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: $search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
}search: String) {
              Page(perPage: 10) {
                media(search: $search, type: ANIME, isAdult: false) {
                  title { romaji english }
                  characters(perPage: 8, sort: ROLE) { nodes { name { full } image { large } } }
                }
                characters(search: $search) {
                  nodes { name { full } image { large } media(perPage: 5) { nodes { title { romaji english } } } }
                }
              }
            }
        """.trimIndent()
        try {
            val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", query))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val pageData = root.optJSONObject("data")?.optJSONObject("Page") ?: return emptyList()
            val results = LinkedHashMap<String, AnimeCharacter>()
            val media = pageData.optJSONArray("media") ?: org.json.JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                val nodes = item.optJSONObject("characters")?.optJSONArray("nodes") ?: continue
                for (j in 0 until nodes.length()) {
                    val character = nodes.optJSONObject(j) ?: continue
                    val name = character.optJSONObject("name")?.optString("full").orEmpty()
                    val image = character.optJSONObject("image")?.optString("large").orEmpty()
                    if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
                }
            }
            val direct = pageData.optJSONObject("characters")?.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until direct.length()) {
                val item = direct.optJSONObject(i) ?: continue
                val name = item.optJSONObject("name")?.optString("full").orEmpty()
                val image = item.optJSONObject("image")?.optString("large").orEmpty()
                val mediaNodes = item.optJSONObject("media")?.optJSONArray("nodes")
                val title = mediaNodes?.optJSONObject(0)?.optJSONObject("title")
                val series = title?.optString("romaji").orEmpty().ifBlank { title?.optString("english").orEmpty() }
                if (name.isNotBlank() && isSecureImageUrl(image)) results[name.lowercase()] = AnimeCharacter(name, series, image)
            }
            return results.values.take(20)
        } finally { connection.disconnect() }
    }

    private fun searchDanbooru(tags: String, page: Int): List<OnlineWallpaperResult> {
        val endpoint = URL("https://danbooru.donmai.us/posts.json?limit=20&page=$page&tags=" +
            URLEncoder.encode(tags, Charsets.UTF_8.name()))
        val root = requestJsonArray(endpoint, "https://danbooru.donmai.us/")
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                // Arka deliberately keeps explicit material out of the teen-facing app.
                if (item.optString("rating") != "s") continue
                val ext = item.optString("file_ext").lowercase().let { if (it == "jpeg") "jpg" else it }
                if (ext !in SUPPORTED_IMAGE_TYPES) continue
                val full = item.optString("large_file_url").ifBlank { item.optString("file_url") }
                val preview = item.optString("preview_file_url").ifBlank { full }
                if (!isSecureImageUrl(full) || !isSecureImageUrl(preview)) continue
                val id = item.optLong("id")
                val title = item.optString("tag_string").split(" ").filter { it.isNotBlank() }
                    .take(5).joinToString(" ") { it.replace('_', ' ') }.ifBlank { "Anime illustration $id" }
                add(OnlineWallpaperResult("danbooru-$id", title.take(120), preview, full, ext,
                    "Danbooru contributor", "See original artist/source"))
            }
        }
    }

    private fun requestJsonArray(url: URL, referer: String): org.json.JSONArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ArkaAndroid/0.9.3 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", referer)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Danbooru returned HTTP ${connection.responseCode}")
            return org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun String.toDanbooruTagQuery(allowCosplay: Boolean): String {
        val normalized = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        if (normalized.isBlank()) return ""
        val exclusions = if (allowCosplay) "" else " -cosplay -photo -photorealistic -realistic -3d -irl"
        return "${normalized} rating:s$exclusions"
    }

    private fun searchOpenverse(
        query: String,
        terms: List<String>,
        page: Int
    ): List<OnlineWallpaperResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val endpoint = URL(
            "https://api.openverse.org/v1/images/?q=$encoded" +
                "&page=$page&page_size=20&license_type=all-cc&mature=false"
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
                if (id.isBlank()) continue

                val mimeType = item.optString("mimetype").ifBlank { item.optString("filetype") }
                val fileType = mimeType.substringAfterLast('/').substringBefore(';').lowercase()
                    .let { raw ->
                        when (raw) {
                            "jpeg", "pjpeg" -> "jpg"
                            "x-png" -> "png"
                            else -> raw
                        }
                    }.ifBlank {
                        item.optString("url").substringBefore('?').substringAfterLast('.').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
                if (fileType !in SUPPORTED_IMAGE_TYPES) continue

                val thumbnail = item.optString("thumbnail")
                val preview = normalizeOpenverseUrl(thumbnail)
                if (!isSecureImageUrl(preview)) continue

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
                // Keep provider-ranked matches; strict client-side filtering discarded useful results.
                if (!isSecureImageUrl(preview) ||
                    !isSecureImageUrl(download)
                ) continue

                val extension = download.substringBefore('?')
                    .substringAfterLast('.', "").lowercase()
                    .let { if (it == "jpeg") "jpg" else it }
                    .ifBlank {
                        metadata?.optJSONObject("MimeType")?.optString("value").orEmpty()
                            .substringAfterLast('/').lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    }
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
                "ArkaAndroid/0.9.1 (wallpaper search; https://github.com/xxSwar4jxx/kosmos)"
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

        private fun isSecureImageUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank() &&
            url.userInfo == null && (url.port == -1 || url.port == 443)
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("jpg", "png", "webp", "gif")
    }
}
