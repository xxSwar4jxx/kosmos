package space.swar4j.kosmos

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class StoredWallpaper(
    val file: File,
    val title: String
)

class WallpaperStorage(private val context: Context) {
    private val wallpaperDirectory: File
        get() = File(context.filesDir, "wallpapers").apply { mkdirs() }

    suspend fun importImage(uri: Uri): StoredWallpaper = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)
        val extension = when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IOException("Choose a JPG, PNG, or WebP image")
        }

        val displayName = queryDisplayName(uri)
        val destination = File(wallpaperDirectory, "${UUID.randomUUID()}.$extension")
        val temporary = File(wallpaperDirectory, "${destination.name}.part")

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Couldn't open the selected image")

            input.use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_IMAGE_BYTES) {
                            throw IOException("Images must be smaller than 40 MB")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION ||
                pixels > MAX_PIXELS
            ) {
                throw IOException("This image is invalid or has unsupported dimensions")
            }

            if (!temporary.renameTo(destination)) {
                throw IOException("Couldn't save the image")
            }

            StoredWallpaper(
                file = destination,
                title = displayName.substringBeforeLast('.', displayName)
                    .take(48).ifBlank { "Wallpaper" }
            )
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    suspend fun listWallpapers(): List<StoredWallpaper> = withContext(Dispatchers.IO) {
        wallpaperDirectory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                StoredWallpaper(
                    file = file,
                    title = file.nameWithoutExtension.take(48).ifBlank { "Wallpaper" }
                )
            }
            ?: emptyList()
    }

    private fun queryDisplayName(uri: Uri): String {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                "Wallpaper"
            }
        } ?: "Wallpaper"
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 40L * 1024L * 1024L
        private const val MAX_DIMENSION = 12000
        private const val MAX_PIXELS = 80_000_000L
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "png", "webp")
    }
}
