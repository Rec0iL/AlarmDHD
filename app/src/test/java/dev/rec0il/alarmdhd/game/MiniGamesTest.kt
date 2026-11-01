package dev.rec0il.alarmdhd.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MiniGamesTest {
    @Test
    fun `all ten games are registered`() {
        assertEquals(10, MiniGames.all.size)
    }

    @Test
    fun `ids and titles are all different`() {
        assertEquals(MiniGames.all.size, MiniGames.all.map { it.id }.toSet().size)
        assertEquals(MiniGames.all.size, MiniGames.all.map { it.title }.toSet().size)
    }

    @Test
    fun `pick never returns the game that was just played`() {
        for (last in MiniGames.all) {
            for (seed in 0 until 500) {
                assertNotEquals(last.id, MiniGames.pick(last.id, Random(seed)).id)
            }
        }
    }

    @Test
    fun `a long run of mornings never repeats a game back to back`() {
        val random = Random(7)
        var last: String? = null
        repeat(5000) {
            val game = MiniGames.pick(last, random)
            assertNotEquals(last, game.id)
            last = game.id
        }
    }

    @Test
    fun `every game gets its turn`() {
        val random = Random(7)
        var last: String? = null
        val played = mutableSetOf<String>()
        repeat(1000) {
            val game = MiniGames.pick(last, random).id
            played += game
            last = game
        }
        assertEquals(MiniGames.all.map { it.id }.toSet(), played)
    }

    @Test
    fun `an id from an old version doesn't stop pick working`() {
        assertTrue(MiniGames.pick("game_that_no_longer_exists", Random(1)) in MiniGames.all)
    }
}
