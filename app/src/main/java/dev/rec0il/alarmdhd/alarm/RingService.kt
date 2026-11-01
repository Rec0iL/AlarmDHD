package dev.rec0il.alarmdhd.alarm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.game.MiniGameIntents
import dev.rec0il.alarmdhd.model.AlarmSeries
import dev.rec0il.alarmdhd.model.FiredRing
import dev.rec0il.alarmdhd.model.Planner
import dev.rec0il.alarmdhd.model.SoundIssue
import dev.rec0il.alarmdhd.model.SoundSource
import dev.rec0il.alarmdhd.sound.ChainStatus
import dev.rec0il.alarmdhd.sound.SoundPlayer
import dev.rec0il.alarmdhd.ui.RingActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What's ringing right now. No series ids means a test alarm. */
data class ActiveRing(
    val seriesIds: List<String> = emptyList(),
    val label: String = "",
    val isSnooze: Boolean = false,
    val position: Int? = null,
    val total: Int? = null,
    val snoozeMinutes: Int? = null,
    /** The sound is off because a game is being played. */
    val paused: Boolean = false,
    val nowPlaying: String? = null,
    val issue: SoundIssue? = null,
) {
    val isTest get() = seriesIds.isEmpty()
}

object RingState {
    val active = MutableStateFlow<ActiveRing?>(null)
}

