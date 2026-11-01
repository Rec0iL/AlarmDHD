package dev.rec0il.alarmdhd.sound

import dev.rec0il.alarmdhd.model.LibraryIndex
import dev.rec0il.alarmdhd.model.LibraryTrack
import dev.rec0il.alarmdhd.model.SoundSource
import kotlin.random.Random

/** The tracks to try for one ring, plus what to remember so the next ring plays something else. */
data class TrackPick(
    val tracks: List<SoundTrack.File>,
    val nextCursor: Int? = null,
    val randomPick: String? = null,
)

object TrackPicker {
    /** More than a ring could ever get through, so a huge folder isn't all carried into one ring. */
    private const val MAX_RANDOM_TRACKS = 50

    fun pick(
        source: SoundSource,
        library: LibraryIndex,
        cursor: Int,
        lastRandom: String?,
        random: Random = Random.Default,
    ): TrackPick = when (source) {
        SoundSource.DefaultAlarm -> TrackPick(emptyList())
        is SoundSource.Tone -> TrackPick(listOf(SoundTrack.File(source.uri, source.title)))
        is SoundSource.Song -> TrackPick(listOf(SoundTrack.File(source.uri, source.title)))
        is SoundSource.Playlist -> pickPlaylist(source, library, cursor, lastRandom, random)
        // Not checked against the library: a deleted song fails to start, and SoundChain moves on and reports it.
        is SoundSource.PickedSongs -> continueOrShuffle(
            source.songs.map { SoundTrack.File(it.uri, it.title) },
            source.shuffle,
            cursor,
            lastRandom,
            random,
        )
        is SoundSource.Folder -> {
            val prefix = if (source.pathPrefix.isEmpty()) "" else "${source.pathPrefix}/"
            shuffled(library.tracks.filter { it.path.startsWith(prefix) }.map(::toFile), lastRandom, random)
        }
    }

    private fun pickPlaylist(
        source: SoundSource.Playlist,
        library: LibraryIndex,
        cursor: Int,
        lastRandom: String?,
        random: Random,
    ): TrackPick {
        val playlist = library.playlists.find { it.uri == source.uri } ?: return TrackPick(emptyList())
        val byUri = library.tracks.associateBy { it.uri }
        val tracks = playlist.trackUris.mapNotNull { byUri[it] }.map(::toFile)
        return continueOrShuffle(tracks, source.shuffle, cursor, lastRandom, random)
    }

    private fun continueOrShuffle(
        tracks: List<SoundTrack.File>,
        shuffle: Boolean,
        cursor: Int,
        lastRandom: String?,
        random: Random,
    ): TrackPick {
        if (shuffle) return shuffled(tracks, lastRandom, random)
        if (tracks.isEmpty()) return TrackPick(emptyList())
        val start = cursor.mod(tracks.size)
        return TrackPick(tracks.drop(start) + tracks.take(start), nextCursor = start + 1)
    }

    private fun shuffled(tracks: List<SoundTrack.File>, lastRandom: String?, random: Random): TrackPick {
        val order = tracks.shuffled(random).toMutableList()
        // Never the same song twice in a row, unless it's the only one.
        if (order.size > 1 && order.first().uri == lastRandom) order.add(order.removeAt(0))
        val chosen = order.take(MAX_RANDOM_TRACKS)
        return TrackPick(chosen, randomPick = chosen.firstOrNull()?.uri)
    }

    private fun toFile(track: LibraryTrack) = SoundTrack.File(track.uri, track.title)
}
