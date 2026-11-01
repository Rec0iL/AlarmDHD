package dev.rec0il.alarmdhd.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlin.math.abs

/** Hold the can over the plant until it blooms. It never wilts, so stopping only pauses the watering. */
object WaterPlant : MiniGame {
    override val id = "water_plant"
    override val title = R.string.game_water_plant

    private const val WATER_MS = 5000f

    /** What one tap pours, so holding is optional and TalkBack's double-tap works too. */
    private const val SPLASH_MS = 1000f

    /** How far the spout sticks out from the middle of the can, as a fraction of the width. */
    private const val SPOUT = 0.17f
    private const val OVER_PLANT = 0.2f
    private const val CAN_HIGHEST = 0.1f
    private const val CAN_LOWEST = 0.22f
    private const val WIN_PAUSE_MS = 900L
    private val START = Offset(0.1f, 0.14f)
    private val TALKBACK_SPOT = Offset(0.36f, 0.14f)
    private val CAN = Color(0xFF7FC8F8)
    private val CAN_DARK = Color(0xFF3D6E8F)
    private val WATER = Color(0xFF9AD6FF)
    private val POT = Color(0xFFD9825B)
    private val POT_DARK = Color(0xFFB0653F)
    private val SOIL = Color(0xFF3A2A1E)
    private val LEAF = Color(0xFF8EE3B0)
    private val STEM = Color(0xFF5DBB84)
    private val PETAL = Color(0xFFFF8A80)
    private val HEART = Color(0xFFFFD27F)

    private fun isOverPlant(canX: Float): Boolean {
        val spout = canX + if (canX < 0.5f) SPOUT else -SPOUT
        return abs(spout - 0.5f) <= OVER_PLANT
    }

