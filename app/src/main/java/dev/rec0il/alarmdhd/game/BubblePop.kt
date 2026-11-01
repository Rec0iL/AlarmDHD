package dev.rec0il.alarmdhd.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

/** Pop every bubble. They stay on screen until popped, so there's nothing to miss and no way to lose. */
object BubblePop : MiniGame {
    override val id = "bubble_pop"
    override val title = R.string.game_bubble_pop

    private const val COLUMNS = 3
    private const val ROWS = 4
    private const val BUBBLES = COLUMNS * ROWS
    private const val WIN_PAUSE_MS = 400L
    private val BUBBLE_SIZE = 88.dp
    private val COLORS = listOf(
        Color(0xFFFFB454),
        Color(0xFFFF8A80),
        Color(0xFF7FC8F8),
        Color(0xFFB9A2FF),
        Color(0xFF8EE3B0),
    )

    private class Bubble(val id: Int, val column: Int, val row: Int, val jitterX: Float, val jitterY: Float, val color: Color, val bobMs: Int)

    private fun spawn(): List<Bubble> = List(BUBBLES) { index ->
        Bubble(
            id = index,
            column = index % COLUMNS,
            row = index / COLUMNS,
            jitterX = Random.nextFloat(),
            jitterY = Random.nextFloat(),
            color = COLORS[Random.nextInt(COLORS.size)],
            bobMs = Random.nextInt(1600, 2800),
        )
    }

    @Composable
    override fun Play(onWin: () -> Unit) {
        val bubbles = remember { mutableStateListOf<Bubble>().apply { addAll(spawn()) } }
        var left by remember { mutableIntStateOf(BUBBLES) }

        LaunchedEffect(left) {
            if (left == 0) {
                delay(WIN_PAUSE_MS)
                onWin()
            }
        }

        Column(Modifier.fillMaxSize()) {
            Text(
                pluralStringResource(R.plurals.game_bubbles_left, left, left),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cellWidth = maxWidth / COLUMNS
                val cellHeight = maxHeight / ROWS
                for (bubble in bubbles) {
                    key(bubble.id) {
                        val spareX = (cellWidth - BUBBLE_SIZE).coerceAtLeast(0.dp)
                        val spareY = (cellHeight - BUBBLE_SIZE).coerceAtLeast(0.dp)
                        BubbleView(
                            bubble = bubble,
                            x = cellWidth * bubble.column + spareX * bubble.jitterX,
                            y = cellHeight * bubble.row + spareY * bubble.jitterY,
                            onPopped = { left-- },
                            onGone = { bubbles.remove(bubble) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun BubbleView(bubble: Bubble, x: Dp, y: Dp, onPopped: () -> Unit, onGone: () -> Unit) {
        var popping by remember { mutableStateOf(false) }
        val haptics = LocalHapticFeedback.current
        val bob by rememberInfiniteTransition(label = "bob").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(bubble.bobMs), RepeatMode.Reverse),
            label = "bob",
        )
        val scale by animateFloatAsState(
            targetValue = if (popping) 1.6f else 1f,
            animationSpec = tween(180),
            finishedListener = { if (popping) onGone() },
            label = "scale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (popping) 0f else 1f,
            animationSpec = tween(180),
            label = "alpha",
        )
        val description = stringResource(R.string.game_bubble)
        val pop = {
            if (!popping) {
                popping = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPopped()
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(x.roundToPx(), y.roundToPx() + (bob * 6.dp.toPx()).roundToInt()) }
                .size(BUBBLE_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { pop() }
                .semantics { contentDescription = description },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(bubble.color.copy(alpha = 0.15f), bubble.color.copy(alpha = 0.55f)),
                        center = center,
                        radius = radius,
                    ),
                )
                drawCircle(bubble.color, radius = radius - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                drawCircle(
                    Color.White.copy(alpha = 0.8f),
                    radius = radius * 0.16f,
                    center = Offset(center.x - radius * 0.38f, center.y - radius * 0.42f),
                )
            }
        }
    }
}
