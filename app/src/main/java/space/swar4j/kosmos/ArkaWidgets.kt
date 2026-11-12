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
import android.graphics.Color
import android.content.res.Resources
import android.widget.RemoteViewsService
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

            val views = RemoteViews(context.packageName, R.layout.widget_presets)
            val theme = context.getSharedPreferences("arka_settings", Context.MODE_PRIVATE)
                .getString("theme_mode", "default") ?: "default"
            val (surfaceColor, buttonColor, textColor) = when (theme) {
                "light" -> Triple(0xFFFFFFFF.toInt(), 0xFFF0F0F0.toInt(), 0xFF202124.toInt())
                "forest" -> Triple(0xFF07140D.toInt(), 0xFF1B3525.toInt(), 0xFFE2F3E4.toInt())
                "dark" -> Triple(0xFF000000.toInt(), 0xFF202020.toInt(), 0xFFFFE9C7.toInt())
                "system" -> Triple(0xFF15171C.toInt(), 0xFF24262D.toInt(), 0xFFFFE9C7.toInt())
                else -> Triple(0xFF15171C.toInt(), 0xFF24262D.toInt(), 0xFFFFE9C7.toInt())
            }
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ADVANCE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id != -1) {
                val prefs = context.getSharedPreferences("arka_media_widgets", Context.MODE_PRIVATE)
                val count = mediaFiles(context, id).size
                if (count > 1) {
                    val views = RemoteViews(context.packageName, R.layout.widget_media)
                    views.showNext(R.id.widget_media_stack)
                    AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(id, views)
                }
                scheduleAdvance(context, id)
            }
        }
    }

    companion object {
        const val ACTION_ADVANCE = "space.swar4j.kosmos.MEDIA_WIDGET_ADVANCE"
        fun mediaFiles(context: Context, id: Int): List<File> {
            val prefs = context.getSharedPreferences("arka_media_widgets", Context.MODE_PRIVATE)
            val dir = File(context.filesDir, "widget_media")
            val images = prefs.getStringSet("images_$id", emptySet()).orEmpty()
                .sorted().map { File(dir, it) }.filter { it.isFile }
            val videoName = prefs.getString("video_$id", null)
            val video = videoName?.let { File(dir, it) }?.takeIf { it.isFile }
            return images + listOfNotNull(video)
        }

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("arka_media_widgets", Context.MODE_PRIVATE)
            val files = mediaFiles(context, id)
            val views = RemoteViews(context.packageName, R.layout.widget_media)
            val placeholderClick = PendingIntent.getActivity(
                context, id + 8500,
                Intent(context, MediaWidgetManageActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_media_placeholder, placeholderClick)
            if (files.isEmpty()) {
                views.setOnClickPendingIntent(R.id.widget_media_root, placeholderClick)
                views.setViewVisibility(R.id.widget_media_placeholder, View.VISIBLE)
                views.setViewVisibility(R.id.widget_media_stack, View.GONE)
                val theme = context.getSharedPreferences("arka_settings", Context.MODE_PRIVATE)
                    .getString("theme_mode", "default") ?: "default"
                val (bg, fg) = when (theme) {
                    "light" -> 0xFFF7F7F7.toInt() to 0xFF202124.toInt()
                    "forest" -> 0xFF07140D.toInt() to 0xFFE2F3E4.toInt()
                    "dark" -> 0xFF000000.toInt() to 0xFFFFE9C7.toInt()
                    else -> 0xFF090A0D.toInt() to 0xFFFFE9C7.toInt()
                }
                views.setInt(R.id.widget_media_placeholder, "setBackgroundColor", bg)
                views.setTextColor(R.id.widget_media_placeholder_text, fg)
            } else {
                views.setViewVisibility(R.id.widget_media_placeholder, View.GONE)
                views.setViewVisibility(R.id.widget_media_stack, View.VISIBLE)
                // Fallback click on the widget surface must open the Arka photo-frame
                // actions, never the app's launcher activity.
                val rootAction = Intent(context, MediaWidgetActionsActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                val rootClick = PendingIntent.getActivity(
                    context, id + 9500, rootAction,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_media_root, rootClick)
                val service = Intent(context, MediaWidgetRemoteViewsService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("content://space.swar4j.kosmos.widget/$id"))
                views.setRemoteAdapter(R.id.widget_media_stack, service)
                views.setEmptyView(R.id.widget_media_stack, R.id.widget_media_placeholder)
                val click = Intent(context, MediaWidgetActionsActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                val template = PendingIntent.getActivity(context, id + 5000, click,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                views.setPendingIntentTemplate(R.id.widget_media_stack, template)
                val index = prefs.getInt("index_$id", 0).coerceIn(0, files.lastIndex)
                views.setDisplayedChild(R.id.widget_media_stack, index)
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_media_stack)
            }
            manager.updateAppWidget(id, views)
            scheduleAdvance(context, id)
        }

        fun scheduleAdvance(context: Context, id: Int) {
            val prefs = context.getSharedPreferences("arka_media_widgets", Context.MODE_PRIVATE)
            val interval = prefs.getLong("interval_$id", 30_000L).coerceAtLeast(10_000L)
            val intent = Intent(context, MediaWidgetProvider::class.java)
                .setAction(ACTION_ADVANCE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val pending = PendingIntent.getBroadcast(context, id + 7000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarm.cancel(pending)
            if (mediaFiles(context, id).size > 1) {
                alarm.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + interval, pending)
            }
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, MediaWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}

class MediaWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MediaWidgetFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
}

class MediaWidgetFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var files: List<File> = emptyList()
    private var videoRetriever: MediaMetadataRetriever? = null
    override fun onCreate() { refresh() }
    override fun onDataSetChanged() { refresh() }
    private fun refresh() { files = MediaWidgetProvider.mediaFiles(context, widgetId) }
    override fun onDestroy() { runCatching { videoRetriever?.release() }; videoRetriever = null }
    override fun getCount() = files.size
    override fun getViewAt(position: Int): RemoteViews? {
        val file = files.getOrNull(position) ?: return null
        val prefs = context.getSharedPreferences("arka_media_widgets", Context.MODE_PRIVATE)
        val bitmap = if (file.extension.equals("mp4", true)) {
            try {
                val retriever = videoRetriever ?: MediaMetadataRetriever().also {
                    it.setDataSource(file.absolutePath)
                    videoRetriever = it
                }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frameMs = prefs.getLong("video_frame_$widgetId", 0L).coerceAtMost(duration)
                retriever.getFrameAtTime(frameMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: Exception) { null }
        } else BitmapFactory.decodeFile(file.absolutePath)
        val views = RemoteViews(context.packageName, R.layout.widget_media_item)
        if (bitmap != null) {
            // RemoteViews serializes the bitmap after getViewAt returns. Recycling it here
            // invalidates the image before the launcher can render the collection item.
            views.setImageViewBitmap(R.id.widget_media_item_image, bitmap)
        }
        views.setOnClickFillInIntent(R.id.widget_media_item_image,
            Intent().putExtra(MediaWidgetActionsActivity.EXTRA_SELECTED_FILE, file.name))
        return views
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = files.getOrNull(position)?.name?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds() = true
}

class MediaWidgetActionsActivity : Activity() {
    companion object { const val EXTRA_SELECTED_FILE = "selected_widget_media_file" }
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val prefs = getSharedPreferences("arka_media_widgets", MODE_PRIVATE)
        val name = intent.getStringExtra(EXTRA_SELECTED_FILE)
            ?: MediaWidgetProvider.mediaFiles(this, id).getOrNull(prefs.getInt("index_$id", 0))?.name
            ?: prefs.getString("media_$id", null)
        val file = name?.let { File(File(filesDir, "widget_media"), it) }
        val exists = file?.isFile == true
        val options = arrayOf("Manage gallery", "Set as wallpaper", "Settings", "Remove")
        android.app.AlertDialog.Builder(this)
            .setTitle("Photo frame")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MediaWidgetManageActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    1 -> if (exists) applyWidgetWallpaper(file!!) else
                        android.widget.Toast.makeText(this, "Choose a photo or video first", android.widget.Toast.LENGTH_SHORT).show()
                    2 -> startActivity(Intent(this, MediaWidgetConfigureActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    3 -> if (exists) {
                        file!!.delete()
                        val images = prefs.getStringSet("images_$id", emptySet()).orEmpty().toMutableSet()
                        images.remove(file.name)
                        val edit = prefs.edit().putStringSet("images_$id", images)
                        if (prefs.getString("video_$id", null) == file.name) edit.remove("video_$id")
                        edit.apply()
                        MediaWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
                        android.widget.Toast.makeText(this, "Media removed from photo frame", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
    private lateinit var intervalPicker: Spinner
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var backgroundColor = Color.BLACK
    private var surfaceColor = Color.DKGRAY
    private var foregroundColor = Color.WHITE
    private var secondaryColor = 0xFFB0B0B0.toInt()
    private var accentColor = 0xFFFFB547.toInt()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        val prefs = getSharedPreferences("arka_media_widgets", MODE_PRIVATE)
        val theme = getSharedPreferences("arka_settings", MODE_PRIVATE)
            .getString("theme_mode", "default") ?: "default"
        val colors = when (theme) {
            "light" -> intArrayOf(0xFFF5F5F5.toInt(), 0xFFFFFFFF.toInt(), 0xFF202124.toInt(), 0xFF686868.toInt(), 0xFF3867D6.toInt())
            "forest" -> intArrayOf(0xFF07140D.toInt(), 0xFF10251A.toInt(), 0xFFE2F3E4.toInt(), 0xFFAAC9AE.toInt(), 0xFF9BCB9D.toInt())
            "dark" -> intArrayOf(Color.BLACK, 0xFF151515.toInt(), 0xFFFFE9C7.toInt(), 0xFFC8BBA8.toInt(), 0xFFFFD08A.toInt())
            else -> intArrayOf(0xFF090A0D.toInt(), 0xFF15171C.toInt(), 0xFFFFE9C7.toInt(), 0xFFC9B99F.toInt(), 0xFFFFB547.toInt())
        }
        backgroundColor = colors[0]
        surfaceColor = colors[1]
        foregroundColor = colors[2]
        secondaryColor = colors[3]
        accentColor = colors[4]

        fun text(value: String, size: Float, color: Int = foregroundColor, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = true
            }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(20).toFloat()
            }
        }

        fun sectionLabel(value: String): TextView = text(value.uppercase(), 12f, accentColor, true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(10))
        }

        fun styledSpinner(items: List<String>, selected: Int): Spinner {
            val spinner = Spinner(this)
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    return (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(foregroundColor)
                        textSize = 16f
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                }
                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(foregroundColor)
                        setBackgroundColor(surfaceColor)
                        textSize = 16f
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setSelection(selected.coerceIn(items.indices))
            spinner.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            return spinner
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val scroll = android.widget.ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
        }

        content.addView(text("Photo frame", 28f, foregroundColor, true))
        content.addView(text("Personalise how your home-screen frame displays media.", 14f, secondaryColor).apply {
            setPadding(0, dp(6), 0, dp(22))
        })

        val images = prefs.getStringSet("images_$widgetId", emptySet()).orEmpty()
        val video = prefs.getString("video_$widgetId", null)
        val statusCard = card()
        statusCard.addView(sectionLabel("Current media"))
        statusCard.addView(text(
            when {
                video != null -> "Video frame"
                images.isNotEmpty() -> "Photo collection"
                else -> "Nothing added yet"
            }, 18f, foregroundColor, true
        ))
        statusCard.addView(text(
            when {
                video != null -> "1 looping video is selected"
                images.isNotEmpty() -> "${images.size} of 10 photos added"
                else -> "Add photos or a video from Manage media."
            }, 14f, secondaryColor
        ).apply { setPadding(0, dp(5), 0, 0) })
        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val videoCard = card()
        videoCard.addView(sectionLabel("Video playback"))
        videoCard.addView(text("Frame rate", 16f, foregroundColor, true))
        videoCard.addView(text("Higher FPS makes motion smoother but may use more battery.", 13f, secondaryColor)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val fpsOptions = listOf(8, 12, 16, 24, 30)
        fpsPicker = styledSpinner(fpsOptions.map { "$it FPS" },
            fpsOptions.indexOf(prefs.getInt("fps_$widgetId", 12)).let { if (it < 0) 1 else it })
        videoCard.addView(fpsPicker, LinearLayout.LayoutParams(-1, -2))
        content.addView(videoCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val rotationCard = card()
        rotationCard.addView(sectionLabel("Photo rotation"))
        rotationCard.addView(text("Auto-cycle interval", 16f, foregroundColor, true))
        rotationCard.addView(text("Choose how long each photo stays visible. You can also swipe the widget manually.", 13f, secondaryColor)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val intervalLabels = listOf("10 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes", "1 day")
        val intervalValues = listOf(10000L, 30000L, 60000L, 120000L, 300000L, 600000L, 1800000L, 86400000L)
        intervalPicker = styledSpinner(intervalLabels,
            intervalValues.indexOf(prefs.getLong("interval_$widgetId", 30000L)).coerceAtLeast(0))
        rotationCard.addView(intervalPicker, LinearLayout.LayoutParams(-1, -2))
        content.addView(rotationCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        fun actionButton(label: String, filled: Boolean, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                setTextColor(if (filled) backgroundColor else foregroundColor)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (filled) accentColor else surfaceColor)
                setOnClickListener { action() }
            }
            content.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        }
        actionButton("Manage media", false) {
            savePreferencesOnly()
            startActivity(Intent(this, MediaWidgetManageActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        actionButton("Save settings", true) { saveSettings(); finish() }
        content.addView(text("Arka · ZeroTech", 12f, secondaryColor).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun savePreferencesOnly() {
        val fps = listOf(8, 12, 16, 24, 30)[fpsPicker.selectedItemPosition]
        val intervals = listOf(10000L, 30000L, 60000L, 120000L, 300000L, 600000L, 1800000L, 86400000L)
        getSharedPreferences("arka_media_widgets", MODE_PRIVATE).edit()
            .putInt("fps_$widgetId", fps)
            .putLong("interval_$widgetId", intervals[intervalPicker.selectedItemPosition])
            .apply()
    }

    private fun saveSettings() {
        savePreferencesOnly()
        MediaWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
        val currentName = getSharedPreferences("arka_media_widgets", MODE_PRIVATE)
            .getString("video_$widgetId", null)
        if (currentName?.endsWith(".mp4", true) == true) {
            androidx.core.content.ContextCompat.startForegroundService(this,
                Intent(this, MediaWidgetPlaybackService::class.java))
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
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
                val name = prefs.getString("video_$id", null) ?: return@forEach
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
                val timestampMs = (now - startedAt).coerceAtLeast(0L) % duration.coerceAtLeast(1L)
                prefs.edit().putLong("video_frame_$id", timestampMs).apply()
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_media_stack)

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
