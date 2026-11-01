package dev.rec0il.alarmdhd.ui

import android.Manifest
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.setup.SetupCheck
import dev.rec0il.alarmdhd.setup.SetupChecks
import dev.rec0il.alarmdhd.sound.MusicLibrary
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Welcome : Step
    data object Disclaimer : Step
    data class Check(val check: SetupCheck) : Step
    data object Music : Step
    data object Test : Step
    data object Done : Step
}

private const val TEST_DELAY_MS = 10_000L

/** One step per thing that's wrong, then music and a test. Used on first launch and from settings. */
@Composable
fun SetupWizard(firstRun: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val steps = remember {
        buildList {
            if (firstRun) {
                add(Step.Welcome)
                add(Step.Disclaimer)
            }
            SetupChecks.failing(context).forEach { add(Step.Check(it)) }
            add(Step.Music)
            add(Step.Test)
            add(Step.Done)
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var failing by remember { mutableStateOf(SetupChecks.failing(context)) }
    val step = steps[index]

    fun next() {
        index = (index + 1).coerceAtMost(steps.lastIndex)
    }

    // Coming back from a settings page is what shows whether the fix worked.
    LifecycleResumeEffect(index) {
        failing = SetupChecks.failing(context)
        if (step is Step.Check && step.check != SetupCheck.ALARM_VOLUME && step.check !in failing) next()
        onPauseOrDispose { }
    }

    BackHandler(enabled = index > 0) { index-- }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                Step.Welcome -> Welcome()
                Step.Disclaimer -> StepText(stringResource(R.string.setup_disclaimer_title), stringResource(R.string.disclaimer_text))
                is Step.Check -> CheckStep(step.check, onContinue = { next() }, onSkip = { next() })
                Step.Music -> MusicStep(onContinue = { next() })
                Step.Test -> TestStep(onContinue = { next() })
                Step.Done -> DoneStep(failing)
            }
        }
        when (step) {
            Step.Welcome -> PrimaryAction(stringResource(R.string.setup_start), onClick = { next() })
            Step.Disclaimer -> PrimaryAction(stringResource(R.string.setup_disclaimer_accept), onClick = { next() })
            Step.Done -> PrimaryAction(stringResource(R.string.setup_finish), onClick = onFinished)
            else -> Unit
        }
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(text) }
}

@Composable
private fun StepText(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Welcome() {
    StepText(stringResource(R.string.setup_welcome_title), stringResource(R.string.setup_welcome_text))
    Text(stringResource(R.string.setup_welcome_points), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun CheckStep(check: SetupCheck, onContinue: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    StepText(stringResource(check.title), stringResource(check.why))
    if (check == SetupCheck.ALARM_VOLUME) {
        VolumeControl()
        Spacer(Modifier.height(24.dp))
        PrimaryAction(stringResource(R.string.setup_continue), onClick = onContinue)
        return
    }
    var asked by remember { mutableStateOf(false) }
    // Coming back from the prompt re-checks and moves on by itself, so the result needn't do that too.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
    PrimaryAction(stringResource(check.action), onClick = {
        // Android only shows the permission prompt once or twice. After that, settings is the only way.
        if (check == SetupCheck.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SetupChecks.fixIntent(context, check)?.let { context.startActivity(it) }
        }
    })
    if (check == SetupCheck.OVER_APPS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Text(
            stringResource(R.string.check_over_apps_restricted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = { context.startActivity(SetupChecks.appInfoIntent(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.check_over_apps_app_info))
        }
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.setup_skip))
    }
}

@Composable
private fun VolumeControl() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_ALARM).toFloat()) }
    Slider(
        value = volume,
        onValueChange = {
            volume = it
            audio.setStreamVolume(AudioManager.STREAM_ALARM, it.toInt(), AudioManager.FLAG_PLAY_SOUND)
        },
        valueRange = 0f..max.toFloat(),
        steps = (max - 1).coerceAtLeast(0),
    )
}

@Composable
private fun MusicStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AlarmStore.state.collectAsStateWithLifecycle()
    val library by MusicLibrary.index.collectAsStateWithLifecycle()
    val scanning by MusicLibrary.scanning.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MusicLibrary.addFolder(context, uri)
            scope.launch { MusicLibrary.rescan(context) }
        }
    }
    StepText(stringResource(R.string.setup_music_title), stringResource(R.string.setup_music_text))
    for (folder in state.settings.musicFolders) {
        Text(folder.name, style = MaterialTheme.typography.titleMedium)
    }
    if (state.settings.musicFolders.isNotEmpty()) {
        Text(
            if (scanning) {
                stringResource(R.string.settings_scanning)
            } else {
                pluralStringResource(R.plurals.sound_song_count, library.tracks.size, library.tracks.size)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
    PrimaryAction(stringResource(R.string.sound_add_folder), onClick = { picker.launch(null) })
    TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(if (state.settings.musicFolders.isEmpty()) R.string.setup_skip_music else R.string.setup_continue))
    }
}

@Composable
private fun TestStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    var started by rememberSaveable { mutableStateOf(false) }
    StepText(
        stringResource(R.string.setup_test_title),
        stringResource(if (started) R.string.setup_test_started else R.string.setup_test_text),
    )
    PrimaryAction(stringResource(R.string.setup_test_action), onClick = {
        started = true
        AlarmEngine.scheduleTestRing(context, TEST_DELAY_MS)
    })
    TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(if (started) R.string.setup_continue else R.string.setup_skip))
    }
}

@Composable
private fun DoneStep(failing: List<SetupCheck>) {
    StepText(
        stringResource(if (failing.isEmpty()) R.string.setup_done_title else R.string.setup_done_issues_title),
        stringResource(if (failing.isEmpty()) R.string.setup_done_text else R.string.setup_done_issues_text),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (check in SetupCheck.entries) {
            val ok = check !in failing
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning),
                    null,
                    tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(check.title), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
