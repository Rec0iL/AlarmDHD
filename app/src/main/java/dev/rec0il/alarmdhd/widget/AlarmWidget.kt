package dev.rec0il.alarmdhd.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.ui.Format
import dev.rec0il.alarmdhd.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

/**
 * Shows the next alarm, and during a run a way into the game. It only ever reads state: winning the
 * game is the only way to end a day's alarms early, so nothing here can stop one.
 */
class AlarmWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        private const val REQUEST_APP = 20
        private const val REQUEST_GAME = 21
        private const val REQUEST_NEW_DAY = 22

        /**
         * Called by every [AlarmEngine.reschedule], which also runs before the first unlock after a
         * reboot. Android refuses widget calls until then, and a widget must never get in the way of
         * an alarm, so this can't throw. The reschedule at BOOT_COMPLETED catches up afterwards.
         */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, AlarmWidget::class.java))
                if (ids.isEmpty()) return
                manager.updateAppWidget(ids, render(context))
                refreshAtNewDay(context, ids)
            } catch (e: RuntimeException) {
                Log.w("AlarmWidget", "Couldn't refresh the widget", e)
            }
        }

        private fun render(context: Context): RemoteViews {
            val now = AlarmEngine.now()
            val zone = AlarmEngine.zone()
            val content = WidgetContent.of(AlarmStore.state.value, now, zone)
            val detail = detailLine(context, content, now, zone)
            return RemoteViews(context.packageName, R.layout.widget_alarm).apply {
                setOnClickPendingIntent(android.R.id.background, openApp(context))
                setInt(
                    android.R.id.background,
                    "setBackgroundResource",
                    if (content.canStop) R.drawable.widget_background_running else R.drawable.widget_background,
                )
                setTextViewText(
                    R.id.widget_title,
                    context.getString(if (content.canStop) R.string.home_running else R.string.home_next_alarm),
                )
                setTextViewText(
                    R.id.widget_time,
                    content.nextAt?.let { Format.time(context, it) } ?: context.getString(R.string.widget_no_alarm),
                )
                setTextViewText(R.id.widget_detail, detail)
                setViewVisibility(R.id.widget_detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_stop, if (content.canStop) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(R.id.widget_stop, openGame(context))
            }
        }

        /** "Tomorrow", or "Today · 4 more · 2 snoozed" during a run. No countdown, which would go stale. */
        private fun detailLine(context: Context, content: WidgetContent, now: Long, zone: ZoneId): String {
            val day = content.nextAt?.let {
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                Format.dayName(context, Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), today)
            }
            val progress = content.progress?.let { Format.progressLine(context, it) }
            return listOfNotNull(day, progress).filter { it.isNotEmpty() }.joinToString(" · ")
        }

        /** Brings the app back as it was, without stacking a new screen, however it was opened before. */
        fun appIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context,
            REQUEST_APP,
            appIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openGame(context: Context) = PendingIntent.getActivity(
            context,
            REQUEST_GAME,
            Intent(context, WidgetGameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * Nothing else happens at midnight, and "Tomorrow" would be wrong from then on. It's exact
         * because Android delays an inexact one by up to an hour, but it doesn't wake the phone, since
         * nobody reads a widget on a sleeping one: Android delivers it when the screen next comes on.
         * It's only ever armed while a widget exists.
         */
        private fun refreshAtNewDay(context: Context, ids: IntArray) {
            val operation = PendingIntent.getBroadcast(
                context,
                REQUEST_NEW_DAY,
                Intent(context, AlarmWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java)
                .setExact(AlarmManager.RTC, WidgetContent.nextDayStart(AlarmEngine.now(), AlarmEngine.zone()), operation)
        }
    }
}
