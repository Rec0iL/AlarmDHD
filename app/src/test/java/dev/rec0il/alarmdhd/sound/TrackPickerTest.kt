package dev.rec0il.alarmdhd.sound

import dev.rec0il.alarmdhd.model.LibraryIndex
import dev.rec0il.alarmdhd.model.LibraryPlaylist
import dev.rec0il.alarmdhd.model.LibraryTrack
import dev.rec0il.alarmdhd.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TrackPickerTest {
    private val songs = listOf(
        LibraryTrack("a", "Music/Rock/A.mp3"),
        LibraryTrack("b", "Music/Rock/B.mp3"),
        LibraryTrack("c", "Music/Pop/C.mp3"),
    )
    private val playlist = LibraryPlaylist("list", "Music/list.m3u", listOf("a", "b", "c"), totalEntries = 3)
    private val library = LibraryIndex(songs, listOf(playlist))

    private fun uris(pick: TrackPick) = pick.tracks.map { it.uri }

    @Test
    fun `default tone needs no tracks`() {
        assertTrue(TrackPicker.pick(SoundSource.DefaultAlarm, library, 0, null).tracks.isEmpty())
    }

    @Test
    fun `one song plays that song`() {
        val pick = TrackPicker.pick(SoundSource.Song("x", "X"), library, 0, null)
        assertEquals(listOf("x"), uris(pick))
    }

    @Test
    fun `playlist continues from where the last ring left off`() {
        val source = SoundSource.Playlist("list", "List")
        val first = TrackPicker.pick(source, library, 0, null)
        assertEquals(listOf("a", "b", "c"), uris(first))
        val second = TrackPicker.pick(source, library, first.nextCursor!!, null)
        assertEquals(listOf("b", "c", "a"), uris(second))
    }

    @Test
    fun `playlist position wraps at the end`() {
        val pick = TrackPicker.pick(SoundSource.Playlist("list", "List"), library, 3, null)
        assertEquals(listOf("a", "b", "c"), uris(pick))
    }

    @Test
    fun `playlist skips songs no longer in the library`() {
        val gone = library.copy(tracks = songs.filterNot { it.uri == "b" })
        val pick = TrackPicker.pick(SoundSource.Playlist("list", "List"), gone, 0, null)
        assertEquals(listOf("a", "c"), uris(pick))
    }

    @Test
    fun `missing playlist gives no tracks, so the fallback plays`() {
        assertTrue(TrackPicker.pick(SoundSource.Playlist("gone", "Gone"), library, 0, null).tracks.isEmpty())
    }

    @Test
    fun `shuffled playlist still contains every song`() {
        val pick = TrackPicker.pick(SoundSource.Playlist("list", "List", shuffle = true), library, 0, null, Random(1))
        assertEquals(setOf("a", "b", "c"), uris(pick).toSet())
    }

    private val picked = SoundSource.PickedSongs(
        listOf(SoundSource.Song("a", "A"), SoundSource.Song("b", "B"), SoundSource.Song("c", "C")),
    )

    @Test
    fun `picked songs continue from where the last ring left off`() {
        val first = TrackPicker.pick(picked, library, 0, null)
        assertEquals(listOf("a", "b", "c"), uris(first))
        val second = TrackPicker.pick(picked, library, first.nextCursor!!, null)
        assertEquals(listOf("b", "c", "a"), uris(second))
    }

    @Test
    fun `picked songs wrap at the end`() {
        assertEquals(listOf("a", "b", "c"), uris(TrackPicker.pick(picked, library, 3, null)))
    }

    @Test
    fun `picked songs keep the order they were picked in`() {
        val source = SoundSource.PickedSongs(listOf(SoundSource.Song("c", "C"), SoundSource.Song("a", "A")))
        assertEquals(listOf("c", "a"), uris(TrackPicker.pick(source, library, 0, null)))
    }

    @Test
    fun `picked songs keep their saved titles`() {
        val source = SoundSource.PickedSongs(listOf(SoundSource.Song("x", "Mr. Blue Sky")))
        assertEquals("Mr. Blue Sky", TrackPicker.pick(source, library, 0, null).tracks.single().title)
    }

    @Test
    fun `picked songs still play if the scan has been lost`() {
        assertEquals(listOf("a", "b", "c"), uris(TrackPicker.pick(picked, LibraryIndex(), 0, null)))
    }

    @Test
    fun `picked song missing from the library is still tried, so the chain can report it`() {
        val gone = library.copy(tracks = songs.filterNot { it.uri == "b" })
        assertEquals(listOf("a", "b", "c"), uris(TrackPicker.pick(picked, gone, 0, null)))
    }

    @Test
    fun `shuffled picked songs contain every song and remember the first`() {
        val pick = TrackPicker.pick(picked.copy(shuffle = true), library, 0, null, Random(1))
        assertEquals(setOf("a", "b", "c"), uris(pick).toSet())
        assertEquals(uris(pick).first(), pick.randomPick)
        assertNull(pick.nextCursor)
    }

    @Test
    fun `shuffled picked songs never repeat the last song right away`() {
        for (seed in 0 until 200) {
            val pick = TrackPicker.pick(picked.copy(shuffle = true), library, 0, "a", Random(seed))
            assertNotEquals("a", uris(pick).first())
        }
    }

    @Test
    fun `empty picked songs give no tracks, so the fallback plays`() {
        assertTrue(TrackPicker.pick(SoundSource.PickedSongs(emptyList()), library, 0, null).tracks.isEmpty())
    }

    @Test
    fun `folder limits to its own songs`() {
        val pick = TrackPicker.pick(SoundSource.Folder("Music/Rock", "Rock"), library, 0, null, Random(1))
        assertEquals(setOf("a", "b"), uris(pick).toSet())
    }

    @Test
    fun `folder prefix does not match a similarly named folder`() {
        val tracks = songs + LibraryTrack("d", "Music/Rock Classics/D.mp3")
        val pick = TrackPicker.pick(SoundSource.Folder("Music/Rock", "Rock"), LibraryIndex(tracks), 0, null, Random(1))
        assertEquals(setOf("a", "b"), uris(pick).toSet())
    }

    @Test
    fun `empty prefix means the whole library`() {
        val pick = TrackPicker.pick(SoundSource.Folder("", "All"), library, 0, null, Random(1))
        assertEquals(3, pick.tracks.size)
    }

    @Test
    fun `random folder never repeats the last song right away`() {
        for (seed in 0 until 200) {
            val pick = TrackPicker.pick(SoundSource.Folder("", "All"), library, 0, "a", Random(seed))
            assertNotEquals("a", uris(pick).first())
        }
    }

    @Test
    fun `random pick is remembered for the next ring`() {
        val pick = TrackPicker.pick(SoundSource.Folder("", "All"), library, 0, null, Random(1))
        assertEquals(uris(pick).first(), pick.randomPick)
    }

    @Test
    fun `folder with one song may repeat it`() {
        val pick = TrackPicker.pick(SoundSource.Folder("Music/Pop", "Pop"), library, 0, "c", Random(1))
        assertEquals(listOf("c"), uris(pick))
    }

    @Test
    fun `huge folder is capped for one ring`() {
        val many = List(500) { LibraryTrack("u$it", "Music/$it.mp3") }
        val pick = TrackPicker.pick(SoundSource.Folder("", "All"), LibraryIndex(many), 0, null, Random(1))
        assertEquals(50, pick.tracks.size)
    }
}
