package dev.rec0il.alarmdhd.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlin.math.cos
import kotlin.math.sin

/** Hold to pour until the mug is full. It can't overflow, and letting go only pauses the coffee. */
object PourCoffee : MiniGame {
    override val id = "pour_coffee"
    override val title = R.string.game_pour_coffee

    private const val POUR_MS = 5000f

    /** What one tap pours, so holding is optional and TalkBack's double-tap works too. */
    private const val SPLASH_MS = 1000f
    private const val TILT_DEGREES = 38f
    private const val WIN_PAUSE_MS = 900L
    private val COFFEE = Color(0xFF7A4A26)
    private val CREMA = Color(0xFFC79A63)
    private val POT = Color(0xFFFFB454)
    private val POT_DARK = Color(0xFFB97F34)
    private val GLASS = Color(0xFFE8EDF5)

    @Composable
    override fun Play(onWin: () -> Unit) {
        var fill by remember { mutableFloatStateOf(0f) }
        var holding by remember { mutableStateOf(false) }
        var splash by remember { mutableFloatStateOf(0f) }
        val full = fill >= 1f
        val pouring = !full && (holding || splash > 0f)
        val progress = percentStep(fill)
        val tilt by animateFloatAsState(if (pouring) 1f else 0f, tween(200), label = "tilt")
        val steam by rememberInfiniteTransition(label = "steam").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "steam",
        )
        val haptics = LocalHapticFeedback.current

        WinWhen(full, WIN_PAUSE_MS, onWin)
        LaunchedEffect(pouring) {
            if (!pouring) return@LaunchedEffect
            var last = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                val step = (now - last).toFloat()
                last = now
                fill = (fill + step / POUR_MS).coerceAtMost(1f)
                splash = (splash - step).coerceAtLeast(0f)
            }
        }
        LaunchedEffect(progress) {
            if (progress > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        val description = stringResource(R.string.game_coffee_pot)
        val action = stringResource(R.string.game_coffee_pour)
        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (full) stringResource(R.string.game_coffee_done) else stringResource(R.string.game_coffee_status, progress),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            holding = true
                            splash = SPLASH_MS
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            holding = false
                        }
                    }
                    .semantics {
                        contentDescription = description
                        onClick(label = action) {
                            splash = SPLASH_MS
                            true
                        }
                    },
            ) {
                val unit = minOf(size.width, size.height * 0.62f)
                val coffeeTop = drawMug(unit, fill, steam)
                val potRadius = unit * 0.17f
                val pot = Offset(size.width / 2 - potRadius * 1.2f, potRadius * 1.3f)
                val angle = TILT_DEGREES * tilt
                if (pouring && tilt > 0.7f) {
                    val spout = spoutTip(pot, potRadius, angle)
                    drawLine(COFFEE, spout, Offset(spout.x, coffeeTop), 9.dp.toPx(), StrokeCap.Round)
                }
                rotate(angle, pot) { drawPot(pot, potRadius) }
            }
        }
    }

    private fun spoutTip(pot: Offset, radius: Float, degrees: Float): Offset {
        val radians = Math.toRadians(degrees.toDouble())
        val x = 1.25f * radius
        val y = -0.35f * radius
        return Offset(
            pot.x + x * cos(radians).toFloat() - y * sin(radians).toFloat(),
            pot.y + x * sin(radians).toFloat() + y * cos(radians).toFloat(),
        )
    }

    /** Draws a glass mug at the bottom of the canvas and returns where the top of the coffee is. */
    private fun DrawScope.drawMug(unit: Float, fill: Float, steam: Float): Float {
        val width = unit * 0.56f
        val height = unit * 0.6f
        val left = (size.width - width) / 2
        val bottom = size.height - 16.dp.toPx()
        val top = bottom - height
        val wall = 6.dp.toPx()
        val corner = CornerRadius(14.dp.toPx())
        val coffeeTop = bottom - wall - (height - wall * 2) * fill
        drawRoundRect(GLASS.copy(alpha = 0.1f), Offset(left, top), Size(width, height), corner)
        if (fill > 0f) {
            drawRoundRect(
                COFFEE,
                Offset(left + wall, coffeeTop),
                Size(width - wall * 2, bottom - wall - coffeeTop),
                CornerRadius(8.dp.toPx()),
            )
            drawRect(CREMA, Offset(left + wall, coffeeTop), Size(width - wall * 2, (bottom - wall - coffeeTop).coerceAtMost(6.dp.toPx())))
        }
        drawRoundRect(GLASS, Offset(left, top), Size(width, height), corner, Stroke(wall))
        drawArc(
            GLASS,
            startAngle = -75f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(left + width - height * 0.22f, top + height * 0.2f),
            size = Size(height * 0.44f, height * 0.5f),
            style = Stroke(wall),
        )
        if (fill > 0.5f) {
            val strength = ((fill - 0.5f) * 2).coerceAtMost(1f)
            val wispHeight = 80.dp.toPx()
            for (wisp in listOf(0.2f, 0.8f)) {
                val path = Path()
                for (step in 0..12) {
                    val along = step / 12f
                    val x = left + width * wisp + sin((along + steam + wisp) * 6.28f) * 8.dp.toPx()
                    val y = top - 10.dp.toPx() - along * wispHeight
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color.White.copy(alpha = 0.4f * strength), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        return coffeeTop
    }

    private fun DrawScope.drawPot(center: Offset, radius: Float) {
        drawArc(
            POT_DARK,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - radius * 1.45f, center.y - radius * 0.6f),
            size = Size(radius * 1.2f, radius * 1.2f),
            style = Stroke(radius * 0.16f),
        )
        drawPath(
            Path().apply {
                moveTo(center.x + radius * 0.6f, center.y - radius * 0.1f)
                lineTo(center.x + radius * 1.25f, center.y - radius * 0.4f)
                lineTo(center.x + radius * 1.25f, center.y - radius * 0.28f)
                lineTo(center.x + radius * 0.75f, center.y + radius * 0.45f)
                close()
            },
            POT,
        )
        drawCircle(POT, radius * 0.95f, center)
        drawArc(
            POT_DARK,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(center.x - radius * 0.62f, center.y - radius * 1.14f),
            size = Size(radius * 1.24f, radius * 0.8f),
        )
        drawCircle(POT_DARK, radius * 0.13f, Offset(center.x, center.y - radius * 1.2f))
        drawCircle(Color.White.copy(alpha = 0.3f), radius * 0.18f, Offset(center.x - radius * 0.4f, center.y - radius * 0.3f))
    }
}
