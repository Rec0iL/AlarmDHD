package dev.rec0il.alarmdhd.sound

import dev.rec0il.alarmdhd.model.LibraryTrack
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Reads .m3u and .m3u8 playlists and matches their entries to songs found in the music folder. */
object M3uParser {
    private val OTHER_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+://.*")
    private val WINDOWS_DRIVE = Regex("^[a-zA-Z]:/.*")
    private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

    /** Old .m3u files are often not UTF-8, so anything that isn't valid UTF-8 is read as Windows-1252. */
    fun decode(bytes: ByteArray): String {
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix(BYTE_ORDER_MARK)
    }

    fun entries(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    /** Returns the uri of each entry that could be matched, in playlist order. */
    fun resolve(entries: List<String>, playlistPath: String, tracks: List<LibraryTrack>): List<String> {
        val byPath = tracks.associateBy { it.path.lowercase() }
        val byName = tracks.groupBy { it.path.substringAfterLast('/').lowercase() }
        val playlistDir = playlistPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        return entries.mapNotNull { match(it, playlistDir, byPath, byName)?.uri }
    }

    private fun match(
        entry: String,
        playlistDir: List<String>,
        byPath: Map<String, LibraryTrack>,
        byName: Map<String, List<LibraryTrack>>,
    ): LibraryTrack? {
        val (segments, isAbsolute) = parse(entry) ?: return null
        if (!isAbsolute) {
            val path = collapse(playlistDir + segments).joinToString("/").lowercase()
            byPath[path]?.let { return it }
        }
        // Full paths come from wherever the playlist was made, so match on the end of the path.
        val wanted = collapse(segments).map { it.lowercase() }
        val candidates = byName[wanted.lastOrNull() ?: return null] ?: return null
        return candidates.maxByOrNull { commonEnding(it.path.lowercase().split('/'), wanted) }
    }

    private fun parse(entry: String): Pair<List<String>, Boolean>? {
        var path = entry
        var isAbsolute = false
        if (path.startsWith("file:", ignoreCase = true)) {
            path = URLDecoder.decode(path.substring(5).replace("+", "%2B"), "UTF-8")
            isAbsolute = true
        } else if (OTHER_SCHEME.matches(path)) {
            return null
        }
        path = path.replace('\\', '/')
        if (WINDOWS_DRIVE.matches(path)) {
            path = path.substring(2)
            isAbsolute = true
        }
        if (path.startsWith("/")) isAbsolute = true
        return path.split('/').filter { it.isNotEmpty() } to isAbsolute
    }

    private fun collapse(segments: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                "." -> Unit
                ".." -> result.removeLastOrNull()
                else -> result += segment
            }
        }
        return result
    }

    private fun commonEnding(a: List<String>, b: List<String>): Int {
        var count = 0
        while (count < a.size && count < b.size && a[a.size - 1 - count] == b[b.size - 1 - count]) count++
        return count
    }
}
