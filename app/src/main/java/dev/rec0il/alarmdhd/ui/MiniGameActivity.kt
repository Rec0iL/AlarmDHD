package dev.rec0il.alarmdhd.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.alarm.RingService
import dev.rec0il.alarmdhd.alarm.RingState
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.game.MiniGame
import dev.rec0il.alarmdhd.game.MiniGames
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hosts a game over the lock screen. Winning is the only way to stop the day's alarms. Leaving,
 * turning the screen off or going idle goes back to ringing, so nobody falls asleep on the game.
 */
class MiniGameActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var isDone = false
    private var fingerDown = false
    private var lastActivity = SystemClock.elapsedRealtime()

    private val idleCheck = object : Runnable {
        override fun run() {
            if (isDone) return
            if (!fingerDown && SystemClock.elapsedRealtime() - lastActivity >= IDLE_LIMIT_MS) {
                leave()
            } else {
                handler.postDelayed(this, IDLE_CHECK_MS)
            }
        }
    }

    /** A double-tap with TalkBack is an accessibility action, which never passes through the touch methods. */
    private val accessibilityActivity = object : View.AccessibilityDelegate() {
        override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
            if (event.eventType and USER_ACCESSIBILITY_EVENTS != 0) lastActivity = SystemClock.elapsedRealtime()
            return super.onRequestSendAccessibilityEvent(host, child, event)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val game = MiniGames.pick(AlarmStore.state.value.live.lastGameId)
        AlarmStore.update { it.copy(live = it.live.copy(lastGameId = game.id)) }
        val wasTest = RingState.active.value?.isTest == true

        setContent {
            AlarmTheme {
                var won by remember { mutableStateOf(false) }
                BackHandler(enabled = !won) { leave() }
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    if (won) {
                        Celebration(wasTest, onClose = { finish() })
                    } else {
                        GameFrame(
                            game = game,
                            onLeave = { leave() },
                            onWin = {
                                win()
                                won = true
                            },
                        )
                    }
                }
            }
        }
        window.decorView.accessibilityDelegate = accessibilityActivity
        handler.postDelayed(idleCheck, IDLE_CHECK_MS)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastActivity = SystemClock.elapsedRealtime()
        fingerDown = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
        return super.dispatchTouchEvent(event)
    }

    /** With TalkBack on, exploring the screen arrives as hovering rather than touching. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        lastActivity = SystemClock.elapsedRealtime()
        return super.dispatchGenericMotionEvent(event)
    }

    private fun win() {
        isDone = true
        handler.removeCallbacks(idleCheck)
        val service = RingService.current
        if (service != null) service.win() else AlarmEngine.stopRunning(this)
    }

    private fun leave() {
        if (isDone) return
        isDone = true
        handler.removeCallbacks(idleCheck)
        RingService.current?.leaveGame()
        if (RingState.active.value != null) {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isDone && !isChangingConfigurations) {
            // The screen went off or something else took over. The ring notification brings the ring screen back.
            isDone = true
            handler.removeCallbacks(idleCheck)
            RingService.current?.leaveGame()
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    private companion object {
        const val IDLE_LIMIT_MS = 60_000L
        const val IDLE_CHECK_MS = 5_000L

        /** Only what a person causes. Status lines read out by TalkBack don't show that anyone is awake. */
        const val USER_ACCESSIBILITY_EVENTS = AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
    }
}

@Composable
private fun GameFrame(game: MiniGame, onLeave: () -> Unit, onWin: () -> Unit) {
    Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(game.title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.game_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLeave) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.game_leave))
            }
        }
        Spacer(Modifier.height(8.dp))
        game.Play(onWin)
    }
}

private class Confetti(val x: Float, val delay: Float, val speed: Float, val sway: Float, val spin: Float, val color: Color)

private val CONFETTI_COLORS = listOf(
    Color(0xFFFFB454),
    Color(0xFFFF8A80),
    Color(0xFF7FC8F8),
    Color(0xFFB9A2FF),
    Color(0xFF8EE3B0),
)

@Composable
private fun Celebration(wasTest: Boolean, onClose: () -> Unit) {
    val pieces = remember {
        List(90) {
            Confetti(
                x = Random.nextFloat(),
                delay = Random.nextFloat(),
                speed = 0.18f + Random.nextFloat() * 0.22f,
                sway = 10f + Random.nextFloat() * 30f,
                spin = 90f + Random.nextFloat() * 270f,
                color = CONFETTI_COLORS.random(),
            )
        }
    }
    var millis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            millis = withFrameMillis { it } - start
        }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val seconds = millis / 1000f
            for (piece in pieces) {
                val fall = (seconds * piece.speed + piece.delay) % 1.2f - 0.1f
                val x = piece.x * size.width + sin(seconds * 2 + piece.delay * 6) * piece.sway
                val y = fall * size.height
                rotate(seconds * piece.spin, pivot = Offset(x, y)) {
                    drawRect(piece.color, topLeft = Offset(x, y), size = Size(18f, 28f))
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        ) {
            Text(
                stringResource(R.string.game_won_title),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (wasTest) R.string.game_won_test else R.string.game_won_text),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onClose, modifier = Modifier.height(64.dp)) {
                Text(stringResource(R.string.game_won_close), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
