package dev.rec0il.alarmdhd.ui

import android.content.Context
import android.text.format.DateFormat
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.ActiveRing
import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.model.Progress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {
    private const val MINUTE_MS = 60_000L

    /** Follows the phone's 12 or 24 hour setting. */
    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    fun minuteOfDay(context: Context, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
        }
        return DateFormat.getTimeFormat(context).format(calendar.time)
    }

    fun until(context: Context, now: Long, at: Long): String {
        val minutes = ((at - now + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(0)
        return when {
            minutes >= 48 * 60 -> context.getString(R.string.until_days, minutes / (24 * 60))
            minutes < 1 -> context.getString(R.string.until_now)
            minutes < 60 -> context.getString(R.string.until_minutes, minutes)
            minutes % 60 == 0L -> context.getString(R.string.until_hours, minutes / 60)
            else -> context.getString(R.string.until_hours_minutes, minutes / 60, minutes % 60)
        }
    }

    fun dayName(context: Context, day: LocalDate, today: LocalDate): String = when (day) {
        today -> context.getString(R.string.day_today)
        today.plusDays(1) -> context.getString(R.string.day_tomorrow)
        else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }

    fun shortDay(isoDay: Int): String =
        DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)

    /** ISO weekday numbers in the order this locale's week runs. */
    fun weekOrder(): List<Int> {
        val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        return List(7) { (first - 1 + it) % 7 + 1 }
    }

    fun seriesRange(context: Context, series: AlarmSeries): String {
        val start = minuteOfDay(context, series.startMinute)
        val end = series.endMinute ?: return context.getString(R.string.series_range_open, start)
        return context.getString(R.string.series_range, start, minuteOfDay(context, end))
    }

    fun seriesDetail(context: Context, series: AlarmSeries): String {
        val every = context.getString(R.string.series_every, series.intervalMinutes)
        val snooze = context.getString(R.string.series_snooze, series.snoozeMinutes)
        val count = Planner.previewMinutes(series, limit = 1).count
        return if (count == null) {
            "$every · $snooze"
        } else {
            val alarms = context.resources.getQuantityString(R.plurals.series_alarms, count, count)
            "$every · $snooze · $alarms"
        }
    }

    fun seriesDays(context: Context, series: AlarmSeries): String =
        if (series.isOnce) {
            context.getString(R.string.series_once)
        } else {
            weekOrder().filter { it in series.days }.joinToString(" ") { shortDay(it) }
        }

    fun ringLine(context: Context, ring: ActiveRing): String {
        val position = ring.position
        val total = ring.total
        return when {
            ring.isTest -> context.getString(R.string.ring_test)
            ring.isSnooze -> context.getString(R.string.ring_snoozed)
            position != null && total != null -> context.getString(R.string.ring_position_of, position, total)
            position != null -> context.getString(R.string.ring_position, position)
            else -> context.getString(R.string.app_name)
        }
    }

    fun snoozeLabel(context: Context, ring: ActiveRing): String {
        val minutes = ring.snoozeMinutes ?: return context.getString(R.string.ring_snooze)
        return context.getString(R.string.ring_snooze_minutes, minutes)
    }

    /** "4 more · 2 snoozed", "No end time · 2 snoozed", or empty if nothing further is coming. */
    fun progressLine(context: Context, progress: Progress): String {
        val remaining = progress.remaining
        val parts = buildList {
            when {
                remaining == null -> add(context.getString(R.string.progress_no_end))
                remaining > 0 -> add(context.resources.getQuantityString(R.plurals.progress_more, remaining, remaining))
            }
            if (progress.snoozed > 0) {
                add(context.resources.getQuantityString(R.plurals.progress_snoozed, progress.snoozed, progress.snoozed))
            }
        }
        return parts.joinToString(" · ")
    }
}
