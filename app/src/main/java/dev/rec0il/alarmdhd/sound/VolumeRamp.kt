package dev.rec0il.alarmdhd.sound

import kotlin.math.pow

/**
 * Brings a ring from quiet to full volume. Anything going wrong in here ends the ramp at full
 * volume, so a problem can only ever make an alarm louder, never leave it stuck quiet.
 */
class VolumeRamp(
    private val timer: ChainTimer,
    private val now: () -> Long,
    private val setVolume: (Float) -> Unit,
) {
    private var startedAt: Long? = null
    private var finished = false
    private var cancelStep: () -> Unit = {}

    /** The clock only starts once, so going back to ringing after the game doesn't start quiet again. */
    fun start() {
        cancelStep()
        step()
    }

    fun stop() = cancelStep()

    /** Full volume from here on, including for any sound played after this. */
    fun finish() {
        finished = true
        cancelStep()
        try {
            setVolume(1f)
        } catch (e: Exception) {
            // The player is broken, and the chain is already moving on from it. The next one starts at full.
        }
    }

    private fun step() {
        try {
            val time = now()
            val began = startedAt ?: time.also { startedAt = it }
            val level = if (finished) 1f else levelAt(time - began)
            setVolume(level)
            if (level < 1f) cancelStep = timer.schedule(STEP_MS, ::step)
        } catch (e: Exception) {
            finish()
        }
    }

    companion object {
        const val DURATION_MS = 30_000L
        const val STEP_MS = 250L

        /** Quiet but not off. Even steps in decibels are what sound even to the ear. */
        private const val QUIET_DB = -30f

        /** Worked out from the clock, not counted in steps, so a late step can't stretch the ramp. */
        fun levelAt(elapsedMs: Long): Float {
            if (elapsedMs !in 0 until DURATION_MS) return 1f
            return 10f.pow(QUIET_DB * (1f - elapsedMs.toFloat() / DURATION_MS) / 20f)
        }
    }
}
