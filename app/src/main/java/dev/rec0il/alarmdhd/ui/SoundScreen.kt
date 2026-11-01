package dev.rec0il.alarmdhd.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.LibraryIndex
import dev.rec0il.alarmdhd.model.LibraryTrack
import dev.rec0il.alarmdhd.model.SoundIssue
import dev.rec0il.alarmdhd.model.SoundSource
import dev.rec0il.alarmdhd.sound.ChainStatus
import dev.rec0il.alarmdhd.sound.MusicLibrary
import dev.rec0il.alarmdhd.sound.SoundPlayer
import dev.rec0il.alarmdhd.sound.SoundTrack
import kotlinx.coroutines.launch

@Composable
fun soundTitle(source: SoundSource): String = when (source) {
    SoundSource.DefaultAlarm -> stringResource(R.string.sound_system)
    is SoundSource.Tone -> source.title
    is SoundSource.Song -> source.title
    is SoundSource.Playlist -> source.title
    is SoundSource.PickedSongs -> pluralStringResource(R.plurals.sound_picked_title, source.songs.size, source.songs.size)
    is SoundSource.Folder -> source.title
}

/** A run's sound, saying what the default currently is when the run is left on it. */
@Composable
fun runSoundTitle(source: SoundSource, defaultSound: SoundSource): String =
    if (source == SoundSource.DefaultAlarm) {
        stringResource(R.string.sound_run_default_title, soundTitle(defaultSound))
    } else {
        soundTitle(source)
    }

@Composable
fun soundIssueText(issue: SoundIssue): String = when {
    issue.failedCount == 0 -> stringResource(R.string.sound_issue_empty)
    issue.usedFallback -> stringResource(R.string.sound_issue_fallback, issue.failedTitle)
    else -> stringResource(R.string.sound_issue_skipped, issue.failedTitle)
}

private enum class MusicTab(val title: Int) {
    SONGS(R.string.sound_tab_songs),
    PLAYLISTS(R.string.sound_tab_playlists),
    FOLDERS(R.string.sound_tab_folders),
}

