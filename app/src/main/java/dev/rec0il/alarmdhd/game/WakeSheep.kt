package dev.rec0il.alarmdhd.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlin.math.cos
import kotlin.math.sin

/** Nudge every sheep until it's up. They stay awake once they're awake, and the sky gets lighter with each one. */
object WakeSheep : MiniGame {
    override val id = "wake_sheep"
    override val title = R.string.game_wake_sheep

    private const val COLUMNS = 2
    private const val ROWS = 3
    private const val SHEEP = COLUMNS * ROWS
    private const val NUDGES_TO_WAKE = 2
    private const val WIN_PAUSE_MS = 800L
    private val NIGHT_SKY = Color(0xFF141A33)
    private val DAWN_SKY = Color(0xFFE08A5E)
    private val NIGHT_GRASS = Color(0xFF1A2E26)
    private val DAWN_GRASS = Color(0xFF3F7A4C)
    private val WOOL = Color(0xFFF4F1EA)
    private val FACE = Color(0xFF3B3546)
    private val SNORE = Color(0xFFCDE8FB)

    @Composable
    override fun Play(onWin: () -> Unit) {
        val nudges = remember { mutableStateListOf<Int>().apply { repeat(SHEEP) { add(0) } } }
        val awake = nudges.count { it >= NUDGES_TO_WAKE }
        val morning by animateFloatAsState(awake.toFloat() / SHEEP, tween(500), label = "morning")
        val haptics = LocalHapticFeedback.current

        WinWhen(awake == SHEEP, WIN_PAUSE_MS, onWin)

        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (awake == SHEEP) stringResource(R.string.game_sheep_done) else pluralStringResource(R.plurals.game_sheep_status, awake, awake, SHEEP),
            )
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(lerp(NIGHT_SKY, DAWN_SKY, morning), size = Size(size.width, size.height * 0.2f))
                    drawRect(
                        Brush.verticalGradient(
                            listOf(lerp(NIGHT_SKY, DAWN_SKY, morning), lerp(NIGHT_GRASS, DAWN_GRASS, morning)),
                            startY = size.height * 0.1f,
                            endY = size.height * 0.3f,
                        ),
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    for (row in 0 until ROWS) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            for (column in 0 until COLUMNS) {
                                val sheep = row * COLUMNS + column
                                SheepView(
                                    number = sheep + 1,
                                    nudges = nudges[sheep],
                                    onNudge = {
                                        nudges[sheep]++
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SheepView(number: Int, nudges: Int, onNudge: () -> Unit, modifier: Modifier) {
        val rise by animateFloatAsState(
            (nudges.toFloat() / NUDGES_TO_WAKE).coerceAtMost(1f),
            spring(dampingRatio = 0.55f, stiffness = 300f),
            label = "rise",
        )
        val hop = remember { Animatable(0f) }
        LaunchedEffect(nudges) {
            if (nudges > 0) {
                hop.animateTo(1f, tween(110))
                hop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 500f))
            }
        }
        val snore by rememberInfiniteTransition(label = "snore").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400 + number * 170, easing = LinearEasing)),
            label = "snore",
        )
        val description = stringResource(R.string.game_sheep, number)
        val state = stringResource(
            when {
                nudges >= NUDGES_TO_WAKE -> R.string.game_sheep_awake
                nudges > 0 -> R.string.game_sheep_drowsy
                else -> R.string.game_sheep_asleep
            },
        )
        val action = stringResource(R.string.game_sheep_nudge)
        Canvas(
            modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = action,
                    onClick = onNudge,
                )
                .semantics {
                    contentDescription = description
                    stateDescription = state
                },
        ) {
            drawSheep(rise, hop.value, nudges, snore)
        }
    }

    private fun DrawScope.drawSheep(rise: Float, hop: Float, nudges: Int, snore: Float) {
        val unit = minOf(size.width, size.height * 1.15f)
        val ground = size.height * 0.92f
        val lifted = hop * unit * 0.09f
        val center = Offset(size.width / 2 + unit * 0.05f, ground - unit * (0.25f + 0.12f * rise) - lifted)
        drawOval(
            Color.Black.copy(alpha = 0.25f),
            Offset(center.x - unit * 0.34f, ground - unit * 0.05f),
            Size(unit * 0.68f, unit * 0.1f),
        )
        for (leg in listOf(-0.2f, -0.08f, 0.08f, 0.2f)) {
            val foot = lerpFloat(center.y + unit * 0.14f, ground - lifted, rise)
            drawLine(FACE, Offset(center.x + unit * leg, center.y + unit * 0.1f), Offset(center.x + unit * leg, foot), unit * 0.05f, StrokeCap.Round)
        }
        for (tuft in 0 until 8) {
            val angle = tuft * 0.785f
            drawCircle(
                WOOL,
                unit * 0.125f,
                Offset(center.x + cos(angle) * unit * 0.2f, center.y + sin(angle) * unit * 0.1f),
            )
        }
        drawOval(WOOL, Offset(center.x - unit * 0.26f, center.y - unit * 0.16f), Size(unit * 0.52f, unit * 0.32f))
        val head = Offset(center.x - unit * 0.3f, center.y - unit * 0.04f + (1f - rise) * unit * 0.12f)
        drawHead(head, unit * 1.25f, nudges)
        if (nudges == 0) {
            for (z in 0..2) {
                val phase = (snore + z / 3f) % 1f
                drawZ(
                    Offset(head.x + unit * (0.02f + phase * 0.14f), head.y - unit * (0.2f + phase * 0.36f)),
                    unit * (0.02f + phase * 0.03f),
                    SNORE.copy(alpha = 1f - phase),
                )
            }
        }
    }

    private fun DrawScope.drawHead(head: Offset, unit: Float, nudges: Int) {
        for (side in listOf(-1f, 1f)) {
            drawOval(
                FACE,
                Offset(head.x + side * unit * 0.1f - unit * 0.05f, head.y - unit * 0.1f),
                Size(unit * 0.1f, unit * 0.06f),
            )
        }
        drawOval(FACE, Offset(head.x - unit * 0.1f, head.y - unit * 0.13f), Size(unit * 0.2f, unit * 0.26f))
        drawCircle(WOOL, unit * 0.06f, Offset(head.x, head.y - unit * 0.12f))
        val eyeY = head.y - unit * 0.02f
        drawEye(Offset(head.x - unit * 0.05f, eyeY), unit, open = nudges >= NUDGES_TO_WAKE)
        drawEye(Offset(head.x + unit * 0.05f, eyeY), unit, open = nudges >= 1)
    }

    private fun DrawScope.drawEye(eye: Offset, unit: Float, open: Boolean) {
        if (open) {
            drawCircle(Color.White, unit * 0.032f, eye)
            drawCircle(Color.Black, unit * 0.016f, eye)
        } else {
            drawLine(Color.White, Offset(eye.x - unit * 0.028f, eye.y), Offset(eye.x + unit * 0.028f, eye.y), unit * 0.014f, StrokeCap.Round)
        }
    }

    private fun DrawScope.drawZ(center: Offset, half: Float, color: Color) {
        val width = 3.dp.toPx()
        val topLeft = Offset(center.x - half, center.y - half)
        val topRight = Offset(center.x + half, center.y - half)
        val bottomLeft = Offset(center.x - half, center.y + half)
        val bottomRight = Offset(center.x + half, center.y + half)
        drawLine(color, topLeft, topRight, width, StrokeCap.Round)
        drawLine(color, topRight, bottomLeft, width, StrokeCap.Round)
        drawLine(color, bottomLeft, bottomRight, width, StrokeCap.Round)
    }

    private fun lerpFloat(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
}
