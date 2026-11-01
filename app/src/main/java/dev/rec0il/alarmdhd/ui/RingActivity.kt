package dev.rec0il.alarmdhd.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.ActiveRing
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.alarm.RingService
import dev.rec0il.alarmdhd.alarm.RingState
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.Planner
import kotlinx.coroutines.delay

/** Shows over the lock screen while something rings, and closes itself as soon as it doesn't. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AlarmTheme {
                // Back would only hide this while it keeps ringing. The buttons are the way out.
                BackHandler { }
                val ring by RingState.active.collectAsStateWithLifecycle()
                val showing = ring
                LaunchedEffect(showing == null, showing?.paused) {
                    if (showing == null || showing.paused) finish()
                }
                if (showing != null) {
                    RingScreen(
                        ring = showing,
                        onSnooze = { RingService.current?.snooze() ?: finish() },
                        onDismiss = { RingService.current?.dismiss() ?: finish() },
                        onImUp = { RingService.beginGame(this) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RingService.current?.ringScreenShown(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) RingService.current?.ringScreenHidden(this)
    }
}

@Composable
private fun RingScreen(ring: ActiveRing, onSnooze: () -> Unit, onDismiss: () -> Unit, onImUp: () -> Unit) {
    val context = LocalContext.current
    val now by produceState(AlarmEngine.now()) {
        while (true) {
            value = AlarmEngine.now()
            delay(1000)
        }
    }
    val state by AlarmStore.state.collectAsStateWithLifecycle()
    val progress = Planner.progress(state, now, AlarmEngine.zone())

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.safeDrawingPadding().padding(20.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(Format.time(context, now), fontSize = 76.sp, fontWeight = FontWeight.Light)
            if (ring.label.isNotBlank()) {
                Text(ring.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                Format.ringLine(context, ring),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (progress != null) {
                val next = progress.nextAt?.let { stringResource(R.string.progress_next, Format.time(context, it)) }
                Text(
                    listOfNotNull(next, Format.progressLine(context, progress).ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            ring.nowPlaying?.let {
                Text(
                    stringResource(R.string.ring_now_playing, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            ring.issue?.let {
                Text(
                    soundIssueText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(128.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(Format.snoozeLabel(context, ring), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text(stringResource(R.string.ring_dismiss), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = onImUp, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text(stringResource(R.string.ring_im_up), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