/** Picks a run's sound, or with no [runDefault], the default sound that runs fall back on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundScreen(current: SoundSource, runDefault: SoundSource?, onChange: (SoundSource) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AlarmStore.state.collectAsStateWithLifecycle()
    val library by MusicLibrary.index.collectAsStateWithLifecycle()
    val scanning by MusicLibrary.scanning.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var pickingSongs by rememberSaveable { mutableStateOf(false) }
    // Kept for this visit, so a stray tap on another sound doesn't lose a long list of picks.
    var lastPicks by remember { mutableStateOf((current as? SoundSource.PickedSongs)?.songs.orEmpty()) }

    LaunchedEffect(Unit) { MusicLibrary.rescan(context) }

    var previewStatus by remember { mutableStateOf<ChainStatus?>(null) }
    var previewing by remember { mutableStateOf(false) }
    // The song whose own button is playing, as opposed to the Test button above.
    var previewingSong by remember { mutableStateOf<String?>(null) }
    val player = remember { SoundPlayer(context) { previewStatus = it } }
    fun stopPreview() {
        player.stop()
        previewing = false
        previewingSong = null
        previewStatus = null
    }
    // Also when the app is left, so nothing keeps playing with no button on screen to stop it.
    LifecycleStartEffect(Unit) { onStopOrDispose { stopPreview() } }

    fun stopSongPreview() {
        if (previewingSong != null) stopPreview()
    }

    fun toggleSongPreview(uri: String, title: String) {
        val wasPlaying = previewingSong == uri
        stopPreview()
        if (!wasPlaying) {
            previewingSong = uri
            player.previewSong(uri, title)
        }
    }

    val previewFailed = stringResource(R.string.sound_preview_failed)
    LaunchedEffect(previewStatus) {
        if (previewingSong != null && previewStatus != null && previewStatus?.playing == null) {
            stopPreview()
            Toast.makeText(context, previewFailed, Toast.LENGTH_SHORT).show()
        }
    }

    fun choose(source: SoundSource) {
        // Choosing the song being heard lets it play on. Anything else ends it.
        if (previewingSong == null || (source as? SoundSource.Song)?.uri != previewingSong) stopPreview()
        onChange(source)
    }

    fun closeSongPicker() {
        stopPreview()
        pickingSongs = false
    }

    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val title = RingtoneManager.getRingtone(context, uri)?.getTitle(context).orEmpty()
                choose(SoundSource.Tone(uri.toString(), title))
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            choose(SoundSource.Song(uri.toString(), displayName(context, uri)))
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MusicLibrary.addFolder(context, uri)
            scope.launch { MusicLibrary.rescan(context) }
        }
    }

    if (pickingSongs) {
        BackHandler { closeSongPicker() }
        SongPicker(
            initial = lastPicks,
            library = library,
            previewingSong = previewingSong,
            onTogglePreview = ::toggleSongPreview,
            onStopPreview = ::stopSongPreview,
            onSave = { songs ->
                lastPicks = songs
                choose(SoundSource.PickedSongs(songs, (current as? SoundSource.PickedSongs)?.shuffle == true))
                closeSongPicker()
            },
            onBack = ::closeSongPicker,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(if (runDefault == null) R.string.settings_default_sound else R.string.sound_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                PreviewCard(
                    title = if (runDefault == null) soundTitle(current) else runSoundTitle(current, runDefault),
                    status = previewStatus,
                    previewing = previewing,
                    titleOf = player::titleOf,
                    onToggle = {
                        val wasPlaying = previewing
                        stopPreview()
                        if (!wasPlaying) {
                            previewing = true
                            player.start(current, seriesId = null)
                        }
                    },
                )
            }
            item {
                SoundRow(
                    title = stringResource(if (runDefault == null) R.string.sound_system else R.string.sound_run_default),
                    subtitle = runDefault?.let { soundTitle(it) },
                    selected = current == SoundSource.DefaultAlarm,
                    onClick = { choose(SoundSource.DefaultAlarm) },
                )
                SoundRow(
                    title = stringResource(R.string.sound_tone),
                    subtitle = (current as? SoundSource.Tone)?.title,
                    selected = current is SoundSource.Tone,
                    onClick = {
                        tonePicker.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false),
                        )
                    },
                )
                SoundRow(
                    title = stringResource(R.string.sound_file),
                    subtitle = (current as? SoundSource.Song)?.title,
                    selected = current is SoundSource.Song,
                    onClick = { filePicker.launch(arrayOf("audio/*")) },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.sound_your_music), Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (state.settings.musicFolders.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sound_add_folder_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.sound_add_folder))
                    }
                }
            } else {
                item {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        MusicTab.entries.forEachIndexed { index, musicTab ->
                            Tab(
                                selected = tab == index,
                                onClick = {
                                    stopSongPreview()
                                    tab = index
                                },
                                text = { Text(stringResource(musicTab.title)) },
                            )
                        }
                    }
                }
                when (MusicTab.entries[tab]) {
                    MusicTab.SONGS -> {
                        item {
                            OutlinedTextField(
                                value = search,
                                onValueChange = {
                                    stopSongPreview()
                                    search = it
                                },
                                placeholder = { Text(stringResource(R.string.sound_search, library.tracks.size)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                        items(matching(library, search), key = { "song:" + it.uri }) { track ->
                            SoundRow(
                                title = track.title,
                                subtitle = track.path.substringBeforeLast('/'),
                                selected = (current as? SoundSource.Song)?.uri == track.uri,
                                onClick = { choose(SoundSource.Song(track.uri, track.title)) },
                                trailing = {
                                    SongPreviewButton(track.title, previewingSong == track.uri) {
                                        toggleSongPreview(track.uri, track.title)
                                    }
                                },
                            )
                        }
                    }
                    MusicTab.PLAYLISTS -> {
                        item {
                            SoundRow(
                                title = stringResource(R.string.sound_picked),
                                subtitle = if (current is SoundSource.PickedSongs) {
                                    pluralStringResource(R.plurals.sound_song_count, current.songs.size, current.songs.size)
                                } else {
                                    stringResource(R.string.sound_picked_hint)
                                },
                                selected = current is SoundSource.PickedSongs,
                                onClick = {
                                    stopPreview()
                                    pickingSongs = true
                                },
                            )
                        }
                        if (current is SoundSource.PickedSongs) {
                            item { ShuffleRow(current.shuffle) { choose(current.copy(shuffle = it)) } }
                        }
                        if (library.playlists.isEmpty()) {
                            item { EmptyHint(stringResource(R.string.sound_no_playlists)) }
                        }
                        items(library.playlists, key = { "playlist:" + it.uri }) { playlist ->
                            val selected = current as? SoundSource.Playlist
                            SoundRow(
                                title = playlist.title,
                                subtitle = stringResource(R.string.sound_playlist_found, playlist.trackUris.size, playlist.totalEntries),
                                selected = selected?.uri == playlist.uri,
                                onClick = { choose(SoundSource.Playlist(playlist.uri, playlist.title, selected?.shuffle == true)) },
                            )
                        }
                        if (current is SoundSource.Playlist) {
                            item { ShuffleRow(current.shuffle) { choose(current.copy(shuffle = it)) } }
                        }
                    }
                    MusicTab.FOLDERS -> {
                        item {
                            val allMusic = stringResource(R.string.sound_all_music)
                            SoundRow(
                                title = allMusic,
                                subtitle = pluralStringResource(R.plurals.sound_song_count, library.tracks.size, library.tracks.size),
                                selected = (current as? SoundSource.Folder)?.pathPrefix == "",
                                onClick = { choose(SoundSource.Folder("", allMusic)) },
                            )
                        }
                        val folders = foldersOf(library)
                        items(folders, key = { "folder:" + it.first }) { (path, count) ->
                            val title = path.substringAfterLast('/')
                            val parent = path.substringBeforeLast('/', "")
                            val songs = pluralStringResource(R.plurals.sound_song_count, count, count)
                            SoundRow(
                                title = title,
                                subtitle = if (parent.isEmpty()) songs else "$parent · $songs",
                                selected = (current as? SoundSource.Folder)?.pathPrefix == path,
                                onClick = { choose(SoundSource.Folder(path, title)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun matching(library: LibraryIndex, search: String): List<LibraryTrack> {
    val query = search.trim().lowercase()
    return if (query.isEmpty()) library.tracks else library.tracks.filter { it.path.lowercase().contains(query) }
}

/** Ticks songs for a run's own playlist. They play in the order they were ticked, which the numbers show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPicker(
    initial: List<SoundSource.Song>,
    library: LibraryIndex,
    previewingSong: String?,
    onTogglePreview: (uri: String, title: String) -> Unit,
    onStopPreview: () -> Unit,
    onSave: (List<SoundSource.Song>) -> Unit,
    onBack: () -> Unit,
) {
    var picked by rememberSaveable { mutableStateOf(initial.map { it.uri }) }
    var search by rememberSaveable { mutableStateOf("") }
    val inLibrary = remember(library) { library.tracks.associateBy { it.uri } }
    val initialByUri = remember(initial) { initial.associateBy { it.uri } }
    fun toggle(uri: String) {
        // Ticking the song being heard lets it play on. Pressing any other song ends it.
        if (uri != previewingSong) onStopPreview()
        picked = if (uri in picked) picked - uri else picked + uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.sound_pick_title)) },
                actions = {
                    TextButton(
                        enabled = picked.isNotEmpty(),
                        onClick = {
                            onSave(
                                picked.mapNotNull { uri ->
                                    initialByUri[uri] ?: inLibrary[uri]?.let { SoundSource.Song(it.uri, it.title) }
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = {
                        onStopPreview()
                        search = it
                    },
                    placeholder = { Text(stringResource(R.string.sound_search, library.tracks.size)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text(
                    pluralStringResource(R.plurals.sound_song_count, picked.size, picked.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Songs that have gone missing stay listed, so they can be unticked instead of being kept invisibly.
            items(initial.filter { it.uri !in inLibrary }, key = { "missing:" + it.uri }) { song ->
                PickRow(
                    title = song.title,
                    subtitle = stringResource(R.string.sound_pick_missing),
                    position = picked.indexOf(song.uri),
                    onToggle = { toggle(song.uri) },
                )
            }
            items(matching(library, search), key = { "song:" + it.uri }) { track ->
                PickRow(
                    title = track.title,
                    subtitle = track.path.substringBeforeLast('/'),
                    position = picked.indexOf(track.uri),
                    onToggle = { toggle(track.uri) },
                    trailing = {
                        SongPreviewButton(track.title, previewingSong == track.uri) {
                            onTogglePreview(track.uri, track.title)
                        }
                    },
                )
            }
        }
    }
}

/** [position] is the song's place in the playlist from 0, or -1 if it isn't ticked. */
@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    position: Int,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ticked = position >= 0
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(vertical = 8.dp),
        ) {
            Checkbox(checked = ticked, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ticked) {
                Text("${position + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        trailing?.invoke()
    }
}

/** Plays a song so it can be heard before choosing it. The same button then stops it. */
@Composable
private fun SongPreviewButton(title: String, playing: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
            stringResource(if (playing) R.string.sound_preview_stop else R.string.sound_preview_play, title),
            tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShuffleRow(shuffle: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(R.string.sound_shuffle), modifier = Modifier.weight(1f))
        Switch(checked = shuffle, onCheckedChange = onChange)
    }
}

