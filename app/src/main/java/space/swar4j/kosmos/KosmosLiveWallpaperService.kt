package space.swar4j.kosmos

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.max

class KosmosLiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = KosmosEngine()

    private inner class KosmosEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var player: ExoPlayer? = null
        private var gifMovie: Movie? = null
        private var gifStartedAt = 0L
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private val gifFrame = object : Runnable {
            override fun run() {
                if (!isVisible || gifMovie == null) return
                drawGifFrame()
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                startPlayback()
            } else {
                stopPlayback()
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            stopPlayback()
            if (isVisible) startPlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopPlayback()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopPlayback()
            super.onDestroy()
        }

        private fun startPlayback() {
            if (!isVisible || !surfaceHolder.surface.isValid ||
                surfaceWidth <= 0 || surfaceHeight <= 0 ||
                player != null || gifMovie != null
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
            runCatching {
                ExoPlayer.Builder(this@KosmosLiveWallpaperService).build().also { exo ->
                    player = exo
                    exo.repeatMode = Player.REPEAT_MODE_ONE
                    exo.volume = 0f
                    exo.setVideoSurface(surfaceHolder.surface)
                    exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    exo.prepare()
                    exo.playWhenReady = true
                }
            }.onFailure {
                stopPlayback()
            }
        }

        @Suppress("DEPRECATION")
        private fun startGif(file: File) {
            val decoded = Movie.decodeFile(file.absolutePath) ?: return
            if (decoded.width() <= 0 || decoded.height() <= 0) return
            gifMovie = decoded
            gifStartedAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(gifFrame)
            handler.post(gifFrame)
        }

        private fun drawGifFrame() {
            val movie = gifMovie ?: return
            val holder = surfaceHolder
            if (!holder.surface.isValid) return

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val duration = movie.duration().coerceAtLeast(1)
                    val elapsed = SystemClock.uptimeMillis() - gifStartedAt
                    movie.setTime((elapsed % duration).toInt())

                    val scale = max(
                        surfaceWidth.toFloat() / movie.width(),
                        surfaceHeight.toFloat() / movie.height()
                    )
                    val left = (surfaceWidth - movie.width() * scale) / 2f
                    val top = (surfaceHeight - movie.height() * scale) / 2f

                    canvas.save()
                    canvas.translate(left, top)
                    canvas.scale(scale, scale)
                    movie.draw(canvas, 0f, 0f)
                    canvas.restore()
                }
            } catch (_: IllegalArgumentException) {
                // The surface may be recreated while a frame is being drawn.
            } finally {
                if (canvas != null) {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        private fun stopPlayback() {
            handler.removeCallbacks(gifFrame)
            gifMovie = null
            runCatching {
                player?.clearVideoSurface()
                player?.release()
            }
            player = null
        }

        companion object {
            private const val FRAME_INTERVAL_MS = 33L
        }
    }
}