/**
 * Owns a ring from start to finish. It stays alive in a silent paused mode during the game, so
 * rings due meanwhile merge in quietly, and if the app dies, the next scheduled alarm still rings.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ringTimeout = Runnable { finishRing() }
    private lateinit var sound: SoundPlayer
    private lateinit var vibrator: Vibrator
    private lateinit var wakeLock: PowerManager.WakeLock
    private var isSounding = false
    private var isVibrating = false
    private var ringOnLeave = false
    private var foregroundId = FIRST_NOTIFICATION_ID

    /** The ring screen while it's on show. Cleared when it stops, so it can't outlive the screen. */
    private var ringScreen: Activity? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        sound = SoundPlayer(this, ::onSoundStatus)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarmdhd:ring")
        wakeLock.acquire(WAKE_LOCK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> onRing(
                FiredRing(
                    seriesIds = intent.getStringArrayExtra(EXTRA_IDS)?.toList().orEmpty(),
                    isSnooze = intent.getBooleanExtra(EXTRA_SNOOZE, false),
                    position = intent.getIntExtra(EXTRA_POSITION, 0).takeIf { it > 0 },
                    total = intent.getIntExtra(EXTRA_TOTAL, 0).takeIf { it > 0 },
                ),
            )
            ACTION_TEST -> onRing(FiredRing(emptyList(), isSnooze = false, position = null, total = null))
            ACTION_GAME -> onGameFromApp()
            else -> {
                // Android requires this after every start, even when there's nothing to do.
                showForeground(Notifications.gamePaused(this))
                finishRing()
            }
        }
        return START_NOT_STICKY
    }

    private fun seriesFor(ids: List<String>): List<AlarmSeries> {
        val all = AlarmStore.state.value.settings.series
        return ids.mapNotNull { id -> all.find { it.id == id } }
    }

    private fun onRing(fired: FiredRing) {
        val previous = RingState.active.value
        val ids = (previous?.seriesIds.orEmpty() + fired.seriesIds).distinct()
        val series = seriesFor(ids)
        val ring = ActiveRing(
            seriesIds = ids,
            label = series.map { it.label }.filter { it.isNotBlank() }.distinct().joinToString(" · "),
            isSnooze = fired.isSnooze,
            position = fired.position,
            total = fired.total,
            snoozeMinutes = series.firstOrNull()?.snoozeMinutes,
            paused = previous?.paused == true,
            nowPlaying = previous?.nowPlaying,
            issue = previous?.issue,
        )
        RingState.active.value = ring
        if (ring.paused) {
            ringOnLeave = true
            showForeground(Notifications.gamePaused(this))
        } else {
            startRinging(alreadyRinging = previous != null)
        }
    }

    private fun startRinging(alreadyRinging: Boolean) {
        val ring = RingState.active.value ?: return
        val series = seriesFor(ring.seriesIds)
        // A new ring always gets the full-screen intent, and the ring screen reports in once it's up.
        if (!alreadyRinging) ringScreen = null
        showForeground(Notifications.ring(this, ring, screenShowing = ringScreen != null), newId = !alreadyRinging)
        if (!alreadyRinging) openRingScreen()
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, (series.maxOfOrNull { it.ringSeconds } ?: TEST_RING_SECONDS) * 1000L)
        if (!isSounding) {
            isSounding = true
            sound.start(series.firstOrNull()?.sound ?: SoundSource.DefaultAlarm, series.firstOrNull()?.id)
        }
        if (series.isEmpty() || series.any { it.vibrate }) startVibration()
    }

    /**
     * Android only turns the notification into a full-screen alarm when the phone is locked or
     * asleep. While it's in use, opening the screen directly does it, given permission to show
     * over other apps. Without that permission Android ignores this and the banner remains.
     */
    private fun openRingScreen() {
        startActivity(
            Intent(this, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        )
    }

    fun ringScreenShown(screen: Activity) {
        val alreadyShowing = ringScreen != null
        ringScreen = screen
        if (!alreadyShowing) quietenRingNotification()
    }

    /** The screen was left or went off while ringing, so the banner comes back as the way back to it. */
    fun ringScreenHidden(screen: Activity) {
        // A new ring screen resumes before the old one stops.
        if (ringScreen !== screen) return
        ringScreen = null
        restoreRingBanner()
    }

    /**
     * A banner with a full-screen intent has no timeout, and whether updating the notification gives
     * it one depends on which of the two reaches the screen first. Replacing the notification always
     * takes the banner down, and the new one is on a channel without banners.
     */
    private fun quietenRingNotification() {
        val ring = RingState.active.value?.takeUnless { it.paused } ?: return
        try {
            showForeground(Notifications.ring(this, ring, screenShowing = true), newId = true)
        } catch (e: RuntimeException) {
            // The banner stays up, which is far better than a crash ending the ring.
            Log.e("RingService", "Couldn't take the banner down", e)
        }
    }

    /**
     * An update, because startForeground repeats Android's background checks and could throw, which
     * would end the ring. Without the notification permission this does nothing, and there's no banner
     * to bring back either.
     */
    @SuppressLint("MissingPermission")
    private fun restoreRingBanner() {
        val ring = RingState.active.value?.takeUnless { it.paused } ?: return
        NotificationManagerCompat.from(this).notify(foregroundId, Notifications.ring(this, ring))
    }

    /** A game started from the home screen, with nothing ringing yet. */
    private fun onGameFromApp() {
        if (RingState.active.value == null) {
            val state = AlarmStore.state.value
            val ids = Planner.runningIds(state, AlarmEngine.now(), AlarmEngine.zone())
            RingState.active.value = ActiveRing(seriesIds = ids, paused = true)
            ringOnLeave = false
        }
        showForeground(Notifications.gamePaused(this))
    }

    private fun onSoundStatus(status: ChainStatus) {
        val issue = status.failed.firstOrNull()?.let { SoundIssue(it.title, status.failed.size, status.usedFallback) }
            ?: if (status.usedFallback) SoundIssue("", 0, usedFallback = true) else null
        val seriesId = RingState.active.value?.seriesIds?.firstOrNull()
        if (seriesId != null) {
            AlarmStore.update { state ->
                val issues = state.live.soundIssues
                val updated = if (issue == null) issues - seriesId else issues + (seriesId to issue)
                state.copy(live = state.live.copy(soundIssues = updated))
            }
        }
        RingState.active.update { it?.copy(nowPlaying = sound.titleOf(status.playing), issue = issue) }
        // A sound that isn't what was chosen might not wake anyone, so vibration backs it up.
        if (status.usedFallback || status.playing == null) startVibration()
    }

    private fun startVibration() {
        if (isVibrating) return
        isVibrating = true
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        // The replacement only exists on Android 13 and up, and this still works there.
        @Suppress("DEPRECATION")
        vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0), attributes)
    }

    private fun silence() {
        if (isSounding) sound.stop()
        isSounding = false
        if (isVibrating) vibrator.cancel()
        isVibrating = false
    }

    private fun finishRing() {
        handler.removeCallbacks(ringTimeout)
        silence()
        RingState.active.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AlarmEngine.reschedule(this)
    }

    fun snooze() {
        val ids = RingState.active.value?.seriesIds.orEmpty()
        AlarmStore.update { Planner.snooze(it, ids, AlarmEngine.now()) }
        finishRing()
    }

    fun dismiss() = finishRing()

    fun enterGame() {
        val ring = RingState.active.value ?: return
        if (ring.paused) return
        handler.removeCallbacks(ringTimeout)
        silence()
        ringOnLeave = true
        RingState.active.value = ring.copy(paused = true)
        showForeground(Notifications.gamePaused(this))
    }

    /** The player left without winning: back to ringing, if there's a ring to go back to. */
    fun leaveGame() {
        val ring = RingState.active.value ?: return
        if (!ring.paused) return
        RingState.active.value = ring.copy(paused = false)
        if (ringOnLeave) startRinging(alreadyRinging = false) else finishRing()
    }

    fun win() {
        val ring = RingState.active.value
        if (ring?.isTest == false) {
            AlarmStore.update { Planner.stopRunning(it, AlarmEngine.now(), AlarmEngine.zone()) }
        }
        finishRing()
    }

    private fun showForeground(notification: Notification, newId: Boolean = false) {
        // Android only opens the full-screen ring screen for a new notification, not an updated one.
        val id = if (newId) foregroundId + 1 else foregroundId
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, id, notification, type)
        foregroundId = id
    }

    override fun onDestroy() {
        handler.removeCallbacks(ringTimeout)
        silence()
        RingState.active.value = null
        if (wakeLock.isHeld) wakeLock.release()
        current = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_RING = "ring"
        private const val ACTION_TEST = "test"
        private const val ACTION_GAME = "game"
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_SNOOZE = "snooze"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_TOTAL = "total"
        private const val FIRST_NOTIFICATION_ID = 100
        private const val TEST_RING_SECONDS = 30
        private const val WAKE_LOCK_MS = 15 * 60 * 1000L
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 400)

        // Only the main thread touches this, and onDestroy clears it, so it can't outlive the service.
        @SuppressLint("StaticFieldLeak")
        var current: RingService? = null
            private set

        fun startRing(context: Context, ring: FiredRing) {
            start(
                context,
                Intent(context, RingService::class.java)
                    .setAction(ACTION_RING)
                    .putExtra(EXTRA_IDS, ring.seriesIds.toTypedArray())
                    .putExtra(EXTRA_SNOOZE, ring.isSnooze)
                    .putExtra(EXTRA_POSITION, ring.position ?: 0)
                    .putExtra(EXTRA_TOTAL, ring.total ?: 0),
            )
        }

        fun startTest(context: Context) {
            start(context, Intent(context, RingService::class.java).setAction(ACTION_TEST))
        }

        /** Stops the noise, if there is any, and opens a game. Winning it stops today's alarms. */
        fun beginGame(context: Context) {
            val service = current
            if (service != null) {
                service.enterGame()
            } else {
                start(context, Intent(context, RingService::class.java).setAction(ACTION_GAME))
            }
            context.startActivity(MiniGameIntents.open(context))
        }

        private fun start(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (e: RuntimeException) {
                // The next ring is already scheduled, so this ring is lost but the run goes on.
                Log.e("RingService", "Couldn't start ringing", e)
            }
        }
    }
}