    @Composable
    override fun Play(onWin: () -> Unit) {
        var growth by remember { mutableFloatStateOf(0f) }
        var can by remember { mutableStateOf(START) }
        var holding by remember { mutableStateOf(false) }
        var splash by remember { mutableFloatStateOf(0f) }
        val grown = growth >= 1f
        val pouring = !grown && isOverPlant(can.x) && (holding || splash > 0f)
        val progress = percentStep(growth)
        val shownCan by animateOffsetAsState(can, spring(stiffness = Spring.StiffnessMediumLow), label = "can")
        val facing by animateFloatAsState(if (shownCan.x < 0.5f) 1f else -1f, tween(150), label = "facing")
        val tilt by animateFloatAsState(if (pouring) 1f else 0f, tween(200), label = "tilt")
        val drops by rememberInfiniteTransition(label = "drops").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing)),
            label = "drops",
        )
        val haptics = LocalHapticFeedback.current

        WinWhen(grown, WIN_PAUSE_MS, onWin)
        LaunchedEffect(pouring) {
            if (!pouring) return@LaunchedEffect
            var last = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                val step = (now - last).toFloat()
                last = now
                growth = (growth + step / WATER_MS).coerceAtMost(1f)
                splash = (splash - step).coerceAtLeast(0f)
            }
        }
        LaunchedEffect(progress) {
            if (progress > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        val description = stringResource(R.string.game_plant_description)
        val action = stringResource(R.string.game_plant_water)
        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (grown) stringResource(R.string.game_plant_done) else stringResource(R.string.game_plant_status, progress),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        fun moveCan(position: Offset) {
                            can = Offset(
                                (position.x / size.width).coerceIn(0f, 1f),
                                (position.y / size.height).coerceIn(CAN_HIGHEST, CAN_LOWEST),
                            )
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            moveCan(down.position)
                            holding = true
                            splash = SPLASH_MS
                            drag(down.id) { change ->
                                moveCan(change.position)
                                change.consume()
                            }
                            holding = false
                        }
                    }
                    .semantics {
                        contentDescription = description
                        onClick(label = action) {
                            can = TALKBACK_SPOT
                            splash = SPLASH_MS
                            true
                        }
                    },
            ) {
                drawPlant(growth)
                val canCenter = Offset(shownCan.x * size.width, shownCan.y * size.height)
                if (pouring && tilt > 0.7f) {
                    val spout = Offset(canCenter.x + SPOUT * size.width * facing, canCenter.y)
                    drawWater(spout, drops)
                }
                drawCan(canCenter, facing, tilt)
            }
        }
    }

    private fun DrawScope.drawWater(spout: Offset, drops: Float) {
        val soil = size.height * 0.84f
        for (drop in 0 until 7) {
            val fall = (drops + drop / 7f) % 1f
            for (side in listOf(-1f, 0f, 1f)) {
                drawCircle(
                    WATER,
                    4.dp.toPx(),
                    Offset(spout.x + side * (6.dp.toPx() + fall * 10.dp.toPx()), spout.y + (soil - spout.y) * fall),
                )
            }
        }
    }

    private fun DrawScope.drawCan(center: Offset, facing: Float, tilt: Float) {
        val body = size.width * 0.2f
        val spoutEnd = size.width * SPOUT
        scale(facing, 1f, center) {
            rotate(tilt * 16f, center) {
                drawArc(
                    CAN_DARK,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(center.x - body * 0.42f, center.y - body * 0.85f),
                    size = Size(body * 0.84f, body * 0.85f),
                    style = Stroke(5.dp.toPx()),
                )
                drawPath(
                    Path().apply {
                        moveTo(center.x + body * 0.4f, center.y + body * 0.3f)
                        lineTo(center.x + spoutEnd, center.y - body * 0.18f)
                        lineTo(center.x + spoutEnd, center.y - body * 0.02f)
                        lineTo(center.x + body * 0.4f, center.y + body * 0.48f)
                        close()
                    },
                    CAN,
                )
                drawLine(
                    CAN_DARK,
                    Offset(center.x + spoutEnd, center.y - body * 0.26f),
                    Offset(center.x + spoutEnd, center.y + body * 0.06f),
                    8.dp.toPx(),
                    StrokeCap.Round,
                )
                drawRoundRect(
                    CAN,
                    Offset(center.x - body / 2, center.y - body * 0.4f),
                    Size(body, body),
                    CornerRadius(10.dp.toPx()),
                )
            }
        }
    }

    private fun DrawScope.drawPlant(growth: Float) {
        val middle = size.width / 2
        val potWidth = minOf(size.width * 0.38f, size.height * 0.24f)
        val potHeight = potWidth * 0.75f
        val potTop = size.height - potHeight - 8.dp.toPx()
        val soil = potTop + 6.dp.toPx()
        val tallest = (potTop - size.height * 0.34f).coerceAtLeast(0f)
        val tip = soil - (0.1f + 0.9f * growth) * tallest
        drawLine(STEM, Offset(middle, soil), Offset(middle, tip), 8.dp.toPx(), StrokeCap.Round)

        val leafSize = potWidth * 0.34f
        for (leaf in 0 until 6) {
            val startsAt = leaf * 0.13f
            val grownIn = ((growth - startsAt) / 0.2f).coerceIn(0f, 1f)
            if (grownIn == 0f && leaf > 1) continue
            val side = if (leaf % 2 == 0) -1f else 1f
            val y = soil - (soil - tip) * (0.3f + leaf * 0.11f).coerceAtMost(0.9f)
            val scale = (0.35f + 0.65f * grownIn) * leafSize
            rotate(side * -30f, Offset(middle, y)) {
                drawOval(
                    LEAF,
                    Offset(middle + if (side < 0) -scale else 0f, y - scale * 0.24f),
                    Size(scale, scale * 0.48f),
                )
            }
        }
        val bloom = ((growth - 0.72f) / 0.28f).coerceIn(0f, 1f)
        if (bloom > 0f) {
            val petal = potWidth * 0.26f * bloom
            for (angle in 0 until 6) {
                rotate(angle * 60f, Offset(middle, tip)) {
                    drawOval(PETAL, Offset(middle - petal * 0.42f, tip - petal * 1.3f), Size(petal * 0.84f, petal * 1.1f))
                }
            }
            drawCircle(HEART, petal * 0.5f, Offset(middle, tip))
        }

        drawPath(
            Path().apply {
                moveTo(middle - potWidth / 2, potTop)
                lineTo(middle + potWidth / 2, potTop)
                lineTo(middle + potWidth * 0.36f, potTop + potHeight)
                lineTo(middle - potWidth * 0.36f, potTop + potHeight)
                close()
            },
            POT,
        )
        drawRoundRect(
            POT_DARK,
            Offset(middle - potWidth * 0.55f, potTop - 4.dp.toPx()),
            Size(potWidth * 1.1f, 16.dp.toPx()),
            CornerRadius(6.dp.toPx()),
        )
        drawRect(SOIL, Offset(middle - potWidth * 0.46f, potTop - 4.dp.toPx()), Size(potWidth * 0.92f, 5.dp.toPx()))
    }
}
