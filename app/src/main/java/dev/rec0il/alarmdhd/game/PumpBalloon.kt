package dev.rec0il.alarmdhd.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R

/** Tap anywhere to pump. The balloon never leaks and can't burst: when it's full it floats away. */
object PumpBalloon : MiniGame {
    override val id = "pump_balloon"
    override val title = R.string.game_pump_balloon

    private const val PUMPS = 12
    private const val WIN_PAUSE_MS = 1100L
    private val BALLOON = Color(0xFFFF8A80)
    private val PUMP = Color(0xFF7FC8F8)
    private val PUMP_DARK = Color(0xFF3D6E8F)
    private val HANDLE = Color(0xFFFFB454)
    private val HOSE = Color(0xFF6B7080)

    @Composable
    override fun Play(onWin: () -> Unit) {
        var pumps by remember { mutableIntStateOf(0) }
        val full = pumps >= PUMPS
        val growth by animateFloatAsState(
            pumps.toFloat() / PUMPS,
            spring(dampingRatio = 0.4f, stiffness = 300f),
            label = "growth",
        )
        val liftOff by animateFloatAsState(
            if (full) 1f else 0f,
            tween(WIN_PAUSE_MS.toInt(), easing = FastOutLinearInEasing),
            label = "liftOff",
        )
        val handle = remember { Animatable(0f) }
        val haptics = LocalHapticFeedback.current

        WinWhen(full, WIN_PAUSE_MS, onWin)
        LaunchedEffect(pumps) {
            if (pumps > 0) {
                handle.animateTo(1f, tween(70))
                handle.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f))
            }
        }

        val description = stringResource(R.string.game_pump_description)
        val action = stringResource(R.string.game_pump_action)
        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (full) stringResource(R.string.game_pump_done) else stringResource(R.string.game_pump_status, pumps, PUMPS),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = action,
                    ) {
                        if (!full) {
                            pumps++
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                    .semantics { contentDescription = description },
            ) {
                val nozzle = Offset(size.width * 0.33f, size.height * 0.6f)
                drawHose(nozzle)
                drawPump(handle.value)
                drawBalloon(nozzle, growth, liftOff)
            }
        }
    }

    private fun DrawScope.drawHose(nozzle: Offset) {
        drawPath(
            Path().apply {
                moveTo(size.width * 0.72f, size.height * 0.95f)
                cubicTo(size.width * 0.5f, size.height * 1.02f, nozzle.x, size.height * 0.92f, nozzle.x, nozzle.y)
            },
            HOSE,
            style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    private fun DrawScope.drawPump(pressed: Float) {
        val middle = size.width * 0.76f
        val barrelWidth = size.width * 0.17f
        val barrelTop = size.height * 0.68f
        val barrelBottom = size.height * 0.97f
        val handleTop = barrelTop - size.height * 0.18f * (1f - pressed * 0.85f)
        drawLine(PUMP_DARK, Offset(middle, handleTop), Offset(middle, barrelTop), 10.dp.toPx())
        drawRoundRect(
            HANDLE,
            Offset(middle - size.width * 0.17f, handleTop - 10.dp.toPx()),
            Size(size.width * 0.34f, 20.dp.toPx()),
            CornerRadius(10.dp.toPx()),
        )
        drawRoundRect(
            PUMP,
            Offset(middle - barrelWidth / 2, barrelTop),
            Size(barrelWidth, barrelBottom - barrelTop),
            CornerRadius(8.dp.toPx()),
        )
        drawRoundRect(
            PUMP_DARK,
            Offset(middle - size.width * 0.17f, barrelBottom - 6.dp.toPx()),
            Size(size.width * 0.34f, 14.dp.toPx()),
            CornerRadius(6.dp.toPx()),
        )
    }

    private fun DrawScope.drawBalloon(nozzle: Offset, growth: Float, liftOff: Float) {
        val width = minOf(size.width * (0.14f + 0.42f * growth), size.height * (0.1f + 0.34f * growth))
        val height = width * 1.2f
        val knot = Offset(nozzle.x, nozzle.y - 6.dp.toPx() - liftOff * size.height)
        val left = (knot.x - width / 2).coerceAtLeast(4.dp.toPx())
        val top = knot.y - height
        drawOval(BALLOON, Offset(left, top), Size(width, height))
        drawOval(
            Color.White.copy(alpha = 0.35f),
            Offset(left + width * 0.2f, top + height * 0.14f),
            Size(width * 0.2f, height * 0.26f),
        )
        val knotX = left + width / 2
        drawPath(
            Path().apply {
                moveTo(knotX - 7.dp.toPx(), knot.y + 8.dp.toPx())
                lineTo(knotX + 7.dp.toPx(), knot.y + 8.dp.toPx())
                lineTo(knotX, knot.y - 2.dp.toPx())
                close()
            },
            BALLOON,
        )
        if (liftOff > 0f) {
            drawLine(Color.White.copy(alpha = 0.6f), Offset(knotX, knot.y + 8.dp.toPx()), Offset(knotX, knot.y + 80.dp.toPx()), 2.dp.toPx())
        }
    }
}
