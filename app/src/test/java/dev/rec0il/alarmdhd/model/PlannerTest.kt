package dev.rec0il.alarmdhd.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PlannerTest {
    private val zone = ZoneId.of("Europe/Berlin")

    // A Tuesday, well outside any daylight saving change.
    private val day = LocalDate.of(2026, 6, 16)

    private fun at(hour: Int, minute: Int, second: Int = 0, date: LocalDate = day): Long =
        LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute, second)
            .atZone(zone).toInstant().toEpochMilli()

    private fun clock(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().toString().take(5)

    private fun series(
        start: Int = 7 * 60,
        end: Int? = 7 * 60 + 30,
        interval: Int = 5,
        snooze: Int = 7,
        days: Set<Int> = emptySet(),
        armedAt: Long = at(0, 0),
        id: String = "a",
    ) = AlarmSeries(
        id = id,
        startMinute = start,
        endMinute = end,
        intervalMinutes = interval,
        snoozeMinutes = snooze,
        days = days,
        armedAt = armedAt,
    )

    private fun stateOf(vararg series: AlarmSeries) = AppState(Settings(series = series.toList()))

    /** Lets the phone run: fires each ring on time and applies [onRing] to it, returning clock times. */
    private fun simulate(
        initial: AppState,
        from: Long,
        until: Long,
        onRing: (AppState, Long, FiredRing) -> AppState = { state, _, _ -> state },
    ): List<String> {
        val rings = mutableListOf<String>()
        var state = initial
        var now = from
        while (true) {
            val due = Planner.nextDue(state, now, zone) ?: break
            if (due.at > until) break
            now = maxOf(now, due.at)
            val result = Planner.fire(state, due.at, now, zone)
            state = result.state
            val ring = result.ring ?: continue
            rings += clock(now)
            state = onRing(state, now, ring)
        }
        return rings
    }

    // --- Ring times ---

    @Test
    fun `rings every interval from start to end inclusive`() {
        val rings = simulate(stateOf(series()), at(6, 0), at(23, 0))
        assertEquals(listOf("07:00", "07:05", "07:10", "07:15", "07:20", "07:25", "07:30"), rings)
    }

    @Test
    fun `run crossing midnight rings on both sides of it`() {
        val rings = simulate(stateOf(series(start = 23 * 60 + 50, end = 10, interval = 10)), at(23, 0), at(23, 59) + 3600_000)
        assertEquals(listOf("23:50", "00:00", "00:10"), rings)
    }

    @Test
    fun `end not on the interval stops at the last ring before it`() {
        val rings = simulate(stateOf(series(end = 7 * 60 + 12)), at(6, 0), at(23, 0))
        assertEquals(listOf("07:00", "07:05", "07:10"), rings)
    }

    @Test
    fun `same start and end rings once`() {
        val rings = simulate(stateOf(series(end = 7 * 60)), at(6, 0), at(23, 0))
        assertEquals(listOf("07:00"), rings)
    }

    @Test
    fun `fired ring reports its position in the run`() {
        val result = Planner.fire(stateOf(series()), at(7, 10), at(7, 10), zone)
        val ring = result.ring!!
        assertEquals(3, ring.position)
        assertEquals(7, ring.total)
        assertFalse(ring.isSnooze)
    }

    // --- Open end ---

    @Test
    fun `open end keeps ringing past any fixed time`() {
        val rings = simulate(stateOf(series(end = null, interval = 30)), at(6, 0), at(11, 0))
        assertEquals(listOf("07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00"), rings)
    }

    @Test
    fun `open end has no total and counts up`() {
        val result = Planner.fire(stateOf(series(end = null)), at(9, 0), at(9, 0), zone)
        assertEquals(25, result.ring!!.position)
        assertNull(result.ring!!.total)
    }

    @Test
    fun `open end gives up after twelve hours`() {
        val rings = simulate(stateOf(series(end = null, interval = 60)), at(6, 0), at(23, 59))
        assertEquals("19:00", rings.last())
        assertEquals(13, rings.size)
    }

    @Test
    fun `open end stops on a win and starts again on its next day`() {
        var state = stateOf(series(end = null, days = setOf(2, 3)))
        state = Planner.stopRunning(state, at(7, 12), zone)
        val rings = simulate(state, at(7, 12), at(7, 10, date = day.plusDays(1)))
        assertEquals(listOf("07:00", "07:05", "07:10"), rings)
    }

    @Test
    fun `open end rings on past midnight when it starts late`() {
        val rings = simulate(stateOf(series(start = 23 * 60 + 30, end = null, interval = 30)), at(23, 0), at(1, 0, date = day.plusDays(1)))
        assertEquals(listOf("23:30", "00:00", "00:30", "01:00"), rings)
    }

    // --- Weekdays, skip, stop ---

    @Test
    fun `repeating run rings only on selected weekdays`() {
        // Tuesday is day 2. Selecting Thursday should jump over Wednesday.
        val state = stateOf(series(days = setOf(4)))
        val next = Planner.nextDue(state, at(8, 0), zone)!!
        assertEquals(at(7, 0, date = day.plusDays(2)), next.at)
    }

    @Test
    fun `skip jumps over the next session and undo restores it`() {
        val state = stateOf(series(days = setOf(2, 3)))
        val upcoming = Planner.upcomingDay(state, state.settings.series[0], at(6, 0), zone)!!
        assertEquals(day, upcoming)

        val skipped = Planner.skip(state, "a", upcoming)
        assertEquals(at(7, 0, date = day.plusDays(1)), Planner.nextDue(skipped, at(6, 0), zone)!!.at)
        assertTrue(Planner.isSkipped(skipped, "a", upcoming))
        assertEquals(upcoming, Planner.upcomingDay(skipped, skipped.settings.series[0], at(6, 0), zone))

        val undone = Planner.unskip(skipped, "a")
        assertEquals(at(7, 0), Planner.nextDue(undone, at(6, 0), zone)!!.at)
    }

    @Test
    fun `winning stops the rest of the run and its snoozes`() {
        var state = stateOf(series(days = setOf(2, 3)))
        state = Planner.snooze(state, listOf("a"), at(7, 5))
        state = Planner.stopRunning(state, at(7, 8), zone)
        assertTrue(state.live.snoozes.isEmpty())
        assertEquals(at(7, 0, date = day.plusDays(1)), Planner.nextDue(state, at(7, 8), zone)!!.at)
    }

    @Test
    fun `winning leaves a run that has not started yet`() {
        val wake = series(id = "wake")
        val leave = series(id = "leave", start = 8 * 60 + 15, end = 8 * 60 + 30)
        val state = Planner.stopRunning(stateOf(wake, leave), at(7, 8), zone)
        assertEquals(listOf("leave"), Planner.nextDue(state, at(7, 8), zone)!!.seriesIds)
        assertEquals(at(8, 15), Planner.nextDue(state, at(7, 8), zone)!!.at)
    }

    @Test
    fun `winning also stops every other run going at the same time`() {
        val one = series(id = "one")
        val two = series(id = "two", start = 7 * 60 + 2, end = 7 * 60 + 40)
        val state = Planner.stopRunning(stateOf(one, two), at(7, 8), zone)
        assertNull(Planner.nextDue(state, at(7, 8), zone))
    }

    @Test
    fun `new run does not ring for a session already under way`() {
        val state = stateOf(series(days = setOf(2, 3), armedAt = at(7, 10)))
        assertEquals(at(7, 0, date = day.plusDays(1)), Planner.nextDue(state, at(7, 10), zone)!!.at)
    }

    @Test
    fun `changing the schedule restarts from the next start but a label does not`() {
        val original = series(days = setOf(2, 3))
        val state = stateOf(original)
        assertEquals(original.armedAt, Planner.save(state, original.copy(label = "Work"), at(7, 10)).settings.series[0].armedAt)
        assertEquals(at(7, 10), Planner.save(state, original.copy(intervalMinutes = 3), at(7, 10)).settings.series[0].armedAt)
    }

    // --- Stacking ---

    @Test
    fun `snoozes stack on top of the run in the agreed order`() {
        // The example from the plan: snooze 07:00, 07:05 and 07:07, then leave the rest alone.
        val snoozeThese = setOf("07:00", "07:05", "07:07")
        val rings = simulate(stateOf(series()), at(6, 0), at(7, 20)) { state, now, ring ->
            if (clock(now) in snoozeThese) Planner.snooze(state, ring.seriesIds, now) else state
        }
        assertEquals(
            listOf("07:00", "07:05", "07:07", "07:10", "07:12", "07:14", "07:15", "07:20"),
            rings,
        )
    }

    @Test
    fun `snooze rings even past the end time`() {
        val rings = simulate(stateOf(series()), at(6, 0), at(23, 0)) { state, now, ring ->
            if (clock(now) == "07:30") Planner.snooze(state, ring.seriesIds, now) else state
        }
        assertEquals("07:37", rings.last())
    }

    @Test
    fun `snooze ring is reported as a snooze`() {
        val state = Planner.snooze(stateOf(series()), listOf("a"), at(7, 0))
        val ring = Planner.fire(state, at(7, 7), at(7, 7), zone).ring!!
        assertTrue(ring.isSnooze)
        assertNull(ring.position)
    }

    @Test
    fun `overdue snooze rings right away after the phone was off`() {
        val state = Planner.snooze(stateOf(series()), listOf("a"), at(7, 0))
        val due = Planner.nextDue(state, at(7, 9), zone)!!
        assertEquals(at(7, 7), due.at)
    }

    @Test
    fun `missed regular rings are not replayed`() {
        val due = Planner.nextDue(stateOf(series()), at(7, 9), zone)!!
        assertEquals(at(7, 10), due.at)
    }

    // --- Merging ---

    @Test
    fun `snooze due just after a ring merges into it`() {
        var state = stateOf(series())
        state = Planner.snooze(state, listOf("a"), at(7, 3, 3)) // Due at 07:10:03.
        val result = Planner.fire(state, at(7, 10), at(7, 10), zone)
        assertTrue(result.state.live.snoozes.isEmpty())
        assertEquals(listOf("a"), result.ring!!.seriesIds)
    }

    @Test
    fun `regular ring just after a snooze ring merges into it`() {
        var state = stateOf(series())
        state = Planner.snooze(state, listOf("a"), at(7, 2, 50)) // Due at 07:09:50.
        val fired = Planner.fire(state, at(7, 9, 50), at(7, 9, 50), zone)
        val next = Planner.nextDue(fired.state, at(7, 9, 55), zone)!!
        assertEquals(at(7, 15), next.at)
    }

    @Test
    fun `one minute interval is not swallowed by merging`() {
        val rings = simulate(stateOf(series(interval = 1, end = 7 * 60 + 3)), at(6, 0), at(23, 0))
        assertEquals(listOf("07:00", "07:01", "07:02", "07:03"), rings)
    }

    @Test
    fun `two runs due at the same moment ring together`() {
        val state = stateOf(series(id = "one"), series(id = "two"))
        val due = Planner.nextDue(state, at(6, 0), zone)!!
        assertEquals(listOf("one", "two"), due.seriesIds)
    }

    @Test
    fun `stale alarm for something switched off does not ring`() {
        val state = Planner.setEnabled(stateOf(series()), "a", false, at(6, 59))
        assertNull(Planner.fire(state, at(7, 0), at(7, 0), zone).ring)
    }

    // --- One-time runs ---

    @Test
    fun `one time run with no days rings the next session only`() {
        val state = stateOf(series(armedAt = at(22, 0)))
        assertEquals(at(7, 0, date = day.plusDays(1)), Planner.nextDue(state, at(22, 0), zone)!!.at)
    }

    @Test
    fun `one time run switches itself off when finished`() {
        val state = stateOf(series())
        assertTrue(Planner.housekeep(state, at(7, 10), zone).settings.series[0].enabled)
        assertFalse(Planner.housekeep(state, at(7, 31), zone).settings.series[0].enabled)
    }

    @Test
    fun `one time run stays on while a snooze is pending`() {
        val state = Planner.snooze(stateOf(series()), listOf("a"), at(7, 30))
        assertTrue(Planner.housekeep(state, at(7, 31), zone).settings.series[0].enabled)
    }

    @Test
    fun `one time run switches itself off after a win`() {
        val state = Planner.stopRunning(stateOf(series()), at(7, 3), zone)
        assertFalse(Planner.housekeep(state, at(7, 3), zone).settings.series[0].enabled)
    }

    // --- Running state ---

    @Test
    fun `progress counts the rings left and the snoozes stacked`() {
        val state = Planner.snooze(stateOf(series()), listOf("a"), at(7, 8))
        val progress = Planner.progress(state, at(7, 8), zone)!!
        assertEquals(at(7, 10), progress.nextAt)
        assertEquals(5, progress.remaining)
        assertEquals(1, progress.snoozed)
    }

    @Test
    fun `progress with open end has no count`() {
        val progress = Planner.progress(stateOf(series(end = null)), at(7, 8), zone)!!
        assertNull(progress.remaining)
        assertEquals(at(7, 10), progress.nextAt)
    }

    @Test
    fun `nothing is running before the start or after the end`() {
        val state = stateOf(series())
        assertNull(Planner.progress(state, at(6, 59), zone))
        assertNull(Planner.progress(state, at(7, 31), zone))
        assertNotNull(Planner.progress(state, at(7, 30), zone))
    }

    @Test
    fun `run stays running while only a snooze is left after the end`() {
        val state = Planner.snooze(stateOf(series()), listOf("a"), at(7, 30))
        val progress = Planner.progress(state, at(7, 31), zone)!!
        assertEquals(at(7, 37), progress.nextAt)
        assertEquals(0, progress.remaining)
    }

    // --- Daylight saving ---

    @Test
    fun `spring forward keeps real spacing and never repeats a ring`() {
        // On this night 02:00 jumps straight to 03:00.
        val night = LocalDate.of(2026, 3, 29)
        val armed = at(0, 0, date = night)
        val state = stateOf(series(start = 60 + 50, end = 3 * 60 + 10, interval = 10, armedAt = armed))
        val rings = simulate(state, at(1, 0, date = night), at(5, 0, date = night))
        assertEquals(listOf("01:50", "03:00", "03:10"), rings)
    }

    @Test
    fun `fall back keeps real spacing through the repeated hour`() {
        // On this night 03:00 falls back to 02:00, so 02:30 happens twice.
        val night = LocalDate.of(2026, 10, 25)
        val armed = at(0, 0, date = night)
        val state = stateOf(series(start = 60 + 50, end = 3 * 60 + 10, interval = 30, armedAt = armed))
        val rings = simulate(state, at(1, 0, date = night), at(5, 0, date = night))
        assertEquals(listOf("01:50", "02:20", "02:50", "02:20", "02:50"), rings)
    }

    @Test
    fun `start time inside the skipped hour still rings`() {
        val night = LocalDate.of(2026, 3, 29)
        val armed = at(0, 0, date = night)
        val state = stateOf(series(start = 2 * 60 + 30, end = 2 * 60 + 40, interval = 5, armedAt = armed))
        val rings = simulate(state, at(1, 0, date = night), at(5, 0, date = night))
        assertEquals(listOf("03:30", "03:35", "03:40"), rings)
    }

    // --- Editor preview ---

    @Test
    fun `preview lists the ring times and their count`() {
        val preview = Planner.previewMinutes(series(), limit = 12)
        assertEquals(7, preview.count)
        assertEquals(listOf(420, 425, 430, 435, 440, 445, 450), preview.minutes)
    }

    @Test
    fun `preview crosses midnight`() {
        val preview = Planner.previewMinutes(series(start = 23 * 60 + 50, end = 10, interval = 10), limit = 12)
        assertEquals(3, preview.count)
        assertEquals(listOf(1430, 0, 10), preview.minutes)
    }

    @Test
    fun `preview of open end has no count`() {
        val preview = Planner.previewMinutes(series(end = null), limit = 4)
        assertNull(preview.count)
        assertEquals(listOf(420, 425, 430, 435), preview.minutes)
    }

    @Test
    fun `preview caps a long list but keeps the real count`() {
        val preview = Planner.previewMinutes(series(interval = 1), limit = 4)
        assertEquals(31, preview.count)
        assertEquals(4, preview.minutes.size)
    }
}
