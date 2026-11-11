package space.swar4j.kosmos

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MediaWidgetManageActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val prefs by lazy { getSharedPreferences("arka_media_widgets", MODE_PRIVATE) }
    private val dir by lazy { File(filesDir, "widget_media").apply { mkdirs() } }
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        root.addView(TextView(this).apply { text = "Manage photos"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Choose one mode per frame: up to 10 images, or one looping MP4 video."; textSize = 14f; setPadding(0,12,0,16) })
        summary = TextView(this).apply { textSize = 15f; setPadding(12,12,12,12) }
        root.addView(summary)
        fun button(label: String, action: () -> Unit) { root.addView(Button(this).apply { text = label; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1,-2)) }
        button("Use images (replaces video)") { pick(false) }
        button("Use one video (replaces images)") { pick(true) }
        button("Remove media") { removeMedia() }
        button("Done") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        val images = prefs.getStringSet("images_$widgetId", emptySet()).orEmpty()
        val video = prefs.getString("video_$widgetId", null)
        summary.text = "Images: ${images.size}/10\n" + images.mapIndexed { i, n -> "${i+1}. ${n.substringBeforeLast('.')}" }.joinToString("\n") +
            "\n\nVideo: ${video?.substringBeforeLast('.') ?: "Not added"}"
    }

    private fun pick(video: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (video) "video/mp4" else "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !video)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, if (video) 62 else 61)
    }

    private fun removeMedia() {
        val images = prefs.getStringSet("images_$widgetId", emptySet()).orEmpty().toMutableList()
        val video = prefs.getString("video_$widgetId", null)
        val names = images.map { it.substringBeforeLast('.') }.toMutableList()
        if (video != null) names.add("Video: ${video.substringBeforeLast('.')}")
        if (names.isEmpty()) return
        android.app.AlertDialog.Builder(this).setTitle("Remove media").setItems(names.toTypedArray()) { _, which ->
            if (which < images.size) {
                File(dir, images[which]).delete(); images.removeAt(which)
                prefs.edit().putStringSet("images_$widgetId", images.toSet()).apply()
            } else {
                video?.let { File(dir, it).delete() }; prefs.edit().remove("video_$widgetId").apply()
            }
            refresh(); MediaWidgetProvider.refresh(this)
        }.setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Media picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            val uris = if (data.clipData != null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(data.data)
            if (requestCode == 62) {
                val uri = uris.firstOrNull() ?: return
                val target = File(dir, "widget_video_$widgetId.mp4")
                contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return
                if (target.length() > 10L * 1024L * 1024L) { target.delete(); throw IllegalArgumentException("Video must be 10 MB or smaller") }
                prefs.getStringSet("images_$widgetId", emptySet()).orEmpty().forEach { File(dir, it).delete() }
                prefs.edit().remove("images_$widgetId").putString("video_$widgetId", target.name).apply()
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, MediaWidgetPlaybackService::class.java))
            } else if (requestCode == 61) {
                prefs.getString("video_$widgetId", null)?.let { File(dir, it).delete() }
                prefs.edit().remove("video_$widgetId").apply()
                val images = prefs.getStringSet("images_$widgetId", emptySet()).orEmpty().toMutableSet()
                for (uri in uris) {
                    if (images.size >= 10) break
                    val ext = when (contentResolver.getType(uri)) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
                    val target = File(dir, "widget_${widgetId}_${System.nanoTime()}.$ext")
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    images.add(target.name)
                }
                prefs.edit().putStringSet("images_$widgetId", images).apply()
            }
            MediaWidgetProvider.refresh(this); refresh()
        } catch (e: Exception) { Toast.makeText(this, e.message ?: "Couldn't add media", Toast.LENGTH_LONG).show() }
    }
}
