package dev.rec0il.alarmdhd.setup

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.Notifications

/** Everything that can quietly stop an alarm from ringing. Shared by the wizard and the home banner. */
enum class SetupCheck(
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    @param:StringRes val action: Int,
) {
    NOTIFICATIONS(R.string.check_notifications, R.string.check_notifications_why, R.string.check_notifications_action),
    FULL_SCREEN(R.string.check_full_screen, R.string.check_full_screen_why, R.string.check_full_screen_action),
    OVER_APPS(R.string.check_over_apps, R.string.check_over_apps_why, R.string.check_over_apps_action),
    EXACT_ALARM(R.string.check_exact_alarm, R.string.check_exact_alarm_why, R.string.check_exact_alarm_action),
    BATTERY(R.string.check_battery, R.string.check_battery_why, R.string.check_battery_action),
    ALARM_VOLUME(R.string.check_volume, R.string.check_volume_why, R.string.check_volume_action),
}

object SetupChecks {
    /** Only the volume is a judgement call: below a quarter of the way up is easy to sleep through. */
    private const val MIN_VOLUME_DIVISOR = 4

    fun isOk(context: Context, check: SetupCheck): Boolean = when (check) {
        SetupCheck.NOTIFICATIONS -> {
            val manager = context.getSystemService(NotificationManager::class.java)
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(Notifications.CHANNEL_RING)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        SetupCheck.FULL_SCREEN -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        SetupCheck.OVER_APPS -> Settings.canDrawOverlays(context)
        SetupCheck.EXACT_ALARM -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        SetupCheck.BATTERY -> context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        SetupCheck.ALARM_VOLUME -> {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.getStreamVolume(AudioManager.STREAM_ALARM) * MIN_VOLUME_DIVISOR >= audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        }
    }

    fun failing(context: Context): List<SetupCheck> = SetupCheck.entries.filterNot { isOk(context, it) }

    /** Where Android hides the switch that unblocks a restricted setting on an app that wasn't installed from a store. */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Where to fix a check. Null means it's handled inside the app instead. */
    fun fixIntent(context: Context, check: SetupCheck): Intent? {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (check) {
            SetupCheck.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            SetupCheck.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
            SetupCheck.OVER_APPS -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            SetupCheck.EXACT_ALARM -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            SetupCheck.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            SetupCheck.ALARM_VOLUME -> null
        }
    }
}
