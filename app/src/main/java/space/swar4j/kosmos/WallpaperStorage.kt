package space.swar4j.kosmos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class StoredWallpaper(
    val file: File,
    val title: String,
    val thumbnail: File,
    val isAnimated: Boolean
)

class WallpaperStorage(private val context: Context) {
    private val wallpaperDirectory: File
        get() = File(context.filesDir, "wallpapers").apply { mkdirs() }

    private val thumbnailDirectory: File
        get() = File(context.filesDir, "thumbnails").apply { mkdirs() }

    suspend fun importMedia(uri: Uri): StoredWallpaper = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)
        val extension = when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            else -> throw IOException("Choose a JPG, PNG, WebP, GIF, or MP4 file")
        }

        val displayName = queryDisplayName(uri)
        val title = displayName.substringBeforeLast('.', displayName)
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .take(32)
            .ifBlank { "Wallpaper" }
        val safeFileName = title.replace(Regex("\\s+"), "-")
        val destination = File(wallpaperDirectory, "${safeFileName}__${UUID.randomUUID()}.$extension")
        val temporary = File(wallpaperDirectory, "${destination.name}.part")
        val maxBytes = if (extension == "mp4") MAX_VIDEO_BYTES else MAX_IMAGE_BYTES

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Couldn't open the selected file")

            input.use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > maxBytes) {
                            throw IOException("This file exceeds Kosmos' import size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }

            validateMedia(temporary, extension)
            if (!temporary.renameTo(destination)) {
                throw IOException("Couldn't save the wallpaper")
            }

            val thumbnail = createThumbnail(destination)
            StoredWallpaper(
                file = destination,
                title = title,
                thumbnail = thumbnail ?: destination,
                isAnimated = extension == "gif" || extension == "mp4"
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
                val extension = file.extension.lowercase()
                val thumbnail = File(thumbnailDirectory, "${file.nameWithoutExtension}.jpg")
                StoredWallpaper(
                    file = file,
                    title = file.nameWithoutExtension.substringBefore("__")
                        .replace("-", " ").take(48).ifBlank { "Wallpaper" },
                    thumbnail = thumbnail.takeIf { it.isFile } ?: file,
                    isAnimated = extension == "gif" || extension == "mp4"
                )
            }
            ?: emptyList()
    }

    private fun validateMedia(file: File, extension: String) {
        when (extension) {
            "jpg", "png", "webp" -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                validateDimensions(bounds.outWidth, bounds.outHeight)
            }
            "gif" -> {
                @Suppress("DEPRECATION")
                val movie = Movie.decodeFile(file.absolutePath)
                    ?: throw IOException("This GIF could not be decoded")
                validateDimensions(movie.width(), movie.height())
            }
            "mp4" -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull() ?: 0
                    val duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    validateDimensions(width, height)
                    if (duration <= 0 || duration > MAX_VIDEO_DURATION_MS) {
                        throw IOException("Videos must be between 1 ms and 2 minutes long")
                    }
                } catch (error: RuntimeException) {
                    throw IOException("This video could not be decoded", error)
                } finally {
                    retriever.release()
                }
            }
        }
    }

    private fun validateDimensions(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 ||
            width > MAX_DIMENSION || height > MAX_DIMENSION ||
            pixels > MAX_PIXELS
        ) {
            throw IOException("This media has invalid or unsupported dimensions")
        }
    }

    @Suppress("DEPRECATION")
    private fun createThumbnail(file: File): File? {
        val output = File(thumbnailDirectory, "${file.nameWithoutExtension}.jpg")
        val bitmap: Bitmap? = when (file.extension.lowercase()) {
            "mp4" -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(0)
                } catch (_: RuntimeException) {
                    null
                } finally {
                    retriever.release()
                }
            }
            "gif" -> {
                val movie = Movie.decodeFile(file.absolutePath)
                if (movie == null) null else {
                    val frame = Bitmap.createBitmap(
                        movie.width(), movie.height(), Bitmap.Config.ARGB_8888
                    )
                    movie.setTime(0)
                    movie.draw(Canvas(frame), 0f, 0f)
                    frame
                }
            }
            else -> null
        } ?: return null

        return try {
            output.outputStream().use { stream ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) output else null
            }
        } finally {
            bitmap.recycle()
        }
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
        private const val MAX_VIDEO_BYTES = 150L * 1024L * 1024L
        private const val MAX_DIMENSION = 12000
        private const val MAX_PIXELS = 80_000_000L
        private const val MAX_VIDEO_DURATION_MS = 120_000L
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "png", "webp", "gif", "mp4")
    }
}
