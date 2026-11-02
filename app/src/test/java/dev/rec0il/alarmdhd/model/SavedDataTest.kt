package dev.rec0il.alarmdhd.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedDataTest {
    // The two ways the app saves things: AlarmStore keeps defaults, and the screen stack leaves them out.
    private val store = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val navigation = Json { ignoreUnknownKeys = true }

    @Test
    fun `a run saved before gradual volume existed still loads, with it off`() {
        val saved = """{"id":"a","startMinute":420,"sound":{"type":"song","uri":"u","title":"T"},"armedAt":5}"""
        val series = store.decodeFromString<AlarmSeries>(saved)
        assertFalse(series.gradualVolume)
        assertEquals(SoundSource.Song("u", "T"), series.sound)
    }

    @Test
    fun `settings saved by 1_0_0 still load, with games on`() {
        val saved = """{"series":[],"setupDone":true,"defaultRunMinutes":45,"defaultSound":{"type":"default"}}"""
        val settings = store.decodeFromString<Settings>(saved)
        assertTrue(settings.gamesEnabled)
        assertEquals(45, settings.defaultRunMinutes)
    }

    @Test
    fun `games turned off survive saving`() {
        val settings = Settings(setupDone = true, gamesEnabled = false)
        assertEquals(settings, store.decodeFromString<Settings>(store.encodeToString(settings)))
    }

    @Test
    fun `gradual volume and picked songs survive saving`() {
        val series = AlarmSeries(
            id = "a",
            startMinute = 420,
            gradualVolume = true,
            sound = SoundSource.PickedSongs(listOf(SoundSource.Song("u", "Mr. Blue Sky")), shuffle = true),
        )
        assertEquals(series, store.decodeFromString<AlarmSeries>(store.encodeToString(series)))
        assertEquals(series, navigation.decodeFromString<AlarmSeries>(navigation.encodeToString(series)))
    }
}
