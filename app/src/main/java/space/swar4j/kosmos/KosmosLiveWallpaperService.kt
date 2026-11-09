package space.swar4j.kosmos

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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
        private var activeVideoFile: File? = null
        private var videoTranscodeInProgress = false
        private var transcodedGif: File? = null
        private var zeroTechLogo: Bitmap? = null
        private var gifMovie: Movie? = null
        private var gifDrawable: Drawable? = null
        private var gifDecodeTask: Future<*>? = null
        private var gifStartedAt = 0L
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var playbackGeneration = 0
        private var playbackPaused = false
        private var fallbackVisible = true

        init {
            // Start preparing MP4 media as soon as Android creates the wallpaper engine,
            // before the home-screen surface is ready. The first frame can then render
            // immediately when Android attaches the surface.
            handler.post { prewarmSelectedVideo() }
        }

        private val videoLoadTimeout = Runnable {
            if (player != null && fallbackVisible) {
                val source = activeVideoFile
                player?.let(::stopVideoPlayer)
                if (source != null) {
                    startFfmpegFallback(source, "Media3 did not render a frame before the startup timeout.")
                } else {
                    reportPlaybackFailure(PlaybackError.VIDEO_START_TIMEOUT)
                }
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
            if (visible) resumePlayback() else pausePlayback()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            if (fallbackVisible) drawFallback()
            if (player != null) {
                runCatching {
                    player?.setVideoSurfaceHolder(holder)
                    if (isVisible) {
                        playbackPaused = false
                        player?.playWhenReady = true
                        player?.play()
                        if (fallbackVisible) {
                            handler.removeCallbacks(videoLoadTimeout)
                            handler.postDelayed(videoLoadTimeout, VIDEO_LOAD_TIMEOUT_MS)
                        }
                    }
                }
                return
            }
            if (gifDrawable != null || gifMovie != null) {
                if (isVisible) {
                    playbackPaused = false
                    gifDrawable?.let { drawable ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            runCatching { (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start() }
                        }
                    }
                    drawGifFrame()
                    if (gifMovie != null) handler.removeCallbacks(gifFrame).also { handler.post(gifFrame) }
                }
                return
            }
            if (isVisible) startPlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Keep the decoder and its current position alive while Android replaces the
            // wallpaper surface (for example, during an app transition). Releasing ExoPlayer
            // here forces a full codec startup and causes the visible black gap on return.
            playbackPaused = true
            handler.removeCallbacks(gifFrame)
            player?.let { exo ->
                runCatching {
                    exo.playWhenReady = false
                    exo.pause()
                    exo.clearVideoSurfaceHolder(holder)
                }
            }
            gifDrawable?.let { drawable ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { stopAnimatedGif(drawable) }
                }
            }
            surfaceWidth = 0
            surfaceHeight = 0
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopPlayback()
            gifDecoder.shutdownNow()
            zeroTechLogo?.recycle()
            zeroTechLogo = null
            super.onDestroy()
        }

        private fun pausePlayback() {
            playbackPaused = true
            handler.removeCallbacks(gifFrame)
            player?.let { exo ->
                runCatching { exo.playWhenReady = false; exo.pause() }
            }
            gifDrawable?.let { drawable ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { stopAnimatedGif(drawable) }
                }
            }
        }

        private fun resumePlayback() {
            playbackPaused = false
            if (!isVisible || !surfaceHolder.surface.isValid) return
            player?.let { exo ->
                runCatching { exo.playWhenReady = true; exo.play() }
                if (fallbackVisible) {
                    handler.removeCallbacks(videoLoadTimeout)
                    handler.postDelayed(videoLoadTimeout, VIDEO_LOAD_TIMEOUT_MS)
                }
                return
            }
            gifDrawable?.let { drawable ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start() }
                }
                drawGifFrame()
                return
            }
            gifMovie?.let {
                drawGifFrame()
                handler.removeCallbacks(gifFrame)
                handler.post(gifFrame)
                return
            }
            if (videoTranscodeInProgress || gifDecodeTask != null) return
            val converted = transcodedGif?.takeIf { it.isFile && it.length() > 0L }
            if (converted != null) {
                startGif(converted)
            } else {
                startPlayback()
            }
        }

        private fun prewarmSelectedVideo() {
            if (player != null || !isVisible && videoTranscodeInProgress) return
            val path = getSharedPreferences(
                LiveWallpaperLauncher.PREFERENCES,
                MODE_PRIVATE
            ).getString(LiveWallpaperLauncher.ACTIVE_WALLPAPER_PATH, null) ?: return
            val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
            val wallpaperDirectory = File(filesDir, "wallpapers").canonicalFile
            if (file.parentFile == wallpaperDirectory && file.isFile && file.canRead() &&
                file.extension.equals("mp4", ignoreCase = true)
            ) {
                startVideo(file)
            }
        }

        private fun startPlayback() {
            if (!isVisible || playbackPaused || !surfaceHolder.surface.isValid ||
                surfaceWidth <= 0 || surfaceHeight <= 0 ||
                player != null || gifMovie != null || gifDrawable != null ||
                gifDecodeTask != null || videoTranscodeInProgress
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
            activeVideoFile = file
            val exo = runCatching {
                val renderersFactory = DefaultRenderersFactory(this@KosmosLiveWallpaperService)
                    .setEnableDecoderFallback(true)
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(250, 1_500, 0, 0)
                    .build()
                ExoPlayer.Builder(this@KosmosLiveWallpaperService, renderersFactory)
                    .setLoadControl(loadControl)
                    .build()
            }.getOrElse {
                startFfmpegFallback(file, "Media3 player initialization failed: ${it.javaClass.simpleName}")
                return
            }

            player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.volume = 0f
            exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (player === exo) {
                        val failure = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                                PlaybackError.VIDEO_FORMAT_UNSUPPORTED
                            else -> PlaybackError.VIDEO_PLAYBACK_FAILED
                        }
                        val diagnostic = buildString {
                            append(error.errorCodeName)
                            append(" (")
                            append(error.errorCode)
                            append(")")
                            error.cause?.let { cause ->
                                append(" • ")
                                append(cause.javaClass.simpleName)
                                cause.message?.take(120)?.let { append(": "); append(it) }
                            }
                        }
                        stopVideoPlayer(exo)
                        startFfmpegFallback(file, diagnostic)
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
                if (surfaceHolder.surface.isValid) exo.setVideoSurfaceHolder(surfaceHolder)
                exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                exo.prepare()
                exo.playWhenReady = isVisible && surfaceHolder.surface.isValid
            }.onFailure {
                if (player === exo) {
                    stopVideoPlayer(exo)
                    startFfmpegFallback(file, "Media3 preparation failed: ${it.javaClass.simpleName}")
                }
            }
            if (surfaceHolder.surface.isValid) {
                handler.removeCallbacks(videoLoadTimeout)
                handler.postDelayed(videoLoadTimeout, VIDEO_LOAD_TIMEOUT_MS)
            }
        }


        /**
         * Device decoders can reject otherwise valid H.264 streams. In that case,
         * decode the video with FFmpeg and hand the resulting animated GIF to the
         * existing platform GIF renderer. The temporary file lives only in cache.
         */
        private fun startFfmpegFallback(file: File, media3Diagnostic: String) {
            if (videoTranscodeInProgress || !isVisible) return
            handler.removeCallbacks(videoLoadTimeout)
            videoTranscodeInProgress = true
            val generation = playbackGeneration
            val output = File(cacheDir, "kosmos-video-fallback-${System.currentTimeMillis()}.gif")
            transcodedGif?.delete()
            transcodedGif = output

            val scaleWidth = surfaceWidth.coerceIn(360, 720)
            val inputPath = file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
            val outputPath = output.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
            val command = "-y -i \"$inputPath\" -t 30 -an " +
                "-vf \"fps=10,scale='min($scaleWidth,iw)':-2:flags=lanczos\" " +
                "-loop 0 \"$outputPath\""

            runCatching {
                FFmpegKit.executeAsync(command) { session ->
                    handler.post {
                        videoTranscodeInProgress = false
                        if (generation != playbackGeneration) {
                            output.delete()
                            return@post
                        }

                        if (ReturnCode.isSuccess(session.returnCode) && output.isFile && output.length() > 0L) {
                            transcodedGif = output
                            if (isVisible && !playbackPaused) startGif(output)
                        } else {
                            val code = session.returnCode?.value?.toString() ?: "unknown"
                            reportPlaybackFailure(
                                PlaybackError.VIDEO_TRANSCODE_FAILED,
                                "FFmpeg fallback failed (exit $code). Media3: $media3Diagnostic"
                            )
                            output.delete()
                        }
                    }
                }
            }.onFailure { failure ->
                videoTranscodeInProgress = false
                output.delete()
                reportPlaybackFailure(
                    PlaybackError.VIDEO_TRANSCODE_FAILED,
                    "FFmpeg could not start: ${failure.javaClass.simpleName}. Media3: $media3Diagnostic"
                )
            }
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

        private fun reportPlaybackFailure(failure: PlaybackError, detail: String? = null) {
            getSharedPreferences(LiveWallpaperLauncher.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR, failure.message)
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR_CODE, failure.code)
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR_CATEGORY, failure.category)
                .putString(LiveWallpaperLauncher.PLAYBACK_ERROR_DETAIL, detail.orEmpty())
                .apply()
            PlaybackFailureNotifier.show(this@KosmosLiveWallpaperService, failure, detail)
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
                    val logo = zeroTechLogo ?: runCatching {
                        val encoded = assets.open("zerotech_logo.base64").bufferedReader().use { it.readText() }
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()?.also { zeroTechLogo = it }

                    if (logo != null && !logo.isRecycled) {
                        val targetWidth = unit * 0.43f
                        val targetHeight = targetWidth * logo.height / logo.width.toFloat()
                        val destination = RectF(
                            centerX - targetWidth / 2f,
                            centerY - unit * 0.62f,
                            centerX + targetWidth / 2f,
                            centerY - unit * 0.45f + targetHeight
                        )
                        canvas.drawBitmap(logo, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    }

                    val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.LTGRAY
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        textSize = (unit * 0.026f).coerceIn(22f, 42f)
                    }
                    canvas.drawText(
                        "If you can see this then the wallpaper didn't load properly.",
                        centerX, centerY + unit * 0.18f, messagePaint
                    )
                    canvas.drawText(
                        "Please Try Again.",
                        centerX, centerY + unit * 0.23f, messagePaint
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
            runCatching { exo.clearVideoSurfaceHolder(surfaceHolder) }
            runCatching { exo.stop() }
            runCatching { exo.release() }
            if (player === exo) player = null
        }

        private fun stopPlayback() {
            playbackPaused = false
            playbackGeneration++
            if (videoTranscodeInProgress) {
                runCatching { FFmpegKit.cancel() }
                videoTranscodeInProgress = false
            }
            transcodedGif?.let { runCatching { it.delete() } }
            transcodedGif = null
            activeVideoFile = null
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
                runCatching { exo.clearVideoSurfaceHolder(surfaceHolder) }
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
