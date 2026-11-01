package dev.rec0il.alarmdhd.widget

import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.AppState
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetContentTest {
    private val zone = ZoneId.of("Europe/Berlin")

    // A Tuesday, well outside any daylight saving change.
    private val day = LocalDate.of(2026, 6, 16)

    private fun at(hour: Int, minute: Int, date: LocalDate = day, zone: ZoneId = this.zone): Long =
        LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun series(
        id: String = "a",
        start: Int = 7 * 60,
        end: Int? = 7 * 60 + 30,
        interval: Int = 5,
        snooze: Int = 7,
        days: Set<Int> = (1..7).toSet(),
    ) = AlarmSeries(
        id = id,
        startMinute = start,
        endMinute = end,
        intervalMinutes = interval,
        snoozeMinutes = snooze,
        days = days,
        armedAt = at(0, 0),
    )

    private fun stateOf(vararg series: AlarmSeries) = AppState(Settings(series = series.toList()))

    private fun ring(state: AppState, time: Long): AppState = Planner.fire(state, time, time, zone).state

    @Test
    fun `before a run it shows the first ring and no button`() {
        val content = WidgetContent.of(stateOf(series()), at(6, 0), zone)
        assertEquals(at(7, 0), content.nextAt)
        assertFalse(content.canStop)
    }

    @Test
    fun `with nothing enabled it shows no alarm and no button`() {
        val content = WidgetContent.of(stateOf(series().copy(enabled = false)), at(6, 0), zone)
        assertNull(content.nextAt)
        assertFalse(content.canStop)
    }

    @Test
    fun `a ring moves it to the next ring and brings up the button`() {
        val state = ring(stateOf(series()), at(7, 0))
        val content = WidgetContent.of(state, at(7, 0) + 1_000, zone)
        assertEquals(at(7, 5), content.nextAt)
        assertTrue(content.canStop)
    }

    @Test
    fun `a snooze due first takes over the next time`() {
        val rung = ring(stateOf(series(interval = 10, snooze = 3)), at(7, 0))
        val state = Planner.snooze(rung, listOf("a"), at(7, 1))
        val content = WidgetContent.of(state, at(7, 1) + 1_000, zone)
        assertEquals(at(7, 4), content.nextAt)
        assertEquals(1, content.progress?.snoozed)
    }

    @Test
    fun `a snooze that only stacks on top still shows up`() {
        val rung = ring(stateOf(series()), at(7, 0))
        val state = Planner.snooze(rung, listOf("a"), at(7, 1))
        val content = WidgetContent.of(state, at(7, 1) + 1_000, zone)
        assertEquals(at(7, 5), content.nextAt)
        assertEquals(1, content.progress?.snoozed)
    }

    @Test
    fun `a win ends the run, so it moves to tomorrow and the button goes`() {
        val rung = ring(stateOf(series()), at(7, 0))
        val state = Planner.stopRunning(rung, at(7, 2), zone)
        val content = WidgetContent.of(state, at(7, 2) + 1_000, zone)
        assertEquals(at(7, 0, day.plusDays(1)), content.nextAt)
        assertFalse(content.canStop)
    }

    @Test
    fun `the last ring of a run takes the button away`() {
        val state = ring(stateOf(series(end = 7 * 60 + 10)), at(7, 10))
        val content = WidgetContent.of(state, at(7, 10) + 1_000, zone)
        assertEquals(at(7, 0, day.plusDays(1)), content.nextAt)
        assertFalse(content.canStop)
    }

    @Test
    fun `during a run it shows what's scheduled, even from a series that isn't running yet`() {
        val running = series(id = "a", interval = 30)
        val later = series(id = "b", start = 7 * 60 + 10, end = null)
        val state = ring(stateOf(running, later), at(7, 0))
        val now = at(7, 5)

        val content = WidgetContent.of(state, now, zone)

        assertEquals(Planner.nextDue(state, now, zone)?.at, content.nextAt)
        assertEquals(at(7, 10), content.nextAt)
        assertNotEquals(content.nextAt, content.progress?.nextAt)
    }

    @Test
    fun `the new day starts at the coming midnight`() {
        assertEquals(at(0, 0, day.plusDays(1)), WidgetContent.nextDayStart(at(23, 59), zone))
        assertEquals(at(0, 0, day.plusDays(1)), WidgetContent.nextDayStart(at(0, 0), zone))
    }

    @Test
    fun `the new day starts when the clock says so where midnight doesn't exist`() {
        // Havana springs forward at midnight, so March 8th 2026 begins at 01:00.
        val havana = ZoneId.of("America/Havana")
        val saturday = LocalDate.of(2026, 3, 7)
        assertEquals(at(1, 0, saturday.plusDays(1), havana), WidgetContent.nextDayStart(at(12, 0, saturday, havana), havana))
    }
}
