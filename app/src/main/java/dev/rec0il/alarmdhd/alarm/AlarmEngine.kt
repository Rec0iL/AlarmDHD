package dev.rec0il.alarmdhd.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.FiredRing
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.ui.MainActivity
import dev.rec0il.alarmdhd.widget.AlarmWidget
import java.time.LocalDate
import java.time.ZoneId

/** Connects the pure [Planner] to the system. Everything that changes alarms ends in [reschedule]. */
object AlarmEngine {
    private const val REQUEST_FIRE = 1
    private const val REQUEST_TEST = 2
    private const val REQUEST_SHOW = 3
    private const val FIRE_WAKE_LOCK_MS = 60_000L

    fun now(): Long = System.currentTimeMillis()

    fun zone(): ZoneId = ZoneId.systemDefault()

    /**
     * Schedules the single next ring from saved state. Safe to call at any time and from anywhere,
     * so recovering from a reboot or clock change is the same operation as any ordinary update.
     */
    fun reschedule(context: Context) {
        val state = AlarmStore.update { Planner.housekeep(it, now(), zone()) }
        val due = Planner.nextDue(state, now(), zone())
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_FIRE,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_AT, due?.at ?: 0L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (due == null) alarms.cancel(operation) else setAlarmClock(context, alarms, due.at, operation)
        Notifications.updateProgress(context, state, now(), zone())
        AlarmWidget.refresh(context)
    }

    private fun setAlarmClock(context: Context, alarms: AlarmManager, at: Long, operation: PendingIntent) {
        val show = PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } catch (e: SecurityException) {
            // Exact alarms were revoked on Android 12. Late is still better than never, and setup flags it.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    fun onAlarmFired(context: Context, at: Long) {
        // The system only keeps the phone awake until the receiver returns, which is before the service starts.
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarmdhd:fire")
            .acquire(FIRE_WAKE_LOCK_MS)

        var ring: FiredRing? = null
        AlarmStore.update { state ->
            val result = Planner.fire(state, at, now(), zone())
            ring = result.ring
            result.state
        }
        // Schedule the next ring before starting this one, so a crash while ringing can't end the run.
        reschedule(context)
        ring?.let { RingService.startRing(context, it) }
    }

    fun scheduleTestRing(context: Context, delayMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_TEST,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarmClock(context, alarms, now() + delayMs, operation)
    }

    /** While a run is going, its times can't change, because that would be a way around the game. */
    fun saveSeries(context: Context, series: AlarmSeries) {
        AlarmStore.update { state ->
            val existing = state.settings.series.find { it.id == series.id }
            val safe = if (existing != null && Planner.isRunning(state, existing, now(), zone())) {
                series.copy(
                    startMinute = existing.startMinute,
                    endMinute = existing.endMinute,
                    intervalMinutes = existing.intervalMinutes,
                    days = existing.days,
                    enabled = existing.enabled,
                )
            } else {
                series
            }
            Planner.save(state, safe, now())
        }
        reschedule(context)
    }

    fun setEnabled(context: Context, seriesId: String, enabled: Boolean) {
        AlarmStore.update { state ->
            val series = state.settings.series.find { it.id == seriesId }
            if (series == null || Planner.isRunning(state, series, now(), zone())) {
                state
            } else {
                Planner.setEnabled(state, seriesId, enabled, now())
            }
        }
        reschedule(context)
    }

    fun deleteSeries(context: Context, seriesId: String) {
        AlarmStore.update { state ->
            val series = state.settings.series.find { it.id == seriesId }
            if (series == null || Planner.isRunning(state, series, now(), zone())) {
                state
            } else {
                Planner.delete(state, seriesId)
            }
        }
        reschedule(context)
    }

    fun skip(context: Context, seriesId: String, day: LocalDate) {
        AlarmStore.update { Planner.skip(it, seriesId, day) }
        reschedule(context)
    }

    fun undoSkip(context: Context, seriesId: String) {
        AlarmStore.update { Planner.unskip(it, seriesId) }
        reschedule(context)
    }

    /** Winning the game when no ring service is around to do it. */
    fun stopRunning(context: Context) {
        AlarmStore.update { Planner.stopRunning(it, now(), zone()) }
        reschedule(context)
    }
}
