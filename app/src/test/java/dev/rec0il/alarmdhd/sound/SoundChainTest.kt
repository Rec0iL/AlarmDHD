package dev.rec0il.alarmdhd.sound

import dev.rec0il.alarmdhd.model.LibraryIndex
import dev.rec0il.alarmdhd.model.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundChainTest {
    private enum class Outcome { STARTS, FAILS, HANGS }

    private class FakePlayer(val outcomes: Map<SoundTrack, Outcome>) : TrackPlayer {
        val played = mutableListOf<SoundTrack>()
        var listener: TrackPlayer.Listener? = null
        var isLooping = false

        override fun play(track: SoundTrack, loop: Boolean, listener: TrackPlayer.Listener) {
            played += track
            this.listener = listener
            isLooping = loop
            when (outcomes[track] ?: Outcome.STARTS) {
                Outcome.STARTS -> listener.onStarted()
                Outcome.FAILS -> listener.onFailed()
                Outcome.HANGS -> Unit
            }
        }

        override fun stop() {
            listener = null
        }
    }

    private class FakeTimer : ChainTimer {
        val pending = mutableListOf<() -> Unit>()

        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            pending += block
            return { pending.remove(block) }
        }

        fun fireAll() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it() }
        }
    }

    private val songA = SoundTrack.File("a", "Song A")
    private val songB = SoundTrack.File("b", "Song B")
    private val songC = SoundTrack.File("c", "Song C")

    private val timer = FakeTimer()
    private val statuses = mutableListOf<ChainStatus>()

    private fun chain(outcomes: Map<SoundTrack, Outcome>): Pair<SoundChain, FakePlayer> {
        val player = FakePlayer(outcomes)
        return SoundChain(player, timer) { statuses += it } to player
    }

    @Test
    fun `plays the chosen song and reports no problem`() {
        val (chain, player) = chain(emptyMap())
        chain.start(listOf(songA), expectTracks = true)
        assertEquals(listOf<SoundTrack>(songA), player.played)
        assertEquals(songA, statuses.last().playing)
        assertTrue(statuses.last().failed.isEmpty())
        assertFalse(statuses.last().usedFallback)
    }

    @Test
    fun `a single song loops and several do not`() {
        val (single, singlePlayer) = chain(emptyMap())
        single.start(listOf(songA), expectTracks = true)
        assertTrue(singlePlayer.isLooping)

        val (several, severalPlayer) = chain(emptyMap())
        several.start(listOf(songA, songB), expectTracks = true)
        assertFalse(severalPlayer.isLooping)
    }

    @Test
    fun `default tone asked for is not reported as a fallback`() {
        val (chain, player) = chain(emptyMap())
        chain.start(emptyList(), expectTracks = false)
        assertEquals(listOf<SoundTrack>(SoundTrack.SystemDefault), player.played)
        assertFalse(statuses.last().usedFallback)
    }

    @Test
    fun `song that fails to start moves to the next song`() {
        val (chain, player) = chain(mapOf(songA to Outcome.FAILS))
        chain.start(listOf(songA, songB), expectTracks = true)
        assertEquals(listOf<SoundTrack>(songA, songB), player.played)
        assertEquals(songB, statuses.last().playing)
        assertEquals(listOf(songA), statuses.last().failed)
        assertFalse(statuses.last().usedFallback)
    }

    @Test
    fun `song that breaks midway moves to the next song`() {
        val (chain, player) = chain(emptyMap())
        chain.start(listOf(songA, songB), expectTracks = true)
        player.listener!!.onFailed()
        assertEquals(songB, player.played.last())
        assertEquals(listOf(songA), statuses.last().failed)
    }

    @Test
    fun `song too slow to start times out and moves on`() {
        val (chain, player) = chain(mapOf(songA to Outcome.HANGS))
        chain.start(listOf(songA, songB), expectTracks = true)
        assertEquals(songA, player.played.last())
        timer.fireAll()
        assertEquals(songB, player.played.last())
    }

    @Test
    fun `all songs failing falls back to the system tone`() {
        val (chain, player) = chain(mapOf(songA to Outcome.FAILS, songB to Outcome.FAILS))
        chain.start(listOf(songA, songB), expectTracks = true)
        assertEquals(SoundTrack.SystemDefault, player.played.last())
        assertTrue(statuses.last().usedFallback)
        assertEquals(listOf(songA, songB), statuses.last().failed)
    }

    @Test
    fun `empty folder falls back and reports it`() {
        val (chain, player) = chain(emptyMap())
        chain.start(emptyList(), expectTracks = true)
        assertEquals(SoundTrack.SystemDefault, player.played.last())
        assertTrue(statuses.last().usedFallback)
    }

    @Test
    fun `every kind of failure ends at the bundled tone`() {
        for (failure in listOf(Outcome.FAILS, Outcome.HANGS)) {
            val (chain, player) = chain(mapOf(songA to failure, SoundTrack.SystemDefault to failure))
            chain.start(listOf(songA), expectTracks = true)
            repeat(3) { timer.fireAll() }
            assertEquals(SoundTrack.Bundled, player.played.last())
            assertEquals(SoundTrack.Bundled, statuses.last().playing)
        }
    }

    @Test
    fun `system tone breaking midway moves to the bundled tone`() {
        val (chain, player) = chain(emptyMap())
        chain.start(emptyList(), expectTracks = false)
        player.listener!!.onFailed()
        assertEquals(SoundTrack.Bundled, player.played.last())
    }

    @Test
    fun `even the bundled tone failing is reported, not hidden`() {
        val (chain, _) = chain(mapOf(SoundTrack.SystemDefault to Outcome.FAILS, SoundTrack.Bundled to Outcome.FAILS))
        chain.start(emptyList(), expectTracks = false)
        assertNull(statuses.last().playing)
    }

    @Test
    fun `gives up on a big broken folder after a few songs`() {
        val songs = List(50) { SoundTrack.File("uri$it", "Song $it") }
        val (chain, player) = chain(songs.associateWith { Outcome.FAILS })
        chain.start(songs, expectTracks = true)
        assertEquals(SoundChain.MAX_TRACK_FAILURES + 1, player.played.size)
        assertEquals(SoundTrack.SystemDefault, player.played.last())
    }

    @Test
    fun `finished song moves to the next and wraps around`() {
        val (chain, player) = chain(emptyMap())
        chain.start(listOf(songA, songB, songC), expectTracks = true)
        player.listener!!.onCompleted()
        assertEquals(songB, player.played.last())
        player.listener!!.onCompleted()
        player.listener!!.onCompleted()
        assertEquals(songA, player.played.last())
    }

    @Test
    fun `broken song is dropped so the next round skips it`() {
        val (chain, player) = chain(mapOf(songB to Outcome.FAILS))
        chain.start(listOf(songA, songB, songC), expectTracks = true)
        player.listener!!.onCompleted() // A ends, B fails, C plays.
        assertEquals(songC, player.played.last())
        player.listener!!.onCompleted() // C ends, back to A without trying B.
        assertEquals(songA, player.played.last())
        assertEquals(1, player.played.count { it == songB })
    }

    @Test
    fun `deleted song in a picked playlist is skipped and reported`() {
        val source = SoundSource.PickedSongs(listOf(SoundSource.Song("a", "Song A"), SoundSource.Song("b", "Song B")))
        val pick = TrackPicker.pick(source, LibraryIndex(), 0, null)
        val (chain, player) = chain(mapOf(songA to Outcome.FAILS))
        chain.start(pick.tracks, expectTracks = true)
        assertEquals(songB, player.played.last())
        assertEquals(listOf(songA), statuses.last().failed)
        assertFalse(statuses.last().usedFallback)
    }

    @Test
    fun `picked playlist with every song deleted reaches the system tone`() {
        val source = SoundSource.PickedSongs(listOf(SoundSource.Song("a", "Song A"), SoundSource.Song("b", "Song B")))
        val pick = TrackPicker.pick(source, LibraryIndex(), 0, null)
        val (chain, player) = chain(mapOf(songA to Outcome.FAILS, songB to Outcome.FAILS))
        chain.start(pick.tracks, expectTracks = true)
        assertEquals(SoundTrack.SystemDefault, player.played.last())
        assertTrue(statuses.last().usedFallback)
    }

    @Test
    fun `a sound playing normally is not a problem`() {
        val (chain, _) = chain(emptyMap())
        chain.start(listOf(songA), expectTracks = true)
        assertFalse(statuses.last().hadProblem)
        chain.start(emptyList(), expectTracks = false)
        assertFalse(statuses.last().hadProblem)
    }

    @Test
    fun `any failure counts as a problem`() {
        val (skipped, _) = chain(mapOf(songA to Outcome.FAILS))
        skipped.start(listOf(songA, songB), expectTracks = true)
        assertTrue(statuses.last().hadProblem)

        val (fellBack, _) = chain(emptyMap())
        fellBack.start(emptyList(), expectTracks = true)
        assertTrue(statuses.last().hadProblem)

        // The default tone was asked for, so this isn't reported as a fallback, but it did break.
        val (toneBroke, _) = chain(mapOf(SoundTrack.SystemDefault to Outcome.FAILS))
        toneBroke.start(emptyList(), expectTracks = false)
        assertFalse(statuses.last().usedFallback)
        assertTrue(statuses.last().hadProblem)
    }

    @Test
    fun `previewing a song plays it on a loop`() {
        val (chain, player) = chain(emptyMap())
        chain.start(listOf(songA), expectTracks = true, useFallbacks = false)
        assertEquals(listOf<SoundTrack>(songA), player.played)
        assertTrue(player.isLooping)
        assertEquals(songA, statuses.last().playing)
    }

    @Test
    fun `previewing a broken song reports it and plays no tone`() {
        for (failure in listOf(Outcome.FAILS, Outcome.HANGS)) {
            val (chain, player) = chain(mapOf(songA to failure))
            chain.start(listOf(songA), expectTracks = true, useFallbacks = false)
            timer.fireAll()
            assertEquals(listOf<SoundTrack>(songA), player.played)
            assertNull(statuses.last().playing)
        }
    }

    @Test
    fun `a ring after a preview still has its fallbacks`() {
        val (chain, player) = chain(mapOf(songA to Outcome.FAILS))
        chain.start(listOf(songA), expectTracks = true, useFallbacks = false)
        chain.start(listOf(songA), expectTracks = true)
        assertEquals(SoundTrack.SystemDefault, player.played.last())
    }

    @Test
    fun `stop cancels the timeout and ignores late callbacks`() {
        val (chain, player) = chain(mapOf(songA to Outcome.HANGS))
        chain.start(listOf(songA, songB), expectTracks = true)
        val lateListener = player.listener!!
        chain.stop()
        assertTrue(timer.pending.isEmpty())
        lateListener.onFailed()
        lateListener.onStarted()
        assertEquals(listOf<SoundTrack>(songA), player.played)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `starting again begins from a clean slate`() {
        val (chain, player) = chain(mapOf(songA to Outcome.FAILS))
        chain.start(listOf(songA), expectTracks = true)
        assertEquals(SoundTrack.SystemDefault, player.played.last())
        chain.start(listOf(songB), expectTracks = true)
        assertEquals(songB, player.played.last())
        assertTrue(statuses.last().failed.isEmpty())
    }
}
