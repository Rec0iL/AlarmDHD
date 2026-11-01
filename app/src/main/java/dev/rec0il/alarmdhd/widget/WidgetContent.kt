package dev.rec0il.alarmdhd.widget

import dev.rec0il.alarmdhd.model.AppState
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.model.Progress
import java.time.Instant
import java.time.ZoneId

/**
 * What the widget shows. Plain Kotlin, like [Planner], so the tests cover it directly. [nextAt] comes
 * from [Planner.nextDue] even during a run, because that's the ring actually scheduled, whichever
 * series it belongs to.
 */
data class WidgetContent(val nextAt: Long?, val progress: Progress?) {
    /** The home screen's rule too: there has to be a run going before it can be stopped. */
    val canStop get() = progress != null

    companion object {
        fun of(state: AppState, now: Long, zone: ZoneId) = WidgetContent(
            nextAt = Planner.nextDue(state, now, zone)?.at,
            progress = Planner.progress(state, now, zone),
        )

        /** When "Tomorrow" stops being true, which no ring or snooze announces. */
        fun nextDayStart(now: Long, zone: ZoneId): Long =
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
