package dev.rec0il.alarmdhd.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The next moment something should ring, and which series it belongs to. */
data class Due(val at: Long, val seriesIds: List<String>)

/** A ring that has just gone off. [position] and [total] are null for snoozes; [total] is null with open end. */
data class FiredRing(
    val seriesIds: List<String>,
    val isSnooze: Boolean,
    val position: Int?,
    val total: Int?,
)

data class FireResult(val state: AppState, val ring: FiredRing?)

/** A run in progress. [remaining] counts regular rings left and is null with open end. */
data class Progress(
    val seriesIds: List<String>,
    val nextAt: Long?,
    val remaining: Int?,
    val snoozed: Int,
)

/**
 * Every timing rule in the app. Nothing here touches Android, the clock or storage: state and
 * time go in, new state comes out. A "session" is one day's run of a series, identified by the
 * local date it starts on, even if it crosses midnight.
 */
object Planner {
    /** Open-end runs give up after this, so a phone forgotten at home doesn't ring until it dies. */
    const val OPEN_END_CAP_HOURS = 12L

    /** Anything due this soon after a ring merges into it, so snoozing never re-rings seconds later. */
    const val MERGE_WINDOW_MS = 45_000L

    private const val MINUTE_MS = 60_000L

    /** Far enough to find the next weekday even when the one after a skipped session is a week out. */
    private const val LOOKAHEAD_DAYS = 15L

    private fun localTime(minute: Int) = LocalTime.of(minute / 60, minute % 60)

