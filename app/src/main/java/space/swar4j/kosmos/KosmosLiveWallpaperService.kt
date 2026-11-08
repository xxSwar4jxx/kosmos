package space.swar4j.kosmos

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.graphics.withTranslation
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

class KosmosLiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = KosmosEngine()

    private inner class KosmosEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val gifDecoder: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Kosmos-GIF-decoder").apply { isDaemon = true }
        }

        private var player: ExoPlayer? = null
        private var gifMovie: Movie? = null
        private var gifDrawable: Drawable? = null
        private var gifDecodeTask: Future<*>? = null
        private var gifStartedAt = 0L
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var playbackGeneration = 0
        private var fallbackVisible = true
        private val videoLoadTimeout = Runnable {
            if (player != null && fallbackVisible) {
                reportPlaybackFailure(PlaybackError.VIDEO_START_TIMEOUT)
                player?.let(::stopVideoPlayer)
            }
        }

        private val gifDrawableCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (isVisible && gifDrawable === who) drawGifFrame()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                if (isVisible && gifDrawable === who) handler.postAtTime(what, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what)
            }
        }

        private val gifFrame = object : Runnable {
            override fun run() {
                if (!isVisible || gifMovie == null) return
                drawGifFrame()
                if (isVisible && gifMovie != null) handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) startPlayback() else stopPlayback()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            stopPlayback()
            if (isVisible) startPlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopPlayback()
            surfaceWidth = 0
            surfaceHeight = 0
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopPlayback()
            gifDecoder.shutdownNow()
            super.onDestroy()
        }

        private fun startPlayback() {
            if (!isVisible || !surfaceHolder.surface.isValid ||
                surfaceWidth <= 0 || surfaceHeight <= 0 ||
                player != null || gifMovie != null || gifDrawable != null ||
                gifDecodeTask != null
            ) return

            fallbackVisible = true
            drawFallback()

            val path = getSharedPreferences(
                LiveWallpaperLauncher.PREFERENCES,
                MODE_PRIVATE
            ).getString(LiveWallpaperLauncher.ACTIVE_WALLPAPER_PATH, null)
                ?: return reportPlaybackFailure(PlaybackError.NO_WALLPAPER_SELECTED)

            val file = runCatching { File(path).canonicalFile }.getOrNull()
                ?: return reportPlaybackFailure(PlaybackError.FILE_ACCESS_FAILED)
            val wallpaperDirectory = File(filesDir, "wallpapers").canonicalFile
            if (!file.isFile || !file.canRead() || file.parentFile != wallpaperDirectory ||
                file.extension.lowercase() !in setOf("gif", "mp4")) {
                return reportPlaybackFailure(if (file.extension.lowercase() !in setOf("gif", "mp4")) PlaybackError.UNSUPPORTED_FILE_TYPE else PlaybackError.FILE_MISSING)
            }

            when (file.extension.lowercase()) {
                "gif" -> startGif(file)
                "mp4" -> startVideo(file)
            }
        }

        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        private fun startVideo(file: File) {
            val exo = runCatching {
                ExoPlayer.Builder(this@KosmosLiveWallpaperService).build()
            }.getOrElse {
                reportPlaybackFailure(PlaybackError.VIDEO_PLAYER_INIT_FAILED)
                return
            }

            player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.volume = 0f
            exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (player === exo) {
                        reportPlaybackFailure(PlaybackError.VIDEO_PLAYBACK_FAILED)
                        stopVideoPlayer(exo)
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (player === exo) {
                        fallbackVisible = false
                        handler.removeCallbacks(videoLoadTimeout)
                    }
                }
            })

            runCatching {
                exo.setVideoSurface(surfaceHolder.surface)
                exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                exo.prepare()
                exo.playWhenReady = true
            }.onFailure {
                if (player === exo) {
                    reportPlaybackFailure(PlaybackError.VIDEO_PREPARE_FAILED)
                    stopVideoPlayer(exo)
                }
            }
            handler.removeCallbacks(videoLoadTimeout)
            handler.postDelayed(videoLoadTimeout, VIDEO_LOAD_TIMEOUT_MS)
        }

        private fun startGif(file: File) {
            val generation = playbackGeneration
            gifDecodeTask = gifDecoder.submit {
                val decodeResult = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        decodeAnimatedGif(file)
                    } else {
                        @Suppress("DEPRECATION")
                        Movie.decodeFile(file.absolutePath)?.also { movie ->
                            validateGifDimensions(movie.width(), movie.height())
                        } ?: throw PlaybackFailureException(PlaybackError.GIF_DECODE_FAILED)
                    }
                }
                val decoded = decodeResult.getOrNull()

                handler.post {
                    if (generation != playbackGeneration) return@post
                    gifDecodeTask = null
                    if (!isVisible || !surfaceWidthPositive()) return@post
                    if (decoded == null) {
                        reportPlaybackFailure((decodeResult.exceptionOrNull() as? PlaybackFailureException)?.failure ?: PlaybackError.GIF_DECODE_FAILED)
                        return@post
                    }

                    when (decoded) {
                        is Movie -> {
                            gifMovie = decoded
                            gifStartedAt = SystemClock.uptimeMillis()
                            fallbackVisible = false
                            handler.removeCallbacks(gifFrame)
                            handler.post(gifFrame)
                        }
                        is Drawable -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                attachAnimatedGif(decoded)
                                fallbackVisible = false
                                drawGifFrame()
                            } else {
                                reportPlaybackFailure(PlaybackError.GIF_UNSUPPORTED)
                            }
                        }
                    }
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.P)
        @SuppressLint("InlinedApi")
        private fun decodeAnimatedGif(file: File): Drawable {
            val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                validateGifDimensions(width, height)
                val scale = minOf(1f, MAX_GIF_DECODE_DIMENSION / max(width, height).toFloat())
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
            if (decoded !is android.graphics.drawable.AnimatedImageDrawable) {
                throw PlaybackFailureException(PlaybackError.GIF_UNSUPPORTED)
            }
            return decoded
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun attachAnimatedGif(drawable: Drawable) {
            val animated = drawable as? android.graphics.drawable.AnimatedImageDrawable ?: return
            gifDrawable = animated
            animated.setBounds(0, 0, animated.intrinsicWidth, animated.intrinsicHeight)
            animated.callback = gifDrawableCallback
            animated.start()
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun stopAnimatedGif(drawable: Drawable) {
            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
        }

        private fun validateGifDimensions(width: Int, height: Int) {
            val pixels = width.toLong() * height.toLong()
            if (width <= 0 || height <= 0 ||
                width > MAX_GIF_SOURCE_DIMENSION || height > MAX_GIF_SOURCE_DIMENSION ||
                pixels > MAX_GIF_SOURCE_PIXELS
            ) {
                throw PlaybackFailureException(PlaybackError.GIF_DIMENSIONS_INVALID)
            }
        }

        private fun surfaceWidthPositive(): Boolean =
            surfaceWidth > 0 && surfaceHeight > 0 && surfaceHolder.surface.isValid

        private fun reportPlaybackFailure(failure: PlaybackError) {
            getSharedPreferences(LiveWallpaperLauncher.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR, failure.message)
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR_CODE, failure.code)
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR_CATEGORY, failure.category)
                .apply()
            PlaybackFailureNotifier.show(this@KosmosLiveWallpaperService, failure)
            fallbackVisible = true
            drawFallback()
        }

        private fun drawFallback() {
            if (!isVisible || !surfaceHolder.surface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.rgb(10, 12, 18))
                    val centerX = surfaceWidth / 2f
                    val centerY = surfaceHeight / 2f
                    val unit = minOf(surfaceWidth, surfaceHeight).toFloat()
                    val logoRadius = unit * 0.105f
                    val stroke = (unit * 0.009f).coerceAtLeast(3f)
                    val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = stroke
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    canvas.drawCircle(centerX, centerY - unit * 0.12f, logoRadius, logoPaint)
                    val mark = Path().apply {
                        moveTo(centerX - logoRadius * 0.48f, centerY - unit * 0.12f - logoRadius * 0.43f)
                        lineTo(centerX + logoRadius * 0.48f, centerY - unit * 0.12f - logoRadius * 0.43f)
                        lineTo(centerX - logoRadius * 0.48f, centerY - unit * 0.12f + logoRadius * 0.43f)
                        lineTo(centerX + logoRadius * 0.48f, centerY - unit * 0.12f + logoRadius * 0.43f)
                    }
                    canvas.drawPath(mark, logoPaint)

                    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        textSize = unit * 0.052f
                    }
                    canvas.drawText("ZeroTech", centerX, centerY + unit * 0.035f, brandPaint)

                    val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.LTGRAY
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        textSize = (unit * 0.026f).coerceIn(22f, 42f)
                    }
                    canvas.drawText(
                        "If you can see this then the wallpaper didn't load properly.",
                        centerX, centerY + unit * 0.105f, messagePaint
                    )
                    canvas.drawText(
                        "Please Try Again.",
                        centerX, centerY + unit * 0.15f, messagePaint
                    )
                }
            } catch (_: RuntimeException) {
            } finally {
                if (canvas != null) runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun drawGifFrame() {
            if (!isVisible || !surfaceHolder.surface.isValid) return
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val drawable = gifDrawable
                    val movie = gifMovie
                    if (drawable != null) {
                        val width = drawable.intrinsicWidth.coerceAtLeast(1)
                        val height = drawable.intrinsicHeight.coerceAtLeast(1)
                        val scale = max(surfaceWidth.toFloat() / width, surfaceHeight.toFloat() / height)
                        val x = (surfaceWidth - width * scale) / 2f
                        val y = (surfaceHeight - height * scale) / 2f
                        canvas.withTranslation(x, y) {
                            scale(scale, scale)
                            drawable.draw(this)
                        }
                    } else if (movie != null) {
                        val duration = movie.duration().coerceAtLeast(1)
                        movie.setTime(((SystemClock.uptimeMillis() - gifStartedAt) % duration).toInt())
                        val scale = max(
                            surfaceWidth.toFloat() / movie.width(),
                            surfaceHeight.toFloat() / movie.height()
                        )
                        val x = (surfaceWidth - movie.width() * scale) / 2f
                        val y = (surfaceHeight - movie.height() * scale) / 2f
                        canvas.withTranslation(x, y) {
                            scale(scale, scale)
                            movie.draw(this, 0f, 0f)
                        }
                    }
                }
            } catch (_: RuntimeException) {
                // A wallpaper surface may be recreated while a frame is being drawn.
            } finally {
                if (canvas != null) runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun stopVideoPlayer(exo: ExoPlayer) {
            handler.removeCallbacks(videoLoadTimeout)
            runCatching { exo.clearVideoSurface() }
            runCatching { exo.stop() }
            runCatching { exo.release() }
            if (player === exo) player = null
        }

        private fun stopPlayback() {
            playbackGeneration++
            gifDecodeTask?.cancel(true)
            gifDecodeTask = null
            handler.removeCallbacks(gifFrame)
            handler.removeCallbacks(videoLoadTimeout)

            gifDrawable?.let { drawable ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { stopAnimatedGif(drawable) }
                }
                drawable.callback = null
            }
            gifDrawable = null
            gifMovie = null

            player?.let { exo ->
                runCatching { exo.clearVideoSurface() }
                runCatching { exo.stop() }
                runCatching { exo.release() }
            }
            player = null
        }
    }

    private companion object {
        private const val FRAME_INTERVAL_MS = 40L
        private const val VIDEO_LOAD_TIMEOUT_MS = 15_000L
        private const val MAX_GIF_DECODE_DIMENSION = 2048
        private const val MAX_GIF_SOURCE_DIMENSION = 4096
        private const val MAX_GIF_SOURCE_PIXELS = 16_000_000L
    }
}
