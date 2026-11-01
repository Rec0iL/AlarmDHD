package dev.rec0il.alarmdhd.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameRulesTest {
    private val brush = Size(0.1f, 0.1f)

    @Test
    fun `sweeping rows clears the fog with no dragging at all`() {
        var fog = Coverage(5, 8)
        var sweeps = 0
        while (fog.fraction < 1f) {
            val y = fog.nextRowY()
            fog = fog.stroke(BrushStroke(Offset(0f, y), Offset(1f, y)), brush)
            sweeps++
            assertTrue("sweep $sweeps made no progress", sweeps <= 8)
        }
    }

    @Test
    fun `a tap covers what's under it and nothing far away`() {
        val spot = Offset(0.5f, 0.5f)
        val tapped = Coverage(5, 8).stroke(BrushStroke(spot, spot), brush)
        assertTrue(tapped.fraction > 0f)
        assertTrue(tapped.fraction < 0.25f)
    }

    @Test
    fun `going over the same place again takes nothing away`() {
        val line = BrushStroke(Offset(0f, 0.5f), Offset(1f, 0.5f))
        val once = Coverage(5, 8).stroke(line, brush)
        val twice = once.stroke(line, brush)
        assertEquals(once.fraction, twice.fraction)
    }

    @Test
    fun `a diagonal stroke covers the cells between its ends`() {
        val line = BrushStroke(Offset(0.1f, 0.1f), Offset(0.9f, 0.9f))
        val ends = Coverage(5, 8)
            .stroke(BrushStroke(line.from, line.from), brush)
            .stroke(BrushStroke(line.to, line.to), brush)
        assertTrue(Coverage(5, 8).stroke(line, brush).fraction > ends.fraction)
    }

    @Test
    fun `percent steps stay in tens and stop at a hundred`() {
        assertEquals(0, percentStep(0.09f))
        assertEquals(40, percentStep(0.47f))
        assertEquals(100, percentStep(1.3f))
    }

    @Test
    fun `wrong stars do nothing`() {
        var trail = StarTrail(8)
        for (wrong in 1 until 8) {
            trail = trail.tap(wrong)
            assertEquals(0, trail.next)
        }
    }

    @Test
    fun `tapping the stars in order finishes the trail`() {
        var trail = StarTrail(8)
        for (star in 0 until 8) {
            assertFalse(trail.done)
            trail = trail.tap(star)
        }
        assertTrue(trail.done)
    }

    @Test
    fun `tapping a star twice doesn't skip the next one`() {
        val trail = StarTrail(8).tap(0).tap(0)
        assertEquals(1, trail.next)
    }

    /** Roughly the smallest play area: a 320 by 568 dp phone, after the app's header and system bars. */
    @Test
    fun `star targets never overlap, even on a small screen`() {
        val target = ConnectStars.MIN_TARGET.value
        for (shape in ConnectStars.SHAPES) {
            val centers = shape.centers(288f, 380f, target)
            for (a in centers.indices) {
                for (b in a + 1 until centers.size) {
                    val apart = (centers[a] - centers[b]).getDistance()
                    assertTrue("stars ${a + 1} and ${b + 1} are only $apart dp apart", apart >= target)
                }
            }
        }
    }

    @Test
    fun `star targets stay on screen`() {
        val target = ConnectStars.MIN_TARGET.value
        for (shape in ConnectStars.SHAPES) {
            for (center in shape.centers(288f, 380f, target)) {
                assertTrue(center.x - target / 2 >= 0f && center.x + target / 2 <= 288f)
                assertTrue(center.y - target / 2 >= 0f && center.y + target / 2 <= 380f)
            }
        }
    }

    @Test
    fun `a dealt board holds each face exactly twice`() {
        for (seed in 0 until 50) {
            val board = MemoryBoard.deal(pairs = 4, kinds = 7, random = Random(seed))
            assertEquals(8, board.faces.size)
            assertTrue(board.faces.groupBy { it }.values.all { it.size == 2 })
        }
    }

    @Test
    fun `a wrong pair stays open until the next tap, which closes it`() {
        val board = MemoryBoard(listOf(0, 1, 0, 1))
        val wrong = board.flip(0).flip(1)
        assertTrue(wrong.isFaceUp(0) && wrong.isFaceUp(1))
        assertTrue(wrong.matched.isEmpty())

        val next = wrong.flip(2)
        assertFalse(next.isFaceUp(0) || next.isFaceUp(1))
        assertTrue(next.isFaceUp(2))
    }

    @Test
    fun `tapping an open wrong card just reopens it alone`() {
        val next = MemoryBoard(listOf(0, 1, 0, 1)).flip(0).flip(1).flip(1)
        assertEquals(listOf(1), next.open)
    }

    @Test
    fun `tapping the open card again doesn't match it with itself`() {
        val board = MemoryBoard(listOf(0, 1, 0, 1)).flip(0).flip(0)
        assertEquals(listOf(0), board.open)
        assertTrue(board.matched.isEmpty())
    }

    @Test
    fun `matched cards stay matched through any number of wrong tries`() {
        var board = MemoryBoard(listOf(0, 0, 1, 2, 1, 2)).flip(0).flip(1)
        repeat(20) { board = board.flip(2).flip(3) }
        assertEquals(setOf(0, 1), board.matched)
    }

    @Test
    fun `someone with no memory at all still wins, however long it takes`() {
        for (seed in 0 until 50) {
            val random = Random(seed)
            var board = MemoryBoard.deal(pairs = 4, kinds = 7, random = random)
            var taps = 0
            while (!board.won) {
                board = board.flip(random.nextInt(board.faces.size))
                taps++
                assertTrue("still not done after $taps taps", taps < 100_000)
            }
        }
    }

    @Test
    fun `someone who remembers every card wins in a handful of taps`() {
        for (seed in 0 until 50) {
            var board = MemoryBoard.deal(pairs = 4, kinds = 7, random = Random(seed))
            val seen = mutableMapOf<Int, Int>()
            var taps = 0
            while (!board.won) {
                val partner = board.open.singleOrNull()?.let { open ->
                    seen.entries.firstOrNull { it.value == board.faces[open] && it.key != open }?.key
                }
                val known = seen.entries.groupBy { it.value }.values.firstOrNull { pair ->
                    pair.size == 2 && pair.none { it.key in board.matched }
                }?.first()?.key
                val card = partner
                    ?: known.takeIf { board.open.size != 1 }
                    ?: board.faces.indices.first { it !in seen && it !in board.open }
                board = board.flip(card)
                seen[card] = board.faces[card]
                taps++
            }
            assertTrue("took $taps taps", taps <= 16)
        }
    }

    @Test
    fun `wrong drops change nothing, right ones count`() {
        val pile = SockPile(listOf(0, 1, 2, 0, 1, 2))
        assertEquals(6, pile.drop(0, 1).left)
        assertEquals(6, pile.drop(0, 2).left)
        assertEquals(6, pile.drop(0, null).left)
        val better = pile.drop(0, 0)
        assertEquals(5, better.left)
        assertEquals(1, better.inDrawer(0))
    }

    @Test
    fun `every sock in its own drawer wins`() {
        var pile = SockPile.deal(drawers = 3, perDrawer = 2)
        assertEquals(6, pile.left)
        for (sock in pile.kinds.indices) {
            assertFalse(pile.won)
            pile = pile.drop(sock, pile.kinds[sock])
        }
        assertTrue(pile.won)
    }

    @Test
    fun `any number of wrong drops can't stop a win`() {
        var pile = SockPile.deal(drawers = 3, perDrawer = 2)
        for (sock in pile.kinds.indices) {
            repeat(50) { pile = pile.drop(sock, (pile.kinds[sock] + 1) % 3) }
            pile = pile.drop(sock, pile.kinds[sock])
        }
        assertTrue(pile.won)
    }
}
