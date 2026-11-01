package dev.rec0il.alarmdhd.sound

import dev.rec0il.alarmdhd.model.LibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private val tracks = listOf(
        LibraryTrack("uri-beatles", "Music/Beatles/Help.mp3"),
        LibraryTrack("uri-queen", "Music/Queen/Bohemian Rhapsody.flac"),
        LibraryTrack("uri-queen-live", "Music/Queen/Live/Bohemian Rhapsody.flac"),
        LibraryTrack("uri-umlaut", "Music/Ärzte/Männer sind Schweine.mp3"),
        LibraryTrack("uri-top", "Music/Top.mp3"),
    )

    private val playlist = "Music/Playlists/morning.m3u"

    private fun resolve(vararg entries: String) = M3uParser.resolve(entries.toList(), playlist, tracks)

    @Test
    fun `reads entries and skips comments and blank lines`() {
        val text = """
            #EXTM3U
            #EXTINF:123,Beatles - Help
            ../Beatles/Help.mp3

            #EXTINF:200,Queen
              ../Queen/Bohemian Rhapsody.flac
        """.trimIndent()
        assertEquals(listOf("../Beatles/Help.mp3", "../Queen/Bohemian Rhapsody.flac"), M3uParser.entries(text))
    }

    @Test
    fun `relative paths resolve from the playlist's own folder`() {
        assertEquals(listOf("uri-beatles"), resolve("../Beatles/Help.mp3"))
    }

    @Test
    fun `relative path with dot segments resolves`() {
        assertEquals(listOf("uri-top"), resolve("./../Top.mp3"))
    }

    @Test
    fun `windows style backslashes work`() {
        assertEquals(listOf("uri-beatles"), resolve("..\\Beatles\\Help.mp3"))
    }

    @Test
    fun `windows drive paths match by the end of the path`() {
        assertEquals(listOf("uri-beatles"), resolve("C:\\Users\\me\\Music\\Beatles\\Help.mp3"))
    }

    @Test
    fun `full device paths match by the end of the path`() {
        assertEquals(listOf("uri-beatles"), resolve("/storage/emulated/0/Music/Beatles/Help.mp3"))
    }

    @Test
    fun `file urls are decoded`() {
        assertEquals(
            listOf("uri-queen"),
            resolve("file:///storage/emulated/0/Music/Queen/Bohemian%20Rhapsody.flac"),
        )
    }

    @Test
    fun `plus sign in a file url stays a plus sign`() {
        val withPlus = listOf(LibraryTrack("uri-plus", "Music/AC+DC.mp3"))
        assertEquals(listOf("uri-plus"), M3uParser.resolve(listOf("file:///x/Music/AC+DC.mp3"), playlist, withPlus))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("uri-beatles"), resolve("../beatles/HELP.MP3"))
    }

    @Test
    fun `same file name picks the one with the longest matching path`() {
        assertEquals(listOf("uri-queen-live"), resolve("/old/phone/Queen/Live/Bohemian Rhapsody.flac"))
        assertEquals(listOf("uri-queen"), resolve("/old/phone/Queen/Bohemian Rhapsody.flac"))
    }

    @Test
    fun `web streams and unknown songs are skipped, keeping playlist order`() {
        val result = resolve(
            "../Queen/Bohemian Rhapsody.flac",
            "https://example.com/stream.mp3",
            "../Nobody/Unknown.mp3",
            "../Beatles/Help.mp3",
        )
        assertEquals(listOf("uri-queen", "uri-beatles"), result)
    }

    @Test
    fun `empty playlist resolves to nothing`() {
        assertTrue(resolve().isEmpty())
    }

    @Test
    fun `utf8 text and its byte order mark are handled`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Ärzte.mp3".toByteArray(Charsets.UTF_8)
        assertEquals("Ärzte.mp3", M3uParser.decode(bytes))
    }

    @Test
    fun `older encodings are read as windows 1252`() {
        val bytes = "Männer.mp3".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Männer.mp3", M3uParser.decode(bytes))
    }

    @Test
    fun `non ascii names match after decoding`() {
        val text = M3uParser.decode("../Ärzte/Männer sind Schweine.mp3".toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("uri-umlaut"), M3uParser.resolve(M3uParser.entries(text), playlist, tracks))
    }
}
