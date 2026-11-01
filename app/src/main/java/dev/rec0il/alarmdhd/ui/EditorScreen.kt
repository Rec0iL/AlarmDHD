package dev.rec0il.alarmdhd.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.model.SoundSource

private const val PREVIEW_LIMIT = 12
private const val MAX_INTERVAL_MINUTES = 120
private const val MAX_SNOOZE_MINUTES = 60
private const val MAX_RING_MINUTES = 10

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    draft: AlarmSeries,
    isNew: Boolean,
    locked: Boolean,
    runMinutes: Int,
    defaultSound: SoundSource,
    onChange: (AlarmSeries) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onOpenSound: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_cancel))
                    }
                },
                title = { Text(stringResource(if (isNew) R.string.editor_new else R.string.editor_edit)) },
                actions = {
                    TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (locked) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(stringResource(R.string.editor_locked), modifier = Modifier.padding(16.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TimeTile(
                    label = stringResource(R.string.editor_start),
                    value = Format.minuteOfDay(context, draft.startMinute),
                    enabled = !locked,
                    onClick = { pickingStart = true },
                    modifier = Modifier.weight(1f),
                )
                TimeTile(
                    label = stringResource(R.string.editor_end),
                    value = draft.endMinute?.let { Format.minuteOfDay(context, it) } ?: stringResource(R.string.editor_open_end),
                    enabled = !locked && !draft.isOpenEnd,
                    onClick = { pickingEnd = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.editor_no_end), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.editor_no_end_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.isOpenEnd,
                    enabled = !locked,
                    onCheckedChange = { open ->
                        onChange(draft.copy(endMinute = if (open) null else Planner.endAfter(draft.startMinute, runMinutes)))
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Stepper(
                label = stringResource(R.string.editor_interval),
                value = draft.intervalMinutes,
                valueText = stringResource(R.string.editor_minutes, draft.intervalMinutes),
                min = 1,
                max = MAX_INTERVAL_MINUTES,
                enabled = !locked,
                onChange = { onChange(draft.copy(intervalMinutes = it)) },
            )
            Stepper(
                label = stringResource(R.string.editor_snooze),
                value = draft.snoozeMinutes,
                valueText = stringResource(R.string.editor_minutes, draft.snoozeMinutes),
                min = 1,
                max = MAX_SNOOZE_MINUTES,
                onChange = { onChange(draft.copy(snoozeMinutes = it)) },
            )

            RingPreviewBlock(draft)

            SectionTitle(stringResource(R.string.editor_days))
            DayPicker(draft, enabled = !locked, onChange = onChange)
            Text(
                stringResource(if (draft.isOnce) R.string.editor_days_once else R.string.editor_days_repeat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            SectionTitle(stringResource(R.string.editor_ringing))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSound).padding(vertical = 12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_music), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.editor_sound), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        runSoundTitle(draft.sound, defaultSound),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(R.string.editor_vibrate), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = draft.vibrate, onCheckedChange = { onChange(draft.copy(vibrate = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.editor_gradual_volume), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.editor_gradual_volume_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = draft.gradualVolume, onCheckedChange = { onChange(draft.copy(gradualVolume = it)) })
            }
            Stepper(
                label = stringResource(R.string.editor_ring_length),
                value = draft.ringSeconds / 60,
                valueText = stringResource(R.string.editor_minutes, draft.ringSeconds / 60),
                min = 1,
                max = MAX_RING_MINUTES,
                onChange = { onChange(draft.copy(ringSeconds = it * 60)) },
            )

            OutlinedTextField(
                value = draft.label,
                onValueChange = { onChange(draft.copy(label = it)) },
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickingStart) {
        TimeDialog(
            initialMinute = draft.startMinute,
            onDismiss = { pickingStart = false },
            onPicked = {
                onChange(if (isNew) Planner.moveStart(draft, it, runMinutes) else draft.copy(startMinute = it))
                pickingStart = false
            },
        )
    }
    if (pickingEnd) {
        TimeDialog(
            initialMinute = draft.endMinute ?: draft.startMinute,
            onDismiss = { pickingEnd = false },
            onPicked = {
                onChange(draft.copy(endMinute = it))
                pickingEnd = false
            },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            confirmButton = {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TimeTile(label: String, value: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingPreviewBlock(draft: AlarmSeries) {
    val context = LocalContext.current
    val preview = Planner.previewMinutes(draft, PREVIEW_LIMIT)
    val count = preview.count
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (count == null) {
                    stringResource(R.string.editor_preview_open)
                } else {
                    pluralStringResource(R.plurals.editor_preview_count, count, count)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (minute in preview.minutes) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(
                            Format.minuteOfDay(context, minute),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (count == null || count > preview.minutes.size) {
                    Text("…", modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.editor_preview_snooze, draft.snoozeMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayPicker(draft: AlarmSeries, enabled: Boolean, onChange: (AlarmSeries) -> Unit) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        for (day in Format.weekOrder()) {
            val selected = day in draft.days
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(42.dp)
                    .alpha(if (enabled) 1f else 0.5f)
                    .clickable(enabled = enabled, role = Role.Checkbox) {
                        onChange(draft.copy(days = if (selected) draft.days - day else draft.days + day))
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(Format.shortDay(day), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPicked(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
