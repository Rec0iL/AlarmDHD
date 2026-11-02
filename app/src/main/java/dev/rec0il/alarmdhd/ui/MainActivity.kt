package dev.rec0il.alarmdhd.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.alarm.RingService
import dev.rec0il.alarmdhd.alarm.RingState
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.setup.SetupChecks
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private sealed interface Screen {
    @Serializable
    @SerialName("home")
    data object Home : Screen

    @Serializable
    @SerialName("editor")
    data class Editor(val draft: AlarmSeries, val isNew: Boolean) : Screen

    @Serializable
    @SerialName("sound")
    data object Sound : Screen

    @Serializable
    @SerialName("default_sound")
    data object DefaultSound : Screen

    @Serializable
    @SerialName("settings")
    data object Settings : Screen

    @Serializable
    @SerialName("setup")
    data object Setup : Screen
}

private val navJson = Json { ignoreUnknownKeys = true }

private val stackSaver = Saver<MutableState<List<Screen>>, String>(
    save = { navJson.encodeToString(it.value) },
    restore = { mutableStateOf(navJson.decodeFromString<List<Screen>>(it)) },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        setContent {
            AlarmTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AppContent()
                }
            }
        }
    }
}

@Composable
private fun AppContent() {
    val context = LocalContext.current
    val state by AlarmStore.state.collectAsStateWithLifecycle()
    val ring by RingState.active.collectAsStateWithLifecycle()
    var stack by rememberSaveable(saver = stackSaver) { mutableStateOf(listOf<Screen>(Screen.Home)) }
    var problems by remember { mutableStateOf(SetupChecks.failing(context)) }
    // Restarted by any state change, so "next alarm" never shows one that has just gone off.
    val now by produceState(AlarmEngine.now(), state) {
        while (true) {
            value = AlarmEngine.now()
            delay(15_000)
        }
    }

    // Also heals anything lost while the app wasn't running, like alarms cleared by a force-stop.
    LifecycleResumeEffect(Unit) {
        problems = SetupChecks.failing(context)
        AlarmEngine.reschedule(context)
        onPauseOrDispose { }
    }

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop() {
        stack = stack.dropLast(1)
    }

    if (!state.settings.setupDone) {
        SetupWizard(
            firstRun = true,
            onFinished = {
                AlarmStore.update { it.copy(settings = it.settings.copy(setupDone = true)) }
                problems = SetupChecks.failing(context)
            },
        )
        return
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        Screen.Home -> HomeScreen(
            state = state,
            now = now,
            problems = problems,
            onAdd = { push(Screen.Editor(Planner.newSeries(AlarmEngine.now(), state.settings.defaultRunMinutes), isNew = true)) },
            onEdit = { push(Screen.Editor(it, isNew = false)) },
            onToggle = { series, enabled -> AlarmEngine.setEnabled(context, series.id, enabled) },
            onSkip = { series, day -> AlarmEngine.skip(context, series.id, day) },
            onUndoSkip = { AlarmEngine.undoSkip(context, it.id) },
            onStopToday = { RingService.beginGame(context) },
            onOpenSettings = { push(Screen.Settings) },
            onOpenSetup = { push(Screen.Setup) },
        )
        is Screen.Editor -> EditorScreen(
            draft = screen.draft,
            isNew = screen.isNew,
            locked = !screen.isNew && state.settings.series.find { it.id == screen.draft.id }
                ?.let { Planner.isRunning(state, it, now, AlarmEngine.zone()) } == true,
            runMinutes = state.settings.defaultRunMinutes,
            defaultSound = state.settings.defaultSound,
            gamesEnabled = state.settings.gamesEnabled,
            onChange = { draft -> stack = stack.dropLast(1) + screen.copy(draft = draft) },
            onSave = {
                AlarmEngine.saveSeries(context, screen.draft)
                pop()
            },
            onDelete = {
                AlarmEngine.deleteSeries(context, screen.draft.id)
                pop()
            },
            onOpenSound = { push(Screen.Sound) },
            onBack = { pop() },
        )
        Screen.Sound -> {
            val editorIndex = stack.lastIndex - 1
            val editor = stack[editorIndex] as Screen.Editor
            SoundScreen(
                current = editor.draft.sound,
                runDefault = state.settings.defaultSound,
                onChange = { sound ->
                    val updated = editor.copy(draft = editor.draft.copy(sound = sound))
                    stack = stack.toMutableList().also { it[editorIndex] = updated }
                },
                onBack = { pop() },
            )
        }
        Screen.DefaultSound -> SoundScreen(
            current = state.settings.defaultSound,
            runDefault = null,
            onChange = { sound -> AlarmStore.update { it.copy(settings = it.settings.copy(defaultSound = sound)) } },
            onBack = { pop() },
        )
        Screen.Settings -> SettingsScreen(
            folders = state.settings.musicFolders,
            runMinutes = state.settings.defaultRunMinutes,
            defaultSound = state.settings.defaultSound,
            onRunMinutesChange = { minutes ->
                AlarmStore.update { it.copy(settings = it.settings.copy(defaultRunMinutes = minutes)) }
            },
            gamesEnabled = state.settings.gamesEnabled,
            // Like the editor's lock: swapping the game for one tap mid-run would be an easy way back to sleep.
            gamesLocked = state.settings.gamesEnabled &&
                (ring != null || Planner.progress(state, now, AlarmEngine.zone()) != null),
            onGamesChange = { enabled ->
                AlarmStore.update { it.copy(settings = it.settings.copy(gamesEnabled = enabled)) }
            },
            onOpenDefaultSound = { push(Screen.DefaultSound) },
            onOpenSetup = { push(Screen.Setup) },
            onBack = { pop() },
        )
        Screen.Setup -> SetupWizard(firstRun = false, onFinished = { pop() })
    }
}
