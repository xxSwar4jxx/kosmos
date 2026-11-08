package space.swar4j.kosmos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class PlaybackError(
    val code: String,
    val category: String,
    val message: String
) {
    NO_WALLPAPER_SELECTED("KOS-CONFIG-001", "Configuration", "No live wallpaper is selected."),
    FILE_ACCESS_FAILED("KOS-STORAGE-001", "Storage", "The selected wallpaper file could not be opened."),
    FILE_MISSING("KOS-STORAGE-002", "Storage", "The selected wallpaper file is missing or unreadable."),
    UNSUPPORTED_FILE_TYPE("KOS-FORMAT-001", "Format", "This file type cannot be used as a live wallpaper."),
    VIDEO_PLAYER_INIT_FAILED("KOS-VIDEO-001", "Playback", "The video player could not be started."),
    VIDEO_PREPARE_FAILED("KOS-VIDEO-002", "Playback", "The video could not be prepared."),
    VIDEO_PLAYBACK_FAILED("KOS-VIDEO-003", "Playback", "The video could not be played."),
    VIDEO_START_TIMEOUT("KOS-VIDEO-004", "Timeout", "The video did not start in time."),
    GIF_DECODE_FAILED("KOS-GIF-001", "Decoding", "The GIF could not be decoded."),
    GIF_UNSUPPORTED("KOS-GIF-002", "Format", "This GIF is not animated or is unsupported on this Android version."),
    GIF_DIMENSIONS_INVALID("KOS-GIF-003", "Validation", "This GIF has unsupported dimensions.")
}

internal class PlaybackFailureException(val failure: PlaybackError) : Exception(failure.message)

object PlaybackFailureNotifier {
    private const val CHANNEL_ID = "wallpaper_playback_errors"
    private const val NOTIFICATION_ID = 4102

    fun show(context: Context, failure: PlaybackError) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wallpaper errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a live wallpaper fails to load or play"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            context,
            4102,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Kosmos wallpaper failed")
            .setContentText(failure.message)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${failure.message}\nCode: ${failure.code}\nCategory: ${failure.category}"
                )
            )
            .setSubText("${failure.code} • ${failure.category}")
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
