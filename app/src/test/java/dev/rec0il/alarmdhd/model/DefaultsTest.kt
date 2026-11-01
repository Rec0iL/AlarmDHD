package dev.rec0il.alarmdhd.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultsTest {
    private val playlist = SoundSource.PickedSongs(listOf(SoundSource.Song("a", "A")))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun newRun(runMinutes: Int) = Planner.newSeries(now = 0, runMinutes = runMinutes)

    @Test
    fun `new run ends the set number of minutes after its start`() {
        val run = newRun(45)
        assertEquals(7 * 60, run.startMinute)
        assertEquals(7 * 60 + 45, run.endMinute)
    }

    @Test
    fun `setting the start moves the automatic end with it`() {
        val moved = Planner.moveStart(newRun(30), 8 * 60, 30)
        assertEquals(8 * 60, moved.startMinute)
        assertEquals(8 * 60 + 30, moved.endMinute)
    }

    @Test
    fun `setting the start again keeps following`() {
        val first = Planner.moveStart(newRun(30), 8 * 60, 30)
        val second = Planner.moveStart(first, 6 * 60 + 15, 30)
        assertEquals(6 * 60 + 45, second.endMinute)
    }

    @Test
    fun `automatic end crosses midnight`() {
        assertEquals(10, Planner.moveStart(newRun(30), 23 * 60 + 40, 30).endMinute)
    }

    @Test
    fun `an end the user picked stays where it is`() {
        val picked = newRun(30).copy(endMinute = 9 * 60)
        val moved = Planner.moveStart(picked, 8 * 60, 30)
        assertEquals(8 * 60, moved.startMinute)
        assertEquals(9 * 60, moved.endMinute)
    }

    @Test
    fun `open end stays open when the start moves`() {
        val open = newRun(30).copy(endMinute = null)
        assertNull(Planner.moveStart(open, 8 * 60, 30).endMinute)
    }

    @Test
    fun `a run left on default plays the default from settings`() {
        assertEquals(playlist, Settings(defaultSound = playlist).resolve(SoundSource.DefaultAlarm))
    }

    @Test
    fun `a run with its own sound ignores the default from settings`() {
        val song = SoundSource.Song("b", "B")
        assertEquals(song, Settings(defaultSound = playlist).resolve(song))
    }

    @Test
    fun `with nothing chosen in settings the default is the system tone`() {
        assertEquals(SoundSource.DefaultAlarm, Settings().resolve(SoundSource.DefaultAlarm))
    }

    @Test
    fun `settings saved before these options existed still load`() {
        val saved = """{"series":[],"musicFolders":[{"treeUri":"x","name":"Music"}],"setupDone":true}"""
        val settings = json.decodeFromString<Settings>(saved)
        assertEquals(30, settings.defaultRunMinutes)
        assertEquals(SoundSource.DefaultAlarm, settings.defaultSound)
        assertEquals(1, settings.musicFolders.size)
    }

    @Test
    fun `the default sound survives saving and loading`() {
        val settings = Settings(defaultRunMinutes = 45, defaultSound = playlist)
        assertEquals(settings, json.decodeFromString<Settings>(json.encodeToString(settings)))
    }
}