    private fun dayOf(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun startOf(series: AlarmSeries, day: LocalDate, zone: ZoneId): Long =
        day.atTime(localTime(series.startMinute)).atZone(zone).toInstant().toEpochMilli()

    /** The latest moment a regular ring of this session may happen. */
    fun endOf(series: AlarmSeries, day: LocalDate, zone: ZoneId): Long {
        val start = startOf(series, day, zone)
        val endMinute = series.endMinute ?: return start + OPEN_END_CAP_HOURS * 60 * MINUTE_MS
        val endDay = if (endMinute < series.startMinute) day.plusDays(1) else day
        return maxOf(start, endDay.atTime(localTime(endMinute)).atZone(zone).toInstant().toEpochMilli())
    }

    private fun stepMs(series: AlarmSeries) = series.intervalMinutes * MINUTE_MS

    /** The first ring of this session strictly after [after], or null when the session is over. */
    fun ringAfter(series: AlarmSeries, day: LocalDate, after: Long, zone: ZoneId): Long? {
        val start = startOf(series, day, zone)
        if (after < start) return start
        val step = stepMs(series)
        val next = start + ((after - start) / step + 1) * step
        return next.takeIf { it <= endOf(series, day, zone) }
    }

    private fun lastRingOf(series: AlarmSeries, day: LocalDate, zone: ZoneId): Long {
        val start = startOf(series, day, zone)
        val step = stepMs(series)
        return start + (endOf(series, day, zone) - start) / step * step
    }

    private fun ringsInSession(series: AlarmSeries, day: LocalDate, zone: ZoneId): Int {
        val start = startOf(series, day, zone)
        return ((lastRingOf(series, day, zone) - start) / stepMs(series)).toInt() + 1
    }

    private fun onceDay(series: AlarmSeries, zone: ZoneId): LocalDate {
        val armedDay = dayOf(series.armedAt, zone)
        return if (startOf(series, armedDay, zone) > series.armedAt) armedDay else armedDay.plusDays(1)
    }

    private fun runsOn(series: AlarmSeries, day: LocalDate, zone: ZoneId) =
        if (series.isOnce) day == onceDay(series, zone) else day.dayOfWeek.value in series.days

    /** Can this day's session ring, ignoring whether its time has come yet? */
    private fun isLive(state: AppState, series: AlarmSeries, day: LocalDate, zone: ZoneId): Boolean {
        if (!series.enabled || !runsOn(series, day, zone)) return false
        if (startOf(series, day, zone) <= series.armedAt) return false
        val epochDay = day.toEpochDay()
        val stopped = state.live.stoppedThroughDay[series.id]
        if (stopped != null && epochDay <= stopped) return false
        return state.live.skippedDay[series.id] != epochDay
    }

    private fun nextSeriesRing(state: AppState, series: AlarmSeries, after: Long, zone: ZoneId): Long? {
        val today = dayOf(after, zone)
        // Starting a day back covers sessions that began yesterday and are still going.
        for (offset in -1L..LOOKAHEAD_DAYS) {
            val day = today.plusDays(offset)
            if (!isLive(state, series, day, zone)) continue
            ringAfter(series, day, after, zone)?.let { return it }
        }
        return null
    }

    private fun dueAfter(state: AppState, after: Long, zone: ZoneId): Due? {
        val rings = state.settings.series.mapNotNull { series ->
            nextSeriesRing(state, series, after, zone)?.let { series.id to it }
        }
        // Overdue snoozes still count: a snooze always rings, even if the phone was off at the time.
        val candidates = rings + state.live.snoozes.map { it.seriesId to it.fireAt }
        val at = candidates.minOfOrNull { it.second } ?: return null
        return Due(at, candidates.filter { it.second == at }.map { it.first }.distinct())
    }

    fun nextDue(state: AppState, now: Long, zone: ZoneId): Due? {
        val sinceLastRing = now - state.live.lastRingAt
        val after = if (sinceLastRing in 0 until MERGE_WINDOW_MS) state.live.lastRingAt + MERGE_WINDOW_MS else now
        return dueAfter(state, after, zone)
    }

    /**
     * Consumes what the alarm scheduled for [at] was meant to ring. Returns a null ring if the
     * state changed after it was scheduled, so a stale alarm can't ring something switched off.
     */
    fun fire(state: AppState, at: Long, now: Long, zone: ZoneId): FireResult {
        val due = dueAfter(state, at - 1, zone)
        if (due == null || due.at > at) return FireResult(state, null)

        val consumed = state.live.snoozes.filter { it.fireAt <= now + MERGE_WINDOW_MS }
        val ids = (due.seriesIds + consumed.map { it.seriesId }).distinct()
        val regular = due.seriesIds
            .mapNotNull { id -> state.settings.series.find { it.id == id } }
            .firstNotNullOfOrNull { positionOf(state, it, at, zone) }

        val live = state.live.copy(snoozes = state.live.snoozes - consumed.toSet(), lastRingAt = now)
        val ring = FiredRing(ids, isSnooze = regular == null, position = regular?.first, total = regular?.second)
        return FireResult(state.copy(live = live), ring)
    }

    private fun positionOf(state: AppState, series: AlarmSeries, at: Long, zone: ZoneId): Pair<Int, Int?>? {
        val today = dayOf(at, zone)
        for (offset in -1L..0L) {
            val day = today.plusDays(offset)
            if (!isLive(state, series, day, zone)) continue
            val start = startOf(series, day, zone)
            if (at < start || at > endOf(series, day, zone) || (at - start) % stepMs(series) != 0L) continue
            val position = ((at - start) / stepMs(series)).toInt() + 1
            return position to if (series.isOpenEnd) null else ringsInSession(series, day, zone)
        }
        return null
    }

    fun snooze(state: AppState, seriesIds: List<String>, now: Long): AppState {
        val added = seriesIds.mapNotNull { id ->
            state.settings.series.find { it.id == id }?.let { Snooze(id, now + it.snoozeMinutes * MINUTE_MS) }
        }
        return state.copy(live = state.live.copy(snoozes = state.live.snoozes + added))
    }

    private fun currentSession(state: AppState, series: AlarmSeries, now: Long, zone: ZoneId): LocalDate? {
        val today = dayOf(now, zone)
        return listOf(today, today.minusDays(1)).firstOrNull { day ->
            isLive(state, series, day, zone) &&
                startOf(series, day, zone) <= now &&
                now <= lastRingOf(series, day, zone)
        }
    }

    fun isRunning(state: AppState, series: AlarmSeries, now: Long, zone: ZoneId): Boolean =
        state.live.snoozes.any { it.seriesId == series.id } || currentSession(state, series, now, zone) != null

    fun runningIds(state: AppState, now: Long, zone: ZoneId): List<String> =
        state.settings.series.filter { isRunning(state, it, now, zone) }.map { it.id }

    /** A win: ends today's session and pending snoozes of every run that's going. */
    fun stopRunning(state: AppState, now: Long, zone: ZoneId): AppState {
        val ids = runningIds(state, now, zone).toSet()
        var stopped = state.live.stoppedThroughDay
        for (series in state.settings.series.filter { it.id in ids }) {
            val day = currentSession(state, series, now, zone) ?: continue
            stopped = stopped + (series.id to day.toEpochDay())
        }
        val live = state.live.copy(
            snoozes = state.live.snoozes.filterNot { it.seriesId in ids },
            stoppedThroughDay = stopped,
        )
        return state.copy(live = live)
    }

    /** The next session that hasn't started, ignoring any skip, so the UI can offer or undo one. */
    fun upcomingDay(state: AppState, series: AlarmSeries, now: Long, zone: ZoneId): LocalDate? {
        val unskipped = state.copy(live = state.live.copy(skippedDay = state.live.skippedDay - series.id))
        val today = dayOf(now, zone)
        for (offset in 0L..LOOKAHEAD_DAYS) {
            val day = today.plusDays(offset)
            if (isLive(unskipped, series, day, zone) && startOf(series, day, zone) > now) return day
        }
        return null
    }

    fun skip(state: AppState, seriesId: String, day: LocalDate): AppState =
        state.copy(live = state.live.copy(skippedDay = state.live.skippedDay + (seriesId to day.toEpochDay())))

    fun unskip(state: AppState, seriesId: String): AppState =
        state.copy(live = state.live.copy(skippedDay = state.live.skippedDay - seriesId))

    fun isSkipped(state: AppState, seriesId: String, day: LocalDate): Boolean =
        state.live.skippedDay[seriesId] == day.toEpochDay()

    fun progress(state: AppState, now: Long, zone: ZoneId): Progress? {
        val running = state.settings.series.filter { isRunning(state, it, now, zone) }
        if (running.isEmpty()) return null
        val ids = running.map { it.id }
        val snoozes = state.live.snoozes.filter { it.seriesId in ids }

        var openEnd = false
        var remaining = 0
        val nextRings = running.mapNotNull { series ->
            val day = currentSession(state, series, now, zone) ?: return@mapNotNull null
            if (series.isOpenEnd) {
                openEnd = true
            } else {
                val last = lastRingOf(series, day, zone)
                val next = ringAfter(series, day, now, zone)
                if (next != null) remaining += ((last - next) / stepMs(series)).toInt() + 1
            }
            ringAfter(series, day, now, zone)
        }
        val nextAt = (nextRings + snoozes.map { it.fireAt }).minOrNull()
        return Progress(ids, nextAt, if (openEnd) null else remaining, snoozes.size)
    }

    /** Runs a one-time series to its end: once nothing is left to ring, it switches itself off. */
    fun housekeep(state: AppState, now: Long, zone: ZoneId): AppState {
        val series = state.settings.series.map { series ->
            val finished = series.enabled && series.isOnce &&
                state.live.snoozes.none { it.seriesId == series.id } &&
                nextSeriesRing(state, series, now, zone) == null
            if (finished) series.copy(enabled = false) else series
        }
        return if (series == state.settings.series) state else state.copy(settings = state.settings.copy(series = series))
    }

    /** Adds or replaces a series. Changing when it rings starts it fresh from its next start time. */
    fun save(state: AppState, series: AlarmSeries, now: Long): AppState {
        val existing = state.settings.series.find { it.id == series.id }
        val sameSchedule = existing != null &&
            existing.startMinute == series.startMinute &&
            existing.endMinute == series.endMinute &&
            existing.intervalMinutes == series.intervalMinutes &&
            existing.days == series.days &&
            existing.enabled == series.enabled
        val saved = series.copy(armedAt = if (sameSchedule) existing!!.armedAt else now)
        val all = if (existing == null) {
            state.settings.series + saved
        } else {
            state.settings.series.map { if (it.id == series.id) saved else it }
        }
        return state.copy(settings = state.settings.copy(series = all))
    }

    fun setEnabled(state: AppState, seriesId: String, enabled: Boolean, now: Long): AppState {
        val all = state.settings.series.map {
            if (it.id == seriesId) it.copy(enabled = enabled, armedAt = now) else it
        }
        val live = if (enabled) {
            state.live
        } else {
            state.live.copy(snoozes = state.live.snoozes.filterNot { it.seriesId == seriesId })
        }
        return state.copy(settings = state.settings.copy(series = all), live = live)
    }

    fun delete(state: AppState, seriesId: String): AppState = state.copy(
        settings = state.settings.copy(series = state.settings.series.filterNot { it.id == seriesId }),
        live = state.live.copy(
            snoozes = state.live.snoozes.filterNot { it.seriesId == seriesId },
            stoppedThroughDay = state.live.stoppedThroughDay - seriesId,
            skippedDay = state.live.skippedDay - seriesId,
            trackCursors = state.live.trackCursors - seriesId,
            lastRandomTrack = state.live.lastRandomTrack - seriesId,
            soundIssues = state.live.soundIssues - seriesId,
        ),
    )

    /** Regular ring times for the editor, as minutes since midnight. Null [count] means open end. */
    fun previewMinutes(series: AlarmSeries, limit: Int): RingPreview {
        val span = series.endMinute?.let { (it - series.startMinute + 1440) % 1440 }
        val count = span?.let { it / series.intervalMinutes + 1 }
        val shown = minOf(count ?: limit, limit)
        val minutes = List(shown) { (series.startMinute + it * series.intervalMinutes) % 1440 }
        return RingPreview(minutes, count)
    }

    fun newSeries(now: Long, runMinutes: Int): AlarmSeries = AlarmSeries(
        id = UUID.randomUUID().toString(),
        startMinute = 7 * 60,
        endMinute = endAfter(7 * 60, runMinutes),
        armedAt = now,
    )

    fun endAfter(startMinute: Int, runMinutes: Int): Int = (startMinute + runMinutes) % 1440

    /** Sets a new run's start. Its end moves along with it, until the user has picked a different end. */
    fun moveStart(series: AlarmSeries, startMinute: Int, runMinutes: Int): AlarmSeries {
        val endIsAutomatic = series.endMinute == endAfter(series.startMinute, runMinutes)
        return series.copy(
            startMinute = startMinute,
            endMinute = if (endIsAutomatic) endAfter(startMinute, runMinutes) else series.endMinute,
        )
    }
}

data class RingPreview(val minutes: List<Int>, val count: Int?)
