package dev.rec0il.alarmdhd.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeRampTest {
    private class FakeTimer : ChainTimer {
        var pending: (() -> Unit)? = null

        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            pending = block
            return { pending = null }
        }

        fun fire() {
            val due = pending
            pending = null
            due?.invoke()
        }
    }

    private val timer = FakeTimer()
    private var clock = 1_000L
    private val volumes = mutableListOf<Float>()
    private val ramp = VolumeRamp(timer, { clock }) { volumes += it }

    private fun advance(ms: Long) {
        clock += ms
        timer.fire()
    }

    @Test
    fun `starts quiet but not silent`() {
        ramp.start()
        assertEquals(VolumeRamp.levelAt(0), volumes.single(), 0f)
        assertTrue(volumes.single() > 0.01f && volumes.single() < 0.05f)
    }

    @Test
    fun `is never silent and only ever gets louder`() {
        val levels = (0L..VolumeRamp.DURATION_MS step 100L).map { VolumeRamp.levelAt(it) }
        assertTrue(levels.all { it > 0f })
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun `reaches full volume after the duration, and stops there`() {
        ramp.start()
        while (timer.pending != null) advance(VolumeRamp.STEP_MS)
        assertEquals(1f, volumes.last(), 0f)
        assertEquals(VolumeRamp.DURATION_MS, clock - 1_000L)
        assertEquals(volumes.sorted(), volumes)
    }

    @Test
    fun `a late step catches up instead of stretching the ramp`() {
        ramp.start()
        advance(VolumeRamp.DURATION_MS + 5_000L)
        assertEquals(1f, volumes.last(), 0f)
        assertEquals(null, timer.pending)
    }

    @Test
    fun `a clock that makes no sense means full volume`() {
        assertEquals(1f, VolumeRamp.levelAt(-1), 0f)
        assertEquals(1f, VolumeRamp.levelAt(Long.MIN_VALUE), 0f)
        assertEquals(1f, VolumeRamp.levelAt(Long.MAX_VALUE), 0f)
    }

    @Test
    fun `failing to set the volume goes to full and ends the ramp`() {
        var failNext = false
        val set = mutableListOf<Float>()
        val failing = VolumeRamp(timer, { clock }) {
            set += it
            if (failNext) {
                failNext = false
                error("Player is broken")
            }
        }
        failing.start()
        failNext = true
        advance(VolumeRamp.STEP_MS)
        assertEquals(1f, set.last(), 0f)
        assertEquals(null, timer.pending)
    }

    @Test
    fun `failing even to set full volume is survived`() {
        val failing = VolumeRamp(timer, { clock }) { error("Player is broken") }
        failing.start()
        assertEquals(null, timer.pending)
    }

    @Test
    fun `the timer failing goes to full volume`() {
        val brokenTimer = object : ChainTimer {
            override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit = error("No handler")
        }
        val set = mutableListOf<Float>()
        VolumeRamp(brokenTimer, { clock }) { set += it }.start()
        assertEquals(1f, set.last(), 0f)
    }

    @Test
    fun `finish goes to full for good, even when started again`() {
        ramp.start()
        ramp.finish()
        assertEquals(1f, volumes.last(), 0f)
        assertEquals(null, timer.pending)
        ramp.start()
        assertEquals(1f, volumes.last(), 0f)
        assertEquals(null, timer.pending)
    }

    @Test
    fun `stop halts the steps`() {
        ramp.start()
        ramp.stop()
        assertEquals(null, timer.pending)
        assertEquals(1, volumes.size)
    }

    @Test
    fun `starting again carries on from the same clock rather than going quiet again`() {
        ramp.start()
        ramp.stop()
        clock += VolumeRamp.DURATION_MS / 2
        ramp.start()
        assertEquals(VolumeRamp.levelAt(VolumeRamp.DURATION_MS / 2), volumes.last(), 0f)
        ramp.stop()
        clock += VolumeRamp.DURATION_MS
        ramp.start()
        assertEquals(1f, volumes.last(), 0f)
        assertEquals(null, timer.pending)
    }
}
