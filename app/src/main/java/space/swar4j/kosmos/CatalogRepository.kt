package space.swar4j.kosmos

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class CatalogItem(
    val id: String,
    val title: String,
    val category: String,
    val previewUrl: String,
    val downloadUrl: String,
    val sourceUrl: String,
    val creator: String,
    val license: String
)

class CatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("catalogue_cache", Context.MODE_PRIVATE)

    suspend fun load(forceRefresh: Boolean = false): List<CatalogItem> = withContext(Dispatchers.IO) {
        if (forceRefresh || preferences.getString(KEY_JSON, null) == null) {
            runCatching { fetchCatalogue() }.onSuccess { json ->
                preferences.edit().putString(KEY_JSON, json).apply()
            }
        }
        val cached = preferences.getString(KEY_JSON, null)
        val json = cached ?: runCatching {
            appContext.assets.open("catalogue.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext emptyList()
        parse(json)
    }

    private fun fetchCatalogue(): String {
        val connection = (URL(CATALOGUE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Kosmos-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Catalogue server returned HTTP ${connection.responseCode}")
            }
            if (connection.contentLengthLong > MAX_CATALOGUE_BYTES) throw IOException("Catalogue is too large")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_CATALOGUE_BYTES) throw IOException("Catalogue is too large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String): List<CatalogItem> = runCatching {
        val entries = JSONObject(raw).optJSONArray("items") ?: JSONArray()
        val seenIds = HashSet<String>()
        buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val item = CatalogItem(
                    id = entry.optString("id"),
                    title = entry.optString("title"),
                    category = entry.optString("category", "Nature"),
                    previewUrl = entry.optString("previewUrl"),
                    downloadUrl = entry.optString("downloadUrl"),
                    sourceUrl = entry.optString("sourceUrl"),
                    creator = entry.optString("creator"),
                    license = entry.optString("license")
                )
                if (item.id.isBlank() || item.id.length > 80 || item.title.isBlank() || item.title.length > 64 ||
                    item.category.length > 32 || item.creator.isBlank() || item.creator.length > 100 ||
                    item.license.isBlank() || item.license.length > 64 || size >= MAX_ITEMS
                ) continue
                if (!seenIds.add(item.id)) continue
                if (!isAllowedUrl(item.previewUrl, PREVIEW_HOSTS) ||
                    !isAllowedUrl(item.downloadUrl, DOWNLOAD_HOSTS) ||
                    !isAllowedUrl(item.sourceUrl, SOURCE_HOSTS)
                ) continue
                add(item)
            }
        }
    }.getOrDefault(emptyList())

    private fun isAllowedUrl(value: String, hosts: Set<String>): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.host?.lowercase()?.let(hosts::contains) == true
    }

    companion object {
        private const val CATALOGUE_URL =
            "https://raw.githubusercontent.com/xxSwar4jxx/kosmos/main/catalog/catalogue.json"
        private const val KEY_JSON = "catalogue_json"
        private const val MAX_CATALOGUE_BYTES = 512 * 1024L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_ITEMS = 100
        private val PREVIEW_HOSTS = setOf("commons.wikimedia.org", "upload.wikimedia.org")
        private val DOWNLOAD_HOSTS = setOf("commons.wikimedia.org")
        private val SOURCE_HOSTS = setOf("commons.wikimedia.org")
    }
}
