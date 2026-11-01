package dev.rec0il.alarmdhd.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A run of alarms: rings every [intervalMinutes] from [startMinute] until [endMinute] or the game is won. */
@Serializable
data class AlarmSeries(
    val id: String,
    val label: String = "",
    val startMinute: Int,
    /** Minutes since midnight. Null means open end. */
    val endMinute: Int? = null,
    val intervalMinutes: Int = 5,
    val snoozeMinutes: Int = 7,
    /** ISO weekday numbers, 1 = Monday. Empty means once. */
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val ringSeconds: Int = 120,
    val sound: SoundSource = SoundSource.DefaultAlarm,
    /** Each ring starts quiet and reaches full volume after about 30 seconds. */
    val gradualVolume: Boolean = false,
    /** Sessions that start at or before this instant never ring, so a new run can't go off mid-window. */
    val armedAt: Long = 0,
) {
    val isOpenEnd get() = endMinute == null
    val isOnce get() = days.isEmpty()
}

@Serializable
sealed interface SoundSource {
    /** On a run: whatever [Settings.defaultSound] is. As [Settings.defaultSound] itself: the system alarm tone. */
    @Serializable
    @SerialName("default")
    data object DefaultAlarm : SoundSource

    /** A system alarm tone from the ringtone picker. */
    @Serializable
    @SerialName("tone")
    data class Tone(val uri: String, val title: String) : SoundSource

    @Serializable
    @SerialName("song")
    data class Song(val uri: String, val title: String) : SoundSource

    /** An .m3u from the scanned music folder, looked up in the library by [uri] at ring time. */
    @Serializable
    @SerialName("playlist")
    data class Playlist(val uri: String, val title: String, val shuffle: Boolean = false) : SoundSource

    /** Songs picked in the app for this run, for when there's no .m3u. Saved here, so a lost scan can't lose them. */
    @Serializable
    @SerialName("picked")
    data class PickedSongs(val songs: List<Song>, val shuffle: Boolean = false) : SoundSource

    /** Every scanned song under [pathPrefix]; an empty prefix means the whole library. */
    @Serializable
    @SerialName("folder")
    data class Folder(val pathPrefix: String, val title: String) : SoundSource
}

@Serializable
data class Snooze(val seriesId: String, val fireAt: Long)

@Serializable
data class MusicFolder(val treeUri: String, val name: String)

/** What the user configured. Kept apart from [LiveState] so a future backup can save only this. */
@Serializable
data class Settings(
    val series: List<AlarmSeries> = emptyList(),
    val musicFolders: List<MusicFolder> = emptyList(),
    val setupDone: Boolean = false,
    /** How long after its start a new run ends, until the user picks a different end. */
    val defaultRunMinutes: Int = 30,
    val defaultSound: SoundSource = SoundSource.DefaultAlarm,
) {
    /** What a run's sound setting actually plays. */
    fun resolve(sound: SoundSource): SoundSource = if (sound == SoundSource.DefaultAlarm) defaultSound else sound
}

@Serializable
data class SoundIssue(val failedTitle: String, val failedCount: Int, val usedFallback: Boolean)

/** What is happening right now. Never backed up, so a restored phone can't ring old snoozes. */
@Serializable
data class LiveState(
    val snoozes: List<Snooze> = emptyList(),
    val lastRingAt: Long = 0,
    /** Series id to the epoch day of the last session the user ended by winning the game. */
    val stoppedThroughDay: Map<String, Long> = emptyMap(),
    /** Series id to the epoch day of a session the user chose to skip. */
    val skippedDay: Map<String, Long> = emptyMap(),
    val trackCursors: Map<String, Int> = emptyMap(),
    val lastRandomTrack: Map<String, String> = emptyMap(),
    val soundIssues: Map<String, SoundIssue> = emptyMap(),
    val lastGameId: String? = null,
)

@Serializable
data class AppState(
    val settings: Settings = Settings(),
    val live: LiveState = LiveState(),
)

/** A song found in a music folder. [path] starts with the folder's name, like "Music/Album/song.mp3". */
@Serializable
data class LibraryTrack(val uri: String, val path: String) {
    val title get() = path.substringAfterLast('/').substringBeforeLast('.')
}

/** An .m3u found in a music folder. [totalEntries] minus the found tracks is what couldn't be matched. */
@Serializable
data class LibraryPlaylist(val uri: String, val path: String, val trackUris: List<String>, val totalEntries: Int) {
    val title get() = path.substringAfterLast('/').substringBeforeLast('.')
}

@Serializable
data class LibraryIndex(
    val tracks: List<LibraryTrack> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
)
