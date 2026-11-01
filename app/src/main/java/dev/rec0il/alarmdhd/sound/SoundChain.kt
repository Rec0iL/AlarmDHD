package dev.rec0il.alarmdhd.sound

sealed interface SoundTrack {
    data class File(val uri: String, val title: String) : SoundTrack
    data object SystemDefault : SoundTrack

    /** Ships inside the app, so nothing the user does can remove it. */
    data object Bundled : SoundTrack
}

interface TrackPlayer {
    fun play(track: SoundTrack, loop: Boolean, listener: Listener)
    fun stop()

    interface Listener {
        fun onStarted()

        /** Couldn't start, or broke while playing. */
        fun onFailed()
        fun onCompleted()
    }
}

interface ChainTimer {
    /** Returns a function that cancels the timer. */
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
}

/** [playing] is null only if every sound failed, including the bundled one. */
data class ChainStatus(
    val playing: SoundTrack?,
    val failed: List<SoundTrack.File>,
    val usedFallback: Boolean,
) {
    /** Bundled counts even when the default tone was asked for, because it only plays after a failure. */
    val hadProblem get() = failed.isNotEmpty() || usedFallback || playing == null || playing == SoundTrack.Bundled
}

/**
 * Keeps something playing: the chosen tracks first, then the system alarm tone, then the bundled
 * tone. Any way a sound can go wrong (won't start, too slow to start, breaks midway) moves on.
 */
class SoundChain(
    private val player: TrackPlayer,
    private val timer: ChainTimer,
    private val onStatus: (ChainStatus) -> Unit,
) {
    private var tracks = mutableListOf<SoundTrack.File>()
    private var position = 0
    private val failed = mutableListOf<SoundTrack.File>()
    private var fallbacks = ArrayDeque<SoundTrack>()
    private var expectedTracks = false
    private var cancelTimeout: () -> Unit = {}

    // Each attempt gets a number, so a late callback from an abandoned attempt is ignored.
    private var attempt = 0

    /**
     * [expectTracks] is false when the default tone is what was asked for, so it isn't a fallback.
     * [useFallbacks] is false only for hearing a song before choosing it, never for a ring.
     */
    fun start(chosen: List<SoundTrack.File>, expectTracks: Boolean, useFallbacks: Boolean = true) {
        stop()
        tracks = chosen.toMutableList()
        position = 0
        failed.clear()
        fallbacks = if (useFallbacks) ArrayDeque(listOf(SoundTrack.SystemDefault, SoundTrack.Bundled)) else ArrayDeque()
        expectedTracks = expectTracks
        playNext()
    }

    fun stop() {
        attempt++
        cancelTimeout()
        player.stop()
    }

    private fun playNext() {
        if (tracks.isNotEmpty() && failed.size < MAX_TRACK_FAILURES) {
            play(tracks[position % tracks.size], loop = tracks.size == 1)
        } else {
            playFallback()
        }
    }

    private fun playFallback() {
        val fallback = fallbacks.removeFirstOrNull()
        if (fallback == null) {
            onStatus(ChainStatus(null, failed.toList(), usedFallback = true))
        } else {
            play(fallback, loop = true)
        }
    }

    private fun play(track: SoundTrack, loop: Boolean) {
        val current = ++attempt
        cancelTimeout = timer.schedule(START_TIMEOUT_MS) {
            if (current == attempt) onAttemptFailed(track)
        }
        player.play(track, loop, object : TrackPlayer.Listener {
            override fun onStarted() {
                if (current != attempt) return
                cancelTimeout()
                onStatus(ChainStatus(track, failed.toList(), usedFallback = expectedTracks && track !is SoundTrack.File))
            }

            override fun onFailed() {
                if (current != attempt) return
                cancelTimeout()
                onAttemptFailed(track)
            }

            override fun onCompleted() {
                if (current != attempt) return
                position = (position + 1) % tracks.size.coerceAtLeast(1)
                playNext()
            }
        })
    }

    private fun onAttemptFailed(track: SoundTrack) {
        player.stop()
        if (track is SoundTrack.File) {
            failed += track
            tracks.remove(track)
            if (position >= tracks.size) position = 0
            playNext()
        } else {
            playFallback()
        }
    }

    companion object {
        const val START_TIMEOUT_MS = 5_000L

        /** After this many broken songs, stop trying the rest of a big folder and use a tone. */
        const val MAX_TRACK_FAILURES = 5
    }
}
