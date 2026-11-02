package dev.rec0il.alarmdhd.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.game.MiniGameIntents
import dev.rec0il.alarmdhd.model.AppState
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.ui.Format
import dev.rec0il.alarmdhd.ui.MainActivity
import dev.rec0il.alarmdhd.ui.RingActivity
import java.time.ZoneId

object Notifications {
    const val CHANNEL_RING = "ring"
    private const val CHANNEL_PROGRESS = "progress"
    private const val PROGRESS_ID = 1
    private const val REQUEST_RING_SCREEN = 10
    private const val REQUEST_SNOOZE = 11
    private const val REQUEST_DISMISS = 12
    private const val REQUEST_HOME = 13
    private const val REQUEST_GAME = 14

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The ring channel is silent because the service plays the sound itself, with its fallbacks.
        val ring = NotificationChannel(
            CHANNEL_RING,
            context.getString(R.string.channel_ring),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.channel_progress),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(ring, progress))
    }

    private fun broadcast(context: Context, requestCode: Int, action: String) = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * With [screenShowing], the ring screen is already up and a banner would only cover it, so this
     * version has no full-screen intent and uses a channel without banners. The rest stays as the
     * way back to the screen.
     */
    fun ring(context: Context, ring: ActiveRing, screenShowing: Boolean = false): Notification {
        val screen = PendingIntent.getActivity(
            context,
            REQUEST_RING_SCREEN,
            Intent(context, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = broadcast(context, REQUEST_DISMISS, AlarmReceiver.ACTION_DISMISS)
        val builder = NotificationCompat.Builder(context, if (screenShowing) CHANNEL_PROGRESS else CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(ring.label.ifBlank { context.getString(R.string.app_name) })
            .setContentText(Format.ringLine(context, ring))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen)
            // Since Android 14 people can swipe these away. Treat that as dismissing this ring.
            .setDeleteIntent(dismiss)
            .addAction(0, Format.snoozeLabel(context, ring), broadcast(context, REQUEST_SNOOZE, AlarmReceiver.ACTION_SNOOZE))
            .addAction(0, context.getString(R.string.ring_dismiss), dismiss)
        if (!screenShowing) builder.setFullScreenIntent(screen, true)
        return builder.build()
    }

    fun gamePaused(context: Context): Notification {
        val game = PendingIntent.getActivity(
            context,
            REQUEST_GAME,
            MiniGameIntents.open(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.game_notification_title))
            .setContentText(
                context.getString(
                    if (AlarmStore.state.value.settings.gamesEnabled) {
                        R.string.game_notification_text
                    } else {
                        R.string.awake_notification_text
                    },
                ),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(game)
            .build()
    }

    // Without the permission the call simply does nothing, and setup is what flags that.
    @SuppressLint("MissingPermission")
    fun updateProgress(context: Context, state: AppState, now: Long, zone: ZoneId) {
        val manager = NotificationManagerCompat.from(context)
        val progress = Planner.progress(state, now, zone)
        if (progress == null) {
            manager.cancel(PROGRESS_ID)
            return
        }
        val home = PendingIntent.getActivity(
            context,
            REQUEST_HOME,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextAt = progress.nextAt
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(
                if (nextAt != null) {
                    context.getString(R.string.progress_next, Format.time(context, nextAt))
                } else {
                    context.getString(R.string.progress_running)
                },
            )
            .setContentText(Format.progressLine(context, progress))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(home)
        if (nextAt != null) {
            builder.setWhen(nextAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        manager.notify(PROGRESS_ID, builder.build())
    }
}
