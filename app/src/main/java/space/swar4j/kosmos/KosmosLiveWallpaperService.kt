package space.swar4j.kosmos

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.IOException
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
        private var gifDrawable: AnimatedImageDrawable? = null
        private var gifDecodeTask: Future<*>? = null
        private var gifStartedAt = 0L
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var playbackGeneration = 0

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

            val path = getSharedPreferences(
                LiveWallpaperLauncher.PREFERENCES,
                MODE_PRIVATE
            ).getString(LiveWallpaperLauncher.ACTIVE_WALLPAPER_PATH, null) ?: return

            val file = File(path)
            if (!file.isFile || !file.canRead()) return

            when (file.extension.lowercase()) {
                "gif" -> startGif(file)
                "mp4" -> startVideo(file)
            }
        }

        private fun startVideo(file: File) {
            val exo = runCatching {
                ExoPlayer.Builder(this@KosmosLiveWallpaperService).build()
            }.getOrElse { return }

            player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.volume = 0f
            exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (player === exo) stopPlayback()
                }
            })

            runCatching {
                exo.setVideoSurface(surfaceHolder.surface)
                exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                exo.prepare()
                exo.playWhenReady = true
            }.onFailure {
                if (player === exo) stopPlayback()
            }
        }

        private fun startGif(file: File) {
            val generation = playbackGeneration
            gifDecodeTask = gifDecoder.submit {
                val decoded: Any? = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        decodeAnimatedGif(file)
                    } else {
                        @Suppress("DEPRECATION")
                        Movie.decodeFile(file.absolutePath)?.also { movie ->
                            validateGifDimensions(movie.width(), movie.height())
                        } ?: throw IOException("This GIF could not be decoded")
                    }
                }.getOrNull()

                handler.post {
                    if (generation != playbackGeneration) return@post
                    gifDecodeTask = null
                    if (!isVisible || !surfaceHolder.surface.isValid || decoded == null) return@post

                    when (decoded) {
                        is AnimatedImageDrawable -> {
                            gifDrawable = decoded
                            decoded.setBounds(0, 0, decoded.intrinsicWidth, decoded.intrinsicHeight)
                            decoded.callback = gifDrawableCallback
                            decoded.start()
                        }
                        is Movie -> {
                            gifMovie = decoded
                            gifStartedAt = SystemClock.uptimeMillis()
                            handler.removeCallbacks(gifFrame)
                            handler.post(gifFrame)
                        }
                    }
                }
            }
        }

        private fun decodeAnimatedGif(file: File): AnimatedImageDrawable {
            val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                validateGifDimensions(width, height)
                val scale = minOf(1f, MAX_GIF_DECODE_DIMENSION / max(width, height).toFloat())
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return decoded as? AnimatedImageDrawable
                ?: throw IOException("The selected GIF is not animated or is unsupported")
        }

        private fun validateGifDimensions(width: Int, height: Int) {
            val pixels = width.toLong() * height.toLong()
            if (width <= 0 || height <= 0 ||
                width > MAX_GIF_SOURCE_DIMENSION || height > MAX_GIF_SOURCE_DIMENSION ||
                pixels > MAX_GIF_SOURCE_PIXELS
            ) {
                throw IOException("This GIF has unsupported dimensions")
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
                        canvas.save()
                        canvas.translate((surfaceWidth - width * scale) / 2f, (surfaceHeight - height * scale) / 2f)
                        canvas.scale(scale, scale)
                        drawable.draw(canvas)
                        canvas.restore()
                    } else if (movie != null) {
                        val duration = movie.duration().coerceAtLeast(1)
                        movie.setTime(((SystemClock.uptimeMillis() - gifStartedAt) % duration).toInt())
                        val scale = max(
                            surfaceWidth.toFloat() / movie.width(),
                            surfaceHeight.toFloat() / movie.height()
                        )
                        canvas.save()
                        canvas.translate(
                            (surfaceWidth - movie.width() * scale) / 2f,
                            (surfaceHeight - movie.height() * scale) / 2f
                        )
                        canvas.scale(scale, scale)
                        movie.draw(canvas, 0f, 0f)
                        canvas.restore()
                    }
                }
            } catch (_: RuntimeException) {
                // A wallpaper surface may be recreated while a frame is being drawn.
            } finally {
                if (canvas != null) runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun stopPlayback() {
            playbackGeneration++
            gifDecodeTask?.cancel(true)
            gifDecodeTask = null
            handler.removeCallbacks(gifFrame)

            gifDrawable?.let { drawable ->
                runCatching { drawable.stop() }
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

        companion object {
            private const val FRAME_INTERVAL_MS = 33L
            private const val MAX_GIF_DECODE_DIMENSION = 2048
            private const val MAX_GIF_SOURCE_DIMENSION = 4096
            private const val MAX_GIF_SOURCE_PIXELS = 16_000_000L
        }
    }
}
