package space.swar4j.kosmos

import android.app.Activity
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PresetsWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_APPLY_PRESET) return
        val fileName = intent.getStringExtra(EXTRA_PRESET_FILE) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(File(context.filesDir, "wallpapers"), fileName)
                if (file.extension.lowercase() in setOf("gif", "mp4")) {
                    LiveWallpaperLauncher(context).launch(file)
                } else {
                    StaticWallpaperApplier(context).apply(file, WallpaperDestination.HOME)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Wallpaper applied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Couldn't apply this preset", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        const val ACTION_APPLY_PRESET = "space.swar4j.kosmos.APPLY_PRESET_WIDGET"
        const val EXTRA_PRESET_FILE = "preset_file"

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val directory = File(context.filesDir, "wallpapers")
            val saved = context.getSharedPreferences("wallpaper_metadata", Context.MODE_PRIVATE)
                .getStringSet("user_presets", emptySet()).orEmpty()
            val presets = directory.listFiles().orEmpty().filter {
                it.isFile && it.name in saved
            }.sortedBy { it.name }.take(8)

            val theme = context.getSharedPreferences("arka_settings", Context.MODE_PRIVATE)
                .getString("theme_mode", "default") ?: "default"
            val (surfaceColor, buttonColor, textColor) = when (theme) {
                "light" -> Triple(0xFFFFFFFF.toInt(), 0xFFF0F0F0.toInt(), 0xFF202124.toInt())
                "forest" -> Triple(0xFF07140D.toInt(), 0xFF1B3525.toInt(), 0xFFE2F3E4.toInt())
                "dark" -> Triple(0xFF000000.toInt(), 0xFF202020.toInt(), 0xFFFFE9C7.toInt())
                "system" -> Triple(0xFF15171C.toInt(), 0xFF24262D.toInt(), 0xFFFFE9C7.toInt())
                else -> Triple(0xFF15171C.toInt(), 0xFF24262D.toInt(), 0xFFFFE9C7.toInt())
            }
            val views = RemoteViews(context.packageName, R.layout.widget_presets)
            views.setInt(R.id.widget_presets_root, "setBackgroundColor", surfaceColor)
            views.setTextColor(R.id.widget_preset_title, textColor)
            views.setTextColor(R.id.widget_preset_empty, textColor)
            val buttonIds = intArrayOf(
                R.id.widget_preset_1, R.id.widget_preset_2, R.id.widget_preset_3, R.id.widget_preset_4,
                R.id.widget_preset_5, R.id.widget_preset_6, R.id.widget_preset_7, R.id.widget_preset_8
            )
            val minWidth = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val compact = minWidth < 230
            buttonIds.forEachIndexed { index, buttonId ->
                val file = presets.getOrNull(index)
                if (file == null) {
                    views.setViewVisibility(buttonId, View.GONE)
                } else {
                    val rawName = file.nameWithoutExtension
                    val cleanName = rawName
                        .replace(Regex("[ _]?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*$"), "")
                        .removePrefix("preset_").replace('_', ' ').trim()
                    val fullLabel = when (cleanName.lowercase()) {
                        "retro prism" -> "Prism"
                        "retro orbit" -> "Orbit"
                        "retro spectrum" -> "Spectrum"
                        "retro bluehour" -> "Blue Hour"
                        else -> cleanName.ifBlank { "Preset ${index + 1}" }
                    }
                    val label = if (compact || fullLabel.length > 9) "P${index + 1}" else fullLabel
                    views.setTextViewText(buttonId, label)
                    views.setTextColor(buttonId, textColor)
                    views.setInt(buttonId, "setBackgroundColor", buttonColor)
                    views.setViewVisibility(buttonId, View.VISIBLE)
                    val apply = Intent(context, PresetsWidgetProvider::class.java)
                        .setAction(ACTION_APPLY_PRESET).putExtra(EXTRA_PRESET_FILE, file.name)
                    val click = PendingIntent.getBroadcast(
                        context, id * 10 + index, apply,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(buttonId, click)
                }
            }
            views.setViewVisibility(
                R.id.widget_preset_empty,
                if (presets.isEmpty()) View.VISIBLE else View.GONE
            )
            manager.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, PresetsWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}

class MediaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("arka_media_widgets", Context.MODE_PRIVATE)
            val name = prefs.getString("media_$id", null)
            val file = name?.let { File(File(context.filesDir, "widget_media"), it) }
            val views = RemoteViews(context.packageName, R.layout.widget_media)
            if (file != null && file.isFile) {
                val bitmap = if (file.extension.equals("mp4", true)) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.getFrameAtTime(0)
                    } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
                } else BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_media_image, bitmap)
                    views.setViewVisibility(R.id.widget_media_placeholder, View.GONE)
                }
            } else {
                views.setImageViewResource(R.id.widget_media_image, R.drawable.zerotech_logo)
                views.setViewVisibility(R.id.widget_media_placeholder, View.VISIBLE)
            }
            val actions = Intent(context, MediaWidgetActionsActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val openActions = PendingIntent.getActivity(context, id, actions,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_media_root, openActions)
            manager.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, MediaWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}

class MediaWidgetActionsActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val prefs = getSharedPreferences("arka_media_widgets", MODE_PRIVATE)
        val name = prefs.getString("media_$id", null)
        val file = name?.let { File(File(filesDir, "widget_media"), it) }
        val exists = file?.isFile == true
        val options = if (exists) arrayOf("Open Gallery", "Set as wallpaper", "Settings")
            else arrayOf("Open Gallery", "Set as wallpaper", "Settings")
        android.app.AlertDialog.Builder(this)
            .setTitle("Photo frame")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (exists) {
                        startActivity(Intent(this, MediaWidgetViewerActivity::class.java)
                            .putExtra(MediaWidgetViewerActivity.EXTRA_FILE, file!!.absolutePath))
                    } else android.widget.Toast.makeText(this, "Choose a photo or video in Settings first", android.widget.Toast.LENGTH_SHORT).show()
                    1 -> if (exists) applyWidgetWallpaper(file!!) else
                        android.widget.Toast.makeText(this, "Choose a photo or video in Settings first", android.widget.Toast.LENGTH_SHORT).show()
                    2 -> startActivity(Intent(this, MediaWidgetConfigureActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun applyWidgetWallpaper(source: File) {
        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                val directory = File(filesDir, "wallpapers").apply { mkdirs() }
                val target = File(directory, "widget_wallpaper_${source.name}")
                withContext(Dispatchers.IO) { source.copyTo(target, overwrite = true) }
                if (target.extension.lowercase() in setOf("gif", "mp4")) {
                    LiveWallpaperLauncher(this@MediaWidgetActionsActivity).launch(target)
                } else {
                    StaticWallpaperApplier(this@MediaWidgetActionsActivity)
                        .apply(target, WallpaperDestination.HOME)
                }
            }.onSuccess {
                android.widget.Toast.makeText(this@MediaWidgetActionsActivity, "Wallpaper applied", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(this@MediaWidgetActionsActivity, it.message ?: "Couldn't apply wallpaper", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

class MediaWidgetConfigureActivity : Activity() {
    private lateinit var fpsPicker: Spinner
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        val prefs = getSharedPreferences("arka_media_widgets", MODE_PRIVATE)
        val theme = getSharedPreferences("arka_settings", MODE_PRIVATE).getString("theme_mode", "default") ?: "default"
        val (background, surface, foreground, accent) = when (theme) {
            "light" -> intArrayOf(0xFFF7F7F7.toInt(), 0xFFFFFFFF.toInt(), 0xFF202124.toInt(), 0xFF3867D6.toInt())
            "forest" -> intArrayOf(0xFF07140D.toInt(), 0xFF10251A.toInt(), 0xFFE2F3E4.toInt(), 0xFF9BCB9D.toInt())
            "dark" -> intArrayOf(0xFF000000.toInt(), 0xFF101010.toInt(), 0xFFFFE9C7.toInt(), 0xFFFFD08A.toInt())
            else -> intArrayOf(0xFF090A0D.toInt(), 0xFF15171C.toInt(), 0xFFFFE9C7.toInt(), 0xFFFFB547.toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(background)
        }
        val scroll = android.widget.ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 20)
        }
        content.addView(TextView(this).apply {
            text = "Photo frame settings"
            textSize = 25f
            setTextColor(foreground)
            setPadding(0, 4, 0, 8)
        })
        content.addView(TextView(this).apply {
            text = "Choose what appears in your home-screen frame and how videos animate."
            textSize = 14f
            setTextColor(foreground)
            alpha = 0.8f
            setPadding(0, 0, 0, 20)
        })
        val currentName = prefs.getString("media_$widgetId", null)
        content.addView(TextView(this).apply {
            text = if (currentName == null) "No media selected" else "Current media: ${currentName.substringBeforeLast('.')}"
            textSize = 15f
            setTextColor(foreground)
            setPadding(16, 18, 16, 18)
            setBackgroundColor(surface)
        })
        content.addView(TextView(this).apply {
            text = "VIDEO FRAME RATE"
            textSize = 12f
            setTextColor(accent)
            setPadding(0, 24, 0, 8)
        })
        fpsPicker = Spinner(this)
        val fpsOptions = listOf(8, 12, 16, 24, 30)
        fpsPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions.map { "$it FPS" })
        val savedFps = prefs.getInt("fps_$widgetId", 12)
        fpsPicker.setSelection(fpsOptions.indexOf(savedFps).takeIf { it >= 0 } ?: 1)
        content.addView(fpsPicker)
        content.addView(TextView(this).apply {
            text = "Higher frame rates may use more battery and launcher resources."
            textSize = 12f
            setTextColor(foreground)
            alpha = 0.7f
            setPadding(0, 6, 0, 20)
        })
        fun actionButton(label: String, filled: Boolean = false, action: () -> Unit) {
            content.addView(Button(this).apply {
                text = label
                setTextColor(if (filled) background else foreground)
                if (android.os.Build.VERSION.SDK_INT >= 21) backgroundTintList =
                    android.content.res.ColorStateList.valueOf(if (filled) accent else surface)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        }
        actionButton("Choose photo or video", true) { openMediaPicker() }
        actionButton("Save settings") {
            saveSettings()
        }
        actionButton("Done") {
            saveSettings()
            finish()
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun saveSettings() {
        val fps = listOf(8, 12, 16, 24, 30)[fpsPicker.selectedItemPosition]
        getSharedPreferences("arka_media_widgets", MODE_PRIVATE).edit()
            .putInt("fps_$widgetId", fps).apply()
        MediaWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
        val currentName = getSharedPreferences("arka_media_widgets", MODE_PRIVATE).getString("media_$widgetId", null)
        if (currentName?.endsWith(".mp4", true) == true) {
            androidx.core.content.ContextCompat.startForegroundService(this,
                Intent(this, MediaWidgetPlaybackService::class.java))
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
    }

    private fun openMediaPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, 41)
    }

    @Deprecated("Used for the widget media picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val mime = contentResolver.getType(uri)
        val extension = when (mime) {
            "video/mp4" -> "mp4"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val directory = File(filesDir, "widget_media").apply { mkdirs() }
        val target = File(directory, "widget_$widgetId.$extension")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (extension == "mp4" && total > 10L * 1024L * 1024L) throw IllegalArgumentException("Videos must be 10 MB or smaller")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalStateException("Could not open selected media")
            val fps = listOf(8, 12, 16, 24, 30)[fpsPicker.selectedItemPosition]
            getSharedPreferences("arka_media_widgets", MODE_PRIVATE).edit()
                .putString("media_$widgetId", target.name).putInt("fps_$widgetId", fps).apply()
            MediaWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
            if (extension == "mp4") androidx.core.content.ContextCompat.startForegroundService(this,
                Intent(this, MediaWidgetPlaybackService::class.java))
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            recreate()
        } catch (error: Exception) {
            android.widget.Toast.makeText(this, error.message ?: "Couldn't load selected media", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

class MediaWidgetViewerActivity : Activity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File) ?: run { finish(); return }
        if (!file.isFile) { finish(); return }
        if (file.extension.equals("mp4", true)) {
            val playerView = PlayerView(this)
            setContentView(playerView)
            player = ExoPlayer.Builder(this).build().also {
                playerView.player = it
                it.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                it.prepare()
                it.playWhenReady = true
            }
        } else {
            setContentView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setImageURI(Uri.fromFile(file))
                setBackgroundColor(android.graphics.Color.BLACK)
            })
        }
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object { const val EXTRA_FILE = "arka_widget_file" }
}


class MediaWidgetPlaybackService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private val retrievers = mutableMapOf<Int, MediaMetadataRetriever>()
    private val lastFrameAt = mutableMapOf<Int, Long>()
    private val startedAt = SystemClock.elapsedRealtime()
    private val tick = object : Runnable {
        override fun run() {
            val manager = AppWidgetManager.getInstance(this@MediaWidgetPlaybackService)
            val ids = manager.getAppWidgetIds(ComponentName(this@MediaWidgetPlaybackService, MediaWidgetProvider::class.java))
            val prefs = getSharedPreferences("arka_media_widgets", MODE_PRIVATE)
            if (ids.isEmpty()) { stopSelf(); return }
            val now = SystemClock.elapsedRealtime()
            var hasVideo = false
            ids.forEach { id ->
                val name = prefs.getString("media_$id", null) ?: return@forEach
                val file = File(File(filesDir, "widget_media"), name)
                if (!file.isFile || !file.extension.equals("mp4", true)) return@forEach
                hasVideo = true
                val fps = prefs.getInt("fps_$id", 12).coerceIn(8, 30)
                val interval = 1000L / fps
                if (now - (lastFrameAt[id] ?: 0L) < interval) return@forEach
                lastFrameAt[id] = now
                val retriever = retrievers.getOrPut(id) {
                    MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
                }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
                val timestampUs = (((now - startedAt).coerceAtLeast(0L) % duration.coerceAtLeast(1L)) * 1000L)
                val bitmap = runCatching { retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
                if (bitmap != null) {
                    val scale = minOf(1f, 480f / maxOf(bitmap.width, bitmap.height))
                    val output = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                    val views = RemoteViews(packageName, R.layout.widget_media)
                    views.setImageViewBitmap(R.id.widget_media_image, output)
                    views.setViewVisibility(R.id.widget_media_placeholder, View.GONE)
                    val actions = PendingIntent.getActivity(this@MediaWidgetPlaybackService, id,
                        Intent(this@MediaWidgetPlaybackService, MediaWidgetActionsActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(R.id.widget_media_root, actions)
                    manager.updateAppWidget(id, views)
                    if (output !== bitmap) output.recycle()
                    bitmap.recycle()
                }
            }
            if (!hasVideo) { stopSelf(); return }
            handler.postDelayed(this, 62L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("ArkaWidgetFrames").apply { start() }
        handler = Handler(worker.looper)
        val channelId = "arka_widget_playback"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Arka video widgets", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stop = PendingIntent.getService(this, 9001,
            Intent(this, MediaWidgetPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
            else Notification.Builder(this)
        startForeground(9001, builder.setContentTitle("Arka video widget")
            .setContentText("Animating your home-screen video widget")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handler.removeCallbacks(tick)
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val ACTION_STOP = "space.swar4j.kosmos.STOP_WIDGET_VIDEO" }
}
