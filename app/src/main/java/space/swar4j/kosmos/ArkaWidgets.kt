package space.swar4j.kosmos

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class PresetsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val directory = File(context.filesDir, "wallpapers")
            val saved = context.getSharedPreferences("wallpaper_metadata", Context.MODE_PRIVATE)
                .getStringSet("user_presets", emptySet()).orEmpty()
            val presets = directory.listFiles().orEmpty().filter {
                it.isFile && (it.name.startsWith("preset_") || it.name in saved)
            }.sortedBy { it.name }
            val labels = presets.take(8).joinToString("\n") { file ->
                when (file.nameWithoutExtension) {
                    "preset_retro_prism" -> "Prism"
                    "preset_retro_orbit" -> "Orbit"
                    "preset_retro_spectrum" -> "Spectrum"
                    "preset_retro_bluehour" -> "Blue Hour"
                    else -> file.nameWithoutExtension.removePrefix("preset_").replace('_', ' ')
                        .replace(Regex("\\b[a-z]")) { it.value.uppercase() }
                }
            }.ifBlank { "No presets saved yet" }
            val views = RemoteViews(context.packageName, R.layout.widget_presets)
            views.setTextViewText(R.id.widget_preset_names, labels)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_presets_root, open)
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
                val viewIntent = Intent(context, MediaWidgetViewerActivity::class.java)
                    .putExtra(MediaWidgetViewerActivity.EXTRA_FILE, file.absolutePath)
                val open = PendingIntent.getActivity(context, id, viewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_media_root, open)
            } else {
                views.setImageViewResource(R.id.widget_media_image, R.drawable.zerotech_logo)
                views.setViewVisibility(R.id.widget_media_placeholder, View.VISIBLE)
                val configure = Intent(context, MediaWidgetConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                val choose = PendingIntent.getActivity(context, id, configure,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_media_root, choose)
            }
            manager.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, MediaWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}

class MediaWidgetConfigureActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }
        layout.addView(TextView(this).apply {
            text = "Choose a photo or MP4 video for your Arka widget."
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Choose media"
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(picker, 41)
            }
        })
        setContentView(layout)
    }

    @Deprecated("Used for the widget media picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        val extension = when (contentResolver.getType(uri)) {
            "video/mp4" -> "mp4"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val directory = File(filesDir, "widget_media").apply { mkdirs() }
        val target = File(directory, "widget_$widgetId.$extension")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open selected media")
            getSharedPreferences("arka_media_widgets", MODE_PRIVATE).edit()
                .putString("media_$widgetId", target.name).apply()
            MediaWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
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