/** Every folder that holds songs, directly or further down, with how many. */
private fun foldersOf(library: LibraryIndex): List<Pair<String, Int>> {
    val counts = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
    for (track in library.tracks) {
        var folder = track.path.substringBeforeLast('/', "")
        while (folder.isNotEmpty()) {
            counts[folder] = (counts[folder] ?: 0) + 1
            folder = folder.substringBeforeLast('/', "")
        }
    }
    return counts.map { it.key to it.value }
}

private fun displayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return name?.substringBeforeLast('.') ?: uri.lastPathSegment.orEmpty()
}

@Composable
private fun PreviewCard(
    title: String,
    status: ChainStatus?,
    previewing: Boolean,
    titleOf: (SoundTrack?) -> String?,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (previewing && status != null) {
                    val playing = titleOf(status.playing)
                    Text(
                        when {
                            playing == null -> stringResource(R.string.sound_test_silent)
                            status.usedFallback -> stringResource(R.string.sound_test_fallback, playing)
                            status.failed.isNotEmpty() -> stringResource(R.string.sound_test_skipped, playing)
                            else -> stringResource(R.string.ring_now_playing, playing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.usedFallback || status.playing == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = onToggle) {
                Icon(painterResource(if (previewing) R.drawable.ic_stop else R.drawable.ic_play), null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(if (previewing) R.string.sound_test_stop else R.string.sound_test))
            }
        }
    }
}

@Composable
private fun SoundRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}
