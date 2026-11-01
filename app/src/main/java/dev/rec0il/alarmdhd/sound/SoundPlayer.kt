package dev.rec0il.alarmdhd.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.rec0il.alarmdhd.R
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.SoundSource
import kotlin.concurrent.thread

/** Plays a [SoundSource] on the alarm audio stream, walking the fallback chain whenever a sound fails. */
class SoundPlayer(
    private val context: Context,
    private val onStatus: (ChainStatus) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val tracks = MediaTrackPlayer(context)
    private val timer = HandlerChainTimer()
    private var ramp: VolumeRamp? = null
    private val chain = SoundChain(tracks, timer) { status ->
        // Something has already gone wrong this ring, so nothing should hold the sound back now.
        if (status.hadProblem) ramp?.finish()
        onStatus(status)
    }
    private var focus: AudioFocusRequest? = null
    private var restoreVolume: Int? = null

    /** [seriesId] remembers playlist position for that run. Previews pass null, so they also play at full volume. */
    fun start(requested: SoundSource, seriesId: String?) {
        requestFocus()
        raiseVolumeIfMuted()
        val settings = AlarmStore.state.value.settings
        val source = settings.resolve(requested)
        if (ramp == null && settings.series.any { it.id == seriesId && it.gradualVolume }) {
            ramp = VolumeRamp(timer, SystemClock::elapsedRealtime) { tracks.volume = it }
        }
        ramp?.start()
        val live = AlarmStore.state.value.live
        val pick = TrackPicker.pick(
            source,
            MusicLibrary.index.value,
            cursor = seriesId?.let { live.trackCursors[it] } ?: 0,
            lastRandom = seriesId?.let { live.lastRandomTrack[it] },
        )
        if (seriesId != null) {
            AlarmStore.update { state ->
                var updated = state.live
                pick.nextCursor?.let { updated = updated.copy(trackCursors = updated.trackCursors + (seriesId to it)) }
                pick.randomPick?.let { updated = updated.copy(lastRandomTrack = updated.lastRandomTrack + (seriesId to it)) }
                state.copy(live = updated)
            }
        }
        chain.start(pick.tracks, expectTracks = source !is SoundSource.DefaultAlarm)
    }

    /** Plays one song at alarm volume. If it won't play the status says so, because a tone in its place would mislead. */
    fun previewSong(uri: String, title: String) {
        requestFocus()
        raiseVolumeIfMuted()
        chain.start(listOf(SoundTrack.File(uri, title)), expectTracks = true, useFallbacks = false)
    }

    fun stop() {
        ramp?.stop()
        chain.stop()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        restoreVolume?.let { setAlarmVolume(it) }
        restoreVolume = null
    }

    fun titleOf(track: SoundTrack?): String? = when (track) {
        is SoundTrack.File -> track.title
        SoundTrack.SystemDefault -> context.getString(R.string.sound_system)
        SoundTrack.Bundled -> context.getString(R.string.sound_bundled)
        null -> null
    }

    /** Pauses anything else that's playing, like a podcast left on overnight. */
    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ALARM_AUDIO)
            .build()
        audio.requestAudioFocus(request)
        focus = request
    }

    private fun raiseVolumeIfMuted() {
        val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        if (volume == 0) {
            restoreVolume = volume
            setAlarmVolume(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 2)
        }
    }

    private fun setAlarmVolume(volume: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse volume changes. The sound still plays at whatever it is.
        }
    }

    companion object {
        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

private class HandlerChainTimer : ChainTimer {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}

/**
 * Opening a file can block for as long as the storage takes to answer, for example a failing SD
 * card. That happens on its own thread, so the main thread stays free for the start timeout.
 */
private class MediaTrackPlayer(private val context: Context) : TrackPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var isPrepared = false
    private var generation = 0

    /** From 0 to 1, for this sound and any after it. Doesn't touch the alarm volume the user has set. */
    var volume = 1f
        set(value) {
            field = value
            // Not before it's prepared: this would wait on the same lock as a file that's stuck opening.
            if (isPrepared) player?.setVolume(value, value)
        }

    override fun play(track: SoundTrack, loop: Boolean, listener: TrackPlayer.Listener) {
        stop()
        val current = ++generation
        val media = MediaPlayer()
        player = media
        media.setOnErrorListener { _, _, _ ->
            if (current == generation) listener.onFailed()
            true
        }
        media.setOnCompletionListener {
            if (current == generation) listener.onCompleted()
        }
        thread(isDaemon = true) {
            try {
                media.setAudioAttributes(SoundPlayer.ALARM_AUDIO)
                setSource(media, track)
                media.isLooping = loop
                media.prepare()
                main.post {
                    if (current == generation) {
                        isPrepared = true
                        // A new player is at full volume, so failing here still leaves it audible.
                        runCatching { media.setVolume(volume, volume) }
                        media.start()
                        listener.onStarted()
                    }
                }
            } catch (e: Exception) {
                main.post { if (current == generation) listener.onFailed() }
            }
        }
    }

    private fun setSource(media: MediaPlayer, track: SoundTrack) {
        when (track) {
            is SoundTrack.File -> media.setDataSource(context, Uri.parse(track.uri))
            SoundTrack.SystemDefault -> {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: error("No default alarm tone")
                media.setDataSource(context, uri)
            }
            SoundTrack.Bundled -> context.resources.openRawResourceFd(R.raw.fallback_alarm).use {
                media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
        }
    }

    override fun stop() {
        generation++
        val media = player ?: return
        player = null
        if (isPrepared) {
            // Already playing, so this is instant, and the noise must end the moment a button is pressed.
            isPrepared = false
            release(media)
        } else {
            // Might still be stuck opening a file, so wait for that somewhere other than the main thread.
            thread(isDaemon = true) { release(media) }
        }
    }

    private fun release(media: MediaPlayer) {
        runCatching { media.stop() }
        runCatching { media.release() }
    }
}
