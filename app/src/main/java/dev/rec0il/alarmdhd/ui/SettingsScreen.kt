package dev.rec0il.alarmdhd.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.model.MusicFolder
import dev.rec0il.alarmdhd.model.SoundSource
import dev.rec0il.alarmdhd.sound.MusicLibrary
import kotlinx.coroutines.launch

private const val TEST_DELAY_MS = 10_000L
private const val RUN_MINUTES_STEP = 5
private const val MAX_RUN_MINUTES = 720
private const val KOFI_URL = "https://ko-fi.com/rec0il"
private const val RELEASES_URL = "https://github.com/Rec0iL/AlarmDHD/releases"
private val KOFI_COLOR = Color(0xFFFF5E5B)
private val KOFI_ON_COLOR = Color(0xFF3B0A06)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    folders: List<MusicFolder>,
    runMinutes: Int,
    defaultSound: SoundSource,
    onRunMinutesChange: (Int) -> Unit,
    gamesEnabled: Boolean,
    gamesLocked: Boolean,
    onGamesChange: (Boolean) -> Unit,
    onOpenDefaultSound: () -> Unit,
    onOpenSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by MusicLibrary.index.collectAsStateWithLifecycle()
    val scanning by MusicLibrary.scanning.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MusicLibrary.addFolder(context, uri)
            scope.launch { MusicLibrary.rescan(context) }
        }
    }
    val testStarted = stringResource(R.string.settings_test_started)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_check_setup),
                subtitle = stringResource(R.string.settings_check_setup_hint),
                onClick = onOpenSetup,
            )
            SettingsRow(
                title = stringResource(R.string.settings_test),
                subtitle = stringResource(R.string.settings_test_hint),
                onClick = {
                    AlarmEngine.scheduleTestRing(context, TEST_DELAY_MS)
                    Toast.makeText(context, testStarted, Toast.LENGTH_LONG).show()
                },
            )

            SectionTitle(stringResource(R.string.settings_defaults))
            SettingsRow(
                title = stringResource(R.string.settings_default_sound),
                subtitle = soundTitle(defaultSound),
                onClick = onOpenDefaultSound,
            )
            Stepper(
                label = stringResource(R.string.settings_run_length),
                value = runMinutes,
                valueText = stringResource(R.string.editor_minutes, runMinutes),
                min = RUN_MINUTES_STEP,
                max = MAX_RUN_MINUTES,
                step = RUN_MINUTES_STEP,
                onChange = onRunMinutesChange,
            )
            Text(
                stringResource(R.string.settings_run_length_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.settings_stopping))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_games), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (gamesLocked) R.string.settings_games_locked else R.string.settings_games_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = gamesEnabled, enabled = !gamesLocked, onCheckedChange = onGamesChange)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.settings_music), Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (folders.isEmpty()) {
                Text(
                    stringResource(R.string.sound_add_folder_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${pluralStringResource(R.plurals.sound_song_count, library.tracks.size, library.tracks.size)}, " +
                        pluralStringResource(R.plurals.settings_playlists, library.playlists.size, library.playlists.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (folder in folders) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        MusicLibrary.removeFolder(context, folder)
                        scope.launch { MusicLibrary.rescan(context) }
                    }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.settings_remove_folder))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.sound_add_folder)) }
                if (folders.isNotEmpty()) {
                    OutlinedButton(onClick = { scope.launch { MusicLibrary.rescan(context) } }, enabled = !scanning) {
                        Text(stringResource(R.string.settings_rescan))
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_support))
            Text(
                stringResource(R.string.settings_support_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KofiButton(Modifier.padding(top = 12.dp))

            SectionTitle(stringResource(R.string.settings_about))
            Text(
                stringResource(
                    R.string.settings_version,
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) } },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.settings_releases))
            }

            SectionTitle(stringResource(R.string.disclaimer_title))
            Text(
                stringResource(R.string.disclaimer_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun KofiButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Button(
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) } },
        colors = ButtonDefaults.buttonColors(containerColor = KOFI_COLOR, contentColor = KOFI_ON_COLOR),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(painterResource(R.drawable.ic_coffee), null)
        Spacer(Modifier.size(10.dp))
        Text(stringResource(R.string.settings_kofi), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
