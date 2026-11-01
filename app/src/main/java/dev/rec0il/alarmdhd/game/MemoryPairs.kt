package dev.rec0il.alarmdhd.game

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rec0il.alarmdhd.R
import kotlin.random.Random

/**
 * A wrong pair stays open until the next tap, so there's no memorising against the clock, and the
 * next tap closes it. Tries are unlimited and nothing is ever taken away.
 */
internal class MemoryBoard(val faces: List<Int>, val open: List<Int> = emptyList(), val matched: Set<Int> = emptySet()) {
    val pairsFound: Int get() = matched.size / 2
    val won: Boolean get() = matched.size == faces.size

    fun isFaceUp(card: Int): Boolean = card in open || card in matched

    fun flip(card: Int): MemoryBoard {
        if (card in matched) return this
        val stillOpen = if (open.size == 2) emptyList() else open
        if (card in stillOpen) return this
        val nowOpen = stillOpen + card
        return if (nowOpen.size == 2 && faces[nowOpen[0]] == faces[nowOpen[1]]) {
            MemoryBoard(faces, emptyList(), matched + nowOpen)
        } else {
            MemoryBoard(faces, nowOpen, matched)
        }
    }

    companion object {
        fun deal(pairs: Int, kinds: Int, random: Random = Random.Default): MemoryBoard {
            val chosen = (0 until kinds).shuffled(random).take(pairs)
            return MemoryBoard((chosen + chosen).shuffled(random))
        }
    }
}

object MemoryPairs : MiniGame {
    override val id = "memory_pairs"
    override val title = R.string.game_memory_pairs

    private const val COLUMNS = 2
    private const val PAIRS = 4
    private const val WIN_PAUSE_MS = 800L
    private val MATCHED = Color(0xFF8EE3B0)

    private class Face(val emoji: String, @param:StringRes val name: Int)

    private val FACES = listOf(
        Face("☀️", R.string.game_memory_sun),
        Face("🌙", R.string.game_memory_moon),
        Face("☕", R.string.game_memory_coffee),
        Face("🐑", R.string.game_memory_sheep),
        Face("🌱", R.string.game_memory_plant),
        Face("🎈", R.string.game_memory_balloon),
        Face("🧦", R.string.game_memory_sock),
    )

    @Composable
    override fun Play(onWin: () -> Unit) {
        var board by remember { mutableStateOf(MemoryBoard.deal(PAIRS, FACES.size)) }
        val haptics = LocalHapticFeedback.current

        WinWhen(board.won, WIN_PAUSE_MS, onWin)

        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (board.won) stringResource(R.string.game_memory_done) else pluralStringResource(R.plurals.game_memory_status, board.pairsFound, board.pairsFound, PAIRS),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                for (row in board.faces.indices.chunked(COLUMNS)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                        for (card in row) {
                            CardView(
                                number = card + 1,
                                face = FACES[board.faces[card]],
                                isFaceUp = board.isFaceUp(card),
                                isMatched = card in board.matched,
                                onFlip = {
                                    board = board.flip(card)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CardView(
        number: Int,
        face: Face,
        isFaceUp: Boolean,
        isMatched: Boolean,
        onFlip: () -> Unit,
        modifier: Modifier,
    ) {
        val turn by animateFloatAsState(if (isFaceUp) 180f else 0f, tween(240), label = "turn")
        val description = stringResource(R.string.game_memory_card, number)
        val faceName = stringResource(face.name)
        val state = when {
            isMatched -> stringResource(R.string.game_memory_matched, faceName)
            isFaceUp -> faceName
            else -> stringResource(R.string.game_memory_face_down)
        }
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier
                .clip(shape)
                .clickable(enabled = !isMatched, onClick = onFlip)
                .semantics {
                    contentDescription = description
                    stateDescription = state
                },
        ) {
            val showFace = turn > 90f
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = turn
                        cameraDistance = 16 * density
                    }
                    .clip(shape)
                    .background(
                        when {
                            isMatched -> MATCHED.copy(alpha = 0.22f)
                            showFace -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                    )
                    .border(2.dp, if (isMatched) MATCHED else MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                Text(
                    if (showFace) face.emoji else "?",
                    fontSize = 56.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .graphicsLayer { if (showFace) rotationY = 180f }
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}
