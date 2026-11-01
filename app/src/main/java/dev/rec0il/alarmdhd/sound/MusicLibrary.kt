package dev.rec0il.alarmdhd.sound

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.LibraryIndex
import dev.rec0il.alarmdhd.model.LibraryPlaylist
import dev.rec0il.alarmdhd.model.LibraryTrack
import dev.rec0il.alarmdhd.model.MusicFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Scans the added music folders the way a media player would. The result is saved, so a ring
 * can start straight away without walking any folders.
 */
object MusicLibrary {
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mka", "mid", "midi", "amr")
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scanLock = Mutex()
    private lateinit var indexFile: File

    private val mutableIndex = MutableStateFlow(LibraryIndex())
    val index: StateFlow<LibraryIndex> = mutableIndex

    private val mutableScanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = mutableScanning

    fun init(context: Context) {
        indexFile = File(context.createDeviceProtectedStorageContext().filesDir, "library.json")
        try {
            if (indexFile.exists()) mutableIndex.value = json.decodeFromString(indexFile.readText())
        } catch (e: Exception) {
            // Only a cache. Rings fall back to a tone until the next scan rebuilds it.
            Log.e("MusicLibrary", "Couldn't read the saved scan", e)
        }
    }

    fun addFolder(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val folder = MusicFolder(treeUri.toString(), folderName(context, treeUri))
        AlarmStore.update { state ->
            val folders = state.settings.musicFolders.filterNot { it.treeUri == folder.treeUri } + folder
            state.copy(settings = state.settings.copy(musicFolders = folders))
        }
    }

    fun removeFolder(context: Context, folder: MusicFolder) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AlarmStore.update { state ->
            state.copy(settings = state.settings.copy(musicFolders = state.settings.musicFolders - folder))
        }
    }

    private fun folderName(context: Context, treeUri: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = runCatching {
            context.contentResolver.query(document, COLUMNS, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(1) else null
            }
        }.getOrNull()
        return name ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { rootId }
    }

    suspend fun rescan(context: Context) {
        if (scanLock.isLocked) return
        scanLock.withLock {
            mutableScanning.value = true
            try {
                val scanned = withContext(Dispatchers.IO) { scan(context) }
                mutableIndex.value = scanned
                withContext(Dispatchers.IO) { save(scanned) }
            } finally {
                mutableScanning.value = false
            }
        }
    }

    private fun save(scanned: LibraryIndex) {
        // Written to the side and moved into place, so a crash can't leave half a file behind.
        val temporary = File(indexFile.parentFile, "library.tmp")
        temporary.writeText(json.encodeToString(scanned))
        temporary.renameTo(indexFile)
    }

    private fun scan(context: Context): LibraryIndex {
        val tracks = mutableListOf<LibraryTrack>()
        val playlists = mutableListOf<Pair<Uri, String>>()
        for (folder in AlarmStore.state.value.settings.musicFolders) {
            try {
                val tree = Uri.parse(folder.treeUri)
                walk(context, tree, DocumentsContract.getTreeDocumentId(tree), folder.name, tracks, playlists)
            } catch (e: Exception) {
                // Lost access, or an SD card that's out. The other folders are still worth having.
                Log.w("MusicLibrary", "Couldn't scan ${folder.name}", e)
            }
        }
        tracks.sortBy { it.path.lowercase() }
        val parsed = playlists.mapNotNull { (uri, path) -> readPlaylist(context, uri, path, tracks) }
        return LibraryIndex(tracks, parsed.sortedBy { it.path.lowercase() })
    }

    private fun walk(
        context: Context,
        tree: Uri,
        documentId: String,
        path: String,
        tracks: MutableList<LibraryTrack>,
        playlists: MutableList<Pair<Uri, String>>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val folders = mutableListOf<Pair<String, String>>()
        val foundTracks = mutableListOf<LibraryTrack>()
        var hasNoMedia = false
        context.contentResolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val extension = name.substringAfterLast('.', "").lowercase()
                when {
                    name == ".nomedia" -> hasNoMedia = true
                    name.startsWith(".") -> Unit
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> folders += id to name
                    extension in PLAYLIST_EXTENSIONS ->
                        playlists += DocumentsContract.buildDocumentUriUsingTree(tree, id) to "$path/$name"
                    extension in AUDIO_EXTENSIONS || mime.startsWith("audio/") ->
                        foundTracks += LibraryTrack(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "$path/$name")
                }
            }
        }
        // A media player skips folders marked .nomedia, usually app sound effects, and so does this.
        if (!hasNoMedia) tracks += foundTracks
        for ((id, name) in folders) walk(context, tree, id, "$path/$name", tracks, playlists)
    }

    private fun readPlaylist(context: Context, uri: Uri, path: String, tracks: List<LibraryTrack>): LibraryPlaylist? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val entries = M3uParser.entries(M3uParser.decode(bytes))
        LibraryPlaylist(uri.toString(), path, M3uParser.resolve(entries, path, tracks), entries.size)
    } catch (e: Exception) {
        Log.w("MusicLibrary", "Couldn't read $path", e)
        null
    }
}
