package dev.rec0il.alarmdhd.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.AppState
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.model.Progress
import dev.rec0il.alarmdhd.model.SoundSource
import dev.rec0il.alarmdhd.setup.SetupCheck
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppState,
    now: Long,
    problems: List<SetupCheck>,
    onAdd: () -> Unit,
    onEdit: (AlarmSeries) -> Unit,
    onToggle: (AlarmSeries, Boolean) -> Unit,
    onSkip: (AlarmSeries, LocalDate) -> Unit,
    onUndoSkip: (AlarmSeries) -> Unit,
    onStopToday: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val zone = AlarmEngine.zone()
    val progress = Planner.progress(state, now, zone)
    val due = Planner.nextDue(state, now, zone)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { AppLogo(Modifier.padding(start = 12.dp, end = 4.dp)) },
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text(stringResource(R.string.home_new)) },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (problems.isNotEmpty()) {
                item { ProblemBanner(problems.size, onOpenSetup) }
            }
            if (progress != null) {
                item { RunningCard(progress, now, onStopToday) }
            } else if (due != null) {
                item { NextAlarm(due.at, now) }
            }
            if (state.settings.series.isEmpty()) {
                item { EmptyState(state.settings.gamesEnabled) }
            }
            items(state.settings.series, key = { it.id }) { series ->
                SeriesCard(
                    series = series,
                    state = state,
                    now = now,
                    onClick = { onEdit(series) },
                    onToggle = { onToggle(series, it) },
                    onSkip = { onSkip(series, it) },
                    onUndoSkip = { onUndoSkip(series) },
                )
            }
        }
    }
}

/** The app icon. Compose can't draw an adaptive icon directly, so this rebuilds it from its two layers. */
@Composable
private fun AppLogo(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.4f),
        )
    }
}

@Composable
private fun ProblemBanner(count: Int, onFix: () -> Unit) {
    Card(
        onClick = onFix,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_warning), null)
            Spacer(Modifier.width(12.dp))
            Text(pluralStringResource(R.plurals.home_problems, count, count), modifier = Modifier.weight(1f))
            Text(stringResource(R.string.action_fix), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RunningCard(progress: Progress, now: Long, onStopToday: () -> Unit) {
    val context = LocalContext.current
    val nextAt = progress.nextAt
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_running), style = MaterialTheme.typography.labelLarge)
            if (nextAt != null) {
                Text(
                    stringResource(R.string.home_next_in, Format.time(context, nextAt), Format.until(context, now, nextAt)),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(Format.progressLine(context, progress), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStopToday, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.home_stop_today))
            }
        }
    }
}

@Composable
private fun NextAlarm(at: Long, now: Long) {
    val context = LocalContext.current
    val zone = AlarmEngine.zone()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            stringResource(R.string.home_next_alarm),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${Format.dayName(context, day, today)} ${Format.time(context, at)}",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            Format.until(context, now, at),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(gamesEnabled: Boolean) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 32.dp)) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (gamesEnabled) R.string.home_empty_text else R.string.home_empty_text_no_game),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeriesCard(
    series: AlarmSeries,
    state: AppState,
    now: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkip: (LocalDate) -> Unit,
    onUndoSkip: () -> Unit,
) {
    val context = LocalContext.current
    val zone = AlarmEngine.zone()
    val running = Planner.isRunning(state, series, now, zone)
    val dim = if (series.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val detail = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (series.label.isNotBlank()) {
                        Text(series.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(Format.seriesRange(context, series), style = MaterialTheme.typography.headlineSmall, color = dim)
                }
                Switch(checked = series.enabled, onCheckedChange = onToggle, enabled = !running)
            }
            Spacer(Modifier.height(4.dp))
            Text(Format.seriesDetail(context, series), style = MaterialTheme.typography.bodyMedium, color = detail)
            Text(Format.seriesDays(context, series), style = MaterialTheme.typography.bodyMedium, color = detail)
            if (series.sound != SoundSource.DefaultAlarm) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_music), null, Modifier.size(16.dp), tint = detail)
                    Spacer(Modifier.width(6.dp))
                    Text(soundTitle(series.sound), style = MaterialTheme.typography.bodyMedium, color = detail)
                }
            }
            state.live.soundIssues[series.id]?.let {
                Text(
                    soundIssueText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (running) {
                Text(
                    stringResource(if (state.settings.gamesEnabled) R.string.home_locked else R.string.home_locked_no_game),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (series.enabled && !series.isOnce) {
                SkipRow(series, state, now, onSkip, onUndoSkip)
            }
        }
    }
}

@Composable
private fun SkipRow(
    series: AlarmSeries,
    state: AppState,
    now: Long,
    onSkip: (LocalDate) -> Unit,
    onUndoSkip: () -> Unit,
) {
    val context = LocalContext.current
    val zone = AlarmEngine.zone()
    val upcoming = Planner.upcomingDay(state, series, now, zone) ?: return
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayText = Format.dayName(context, upcoming, today)
    if (Planner.isSkipped(state, series.id, upcoming)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_skipped, dayText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndoSkip) { Text(stringResource(R.string.action_undo)) }
        }
    } else {
        TextButton(onClick = { onSkip(upcoming) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.home_skip, dayText))
        }
    }
}
