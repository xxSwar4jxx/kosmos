package space.swar4j.kosmos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
        val displayName = queryDisplayName(uri)
        val extension = resolveExtension(context.contentResolver.getType(uri), displayName)
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
            if (!temporary.renameTo(destination)) throw IOException("Couldn't save the wallpaper")
            val thumbnail = createThumbnail(destination)
                ?: throw IOException("Couldn't create a preview for this wallpaper")

            StoredWallpaper(
                file = destination,
                title = title,
                thumbnail = thumbnail,
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
                val thumbnailFile = File(thumbnailDirectory, "${file.nameWithoutExtension}.jpg")
                val thumbnail = thumbnailFile.takeIf { it.isFile } ?: createThumbnail(file) ?: file
                StoredWallpaper(
                    file = file,
                    title = file.nameWithoutExtension.substringBefore("__")
                        .replace("-", " ").take(48).ifBlank { "Wallpaper" },
                    thumbnail = thumbnail,
                    isAnimated = extension == "gif" || extension == "mp4"
                )
            }
            ?: emptyList()
    }

    private fun resolveExtension(mimeType: String?, displayName: String): String {
        val mimeExtension = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            else -> null
        }
        val nameExtension = displayName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in SUPPORTED_EXTENSIONS }
        return mimeExtension ?: nameExtension
            ?: throw IOException("Choose a JPG, PNG, WebP, GIF, or MP4 file")
    }

    private fun validateMedia(file: File, extension: String) {
        when (extension) {
            "jpg", "png", "webp" -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                validateDimensions(bounds.outWidth, bounds.outHeight)
            }
            "gif" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        validateAnimatedDimensions(info.size.width, info.size.height)
                        val scale = minOf(
                            1f,
                            MAX_GIF_DECODE_DIMENSION / maxOf(info.size.width, info.size.height).toFloat()
                        )
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    }
                    if (drawable !is AnimatedImageDrawable) {
                        throw IOException("The selected GIF is not animated or is unsupported")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val movie = Movie.decodeFile(file.absolutePath)
                        ?: throw IOException("This GIF could not be decoded")
                    validateAnimatedDimensions(movie.width(), movie.height())
                }
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
                    validateVideoDimensions(width, height)
                    if (duration <= 0 || duration > MAX_VIDEO_DURATION_MS) {
                        throw IOException("Videos must be no longer than 2 minutes")
                    }
                } catch (error: RuntimeException) {
                    throw IOException("This video could not be decoded", error)
                } finally {
                    retriever.release()
                }
            }
        }
    }

    private fun validateDimensions(width: Int, height: Int) =
        validateDimensions(width, height, MAX_IMAGE_DIMENSION, MAX_IMAGE_PIXELS)

    private fun validateAnimatedDimensions(width: Int, height: Int) =
        validateDimensions(width, height, MAX_ANIMATED_DIMENSION, MAX_ANIMATED_PIXELS)

    private fun validateVideoDimensions(width: Int, height: Int) =
        validateDimensions(width, height, MAX_VIDEO_DIMENSION, MAX_VIDEO_PIXELS)

    private fun validateDimensions(width: Int, height: Int, maxDimension: Int, maxPixels: Long) {
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 ||
            width > maxDimension || height > maxDimension || pixels > maxPixels
        ) throw IOException("This media has invalid or unsupported dimensions")
    }

    @Suppress("DEPRECATION")
    private fun createThumbnail(file: File): File? {
        val output = File(thumbnailDirectory, "${file.nameWithoutExtension}.jpg")
        val bitmap: Bitmap? = when (file.extension.lowercase()) {
            "mp4" -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT
                        )
                    } else retriever.getFrameAtTime(0)
                } catch (_: RuntimeException) {
                    null
                } finally {
                    retriever.release()
                }
            }
            "gif" -> {
                val movie = Movie.decodeFile(file.absolutePath)
                if (movie == null) null else {
                    val scale = minOf(
                        1f,
                        THUMBNAIL_WIDTH.toFloat() / movie.width(),
                        THUMBNAIL_HEIGHT.toFloat() / movie.height()
                    )
                    val frame = Bitmap.createBitmap(
                        (movie.width() * scale).toInt().coerceAtLeast(1),
                        (movie.height() * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    movie.setTime(0)
                    Canvas(frame).apply {
                        scale(scale, scale)
                        movie.draw(this, 0f, 0f)
                    }
                    frame
                }
            }
            else -> createStaticThumbnail(file)
        } ?: return null

        return try {
            val temporary = File(output.parentFile, "${output.name}.part")
            val success = temporary.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            if (success && temporary.renameTo(output)) output else {
                temporary.delete()
                null
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createStaticThumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= THUMBNAIL_WIDTH &&
            bounds.outHeight / (sampleSize * 2) >= THUMBNAIL_HEIGHT
        ) sampleSize *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun queryDisplayName(uri: Uri): String =
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else "Wallpaper"
        } ?: "Wallpaper"

    companion object {
        private const val MAX_IMAGE_BYTES = 40L * 1024L * 1024L
        private const val MAX_VIDEO_BYTES = 150L * 1024L * 1024L
        private const val MAX_IMAGE_DIMENSION = 12000
        private const val MAX_IMAGE_PIXELS = 80_000_000L
        private const val MAX_ANIMATED_DIMENSION = 4096
        private const val MAX_ANIMATED_PIXELS = 16_000_000L
        private const val MAX_VIDEO_DIMENSION = 4096
        private const val MAX_VIDEO_PIXELS = 16_000_000L
        private const val MAX_VIDEO_DURATION_MS = 120_000L
        private const val MAX_GIF_DECODE_DIMENSION = 2048
        private const val THUMBNAIL_WIDTH = 512
        private const val THUMBNAIL_HEIGHT = 768
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "png", "webp", "gif", "mp4")
    }
}
