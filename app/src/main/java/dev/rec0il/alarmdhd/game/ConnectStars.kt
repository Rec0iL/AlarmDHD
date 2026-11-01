package dev.rec0il.alarmdhd.game

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Only the next star in order counts. Tapping any other one does nothing at all. */
internal class StarTrail(val count: Int, val next: Int = 0) {
    val done: Boolean get() = next >= count

    fun tap(star: Int): StarTrail = if (star == next) StarTrail(count, next + 1) else this
}

/** Star positions run from 0 to 1 across the space the stars can sit in. [closesTo] is the star the last one joins up with. */
internal class Constellation(@param:StringRes val name: Int, val points: List<Offset>, val closesTo: Int) {
    /** Keeps whole tap targets of size [target] on screen. Works in whatever unit it's given. */
    fun centers(width: Float, height: Float, target: Float): List<Offset> = points.map {
        Offset(target / 2 + it.x * (width - target), target / 2 + it.y * (height - target))
    }
}

object ConnectStars : MiniGame {
    override val id = "connect_stars"
    override val title = R.string.game_connect_stars

    internal val MIN_TARGET = 76.dp
    private val MAX_TARGET = 96.dp
    private const val BACKGROUND_STARS = 40
    private const val WIN_PAUSE_MS = 1100L
    private val NIGHT_TOP = Color(0xFF0B1030)
    private val NIGHT_BOTTOM = Color(0xFF1E2552)
    private val LIT = Color(0xFFFFD27F)
    private val DIM = Color(0xFF8E96B8)
    private val LINE = Color(0xFFFFE9B8)
    private val ON_LIT = Color(0xFF2B1800)

    internal val SHAPES = listOf(
        Constellation(
            R.string.game_stars_heart,
            listOf(
                Offset(0.50f, 0.22f),
                Offset(0.78f, 0.00f),
                Offset(1.00f, 0.30f),
                Offset(0.78f, 0.62f),
                Offset(0.50f, 1.00f),
                Offset(0.22f, 0.62f),
                Offset(0.00f, 0.30f),
                Offset(0.22f, 0.00f),
            ),
            closesTo = 0,
        ),
        Constellation(
            R.string.game_stars_dipper,
            listOf(
                Offset(0.00f, 0.02f),
                Offset(0.33f, 0.14f),
                Offset(0.60f, 0.32f),
                Offset(0.42f, 0.58f),
                Offset(0.96f, 0.60f),
                Offset(1.00f, 0.98f),
                Offset(0.42f, 0.96f),
            ),
            closesTo = 3,
        ),
        Constellation(
            R.string.game_stars_crown,
            listOf(
                Offset(0.00f, 1.00f),
                Offset(0.00f, 0.25f),
                Offset(0.27f, 0.62f),
                Offset(0.50f, 0.05f),
                Offset(0.73f, 0.62f),
                Offset(1.00f, 0.25f),
                Offset(1.00f, 1.00f),
            ),
            closesTo = 0,
        ),
    )

    @Composable
    override fun Play(onWin: () -> Unit) {
        val shape = remember { SHAPES.random() }
        var trail by remember { mutableStateOf(StarTrail(shape.points.size)) }
        val sky = remember { List(BACKGROUND_STARS) { Offset(Random.nextFloat(), Random.nextFloat()) } }
        val grow = remember(trail.next) { Animatable(0f) }
        LaunchedEffect(trail.next) { grow.animateTo(1f, tween(220)) }
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "pulse",
        )
        val haptics = LocalHapticFeedback.current

        WinWhen(trail.done, WIN_PAUSE_MS, onWin)

        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (trail.done) {
                    stringResource(R.string.game_stars_done, stringResource(shape.name))
                } else {
                    stringResource(R.string.game_stars_status, trail.next, trail.count)
                },
            )
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .semantics { isTraversalGroup = true },
            ) {
                val target = (minOf(maxWidth, maxHeight) / 4.5f).coerceIn(MIN_TARGET, MAX_TARGET)
                val centers = shape.centers(maxWidth.value, maxHeight.value, target.value)
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Brush.verticalGradient(listOf(NIGHT_TOP, NIGHT_BOTTOM)))
                    for (star in sky) {
                        drawCircle(
                            Color.White.copy(alpha = 0.25f),
                            1.5.dp.toPx(),
                            Offset(star.x * size.width, star.y * size.height),
                        )
                    }
                    val points = centers.map { Offset(it.x.dp.toPx(), it.y.dp.toPx()) }
                    for (index in 1 until trail.next) {
                        val isNewest = index == trail.next - 1
                        val end = if (isNewest) points[index - 1] + (points[index] - points[index - 1]) * grow.value else points[index]
                        drawLine(LINE, points[index - 1], end, 4.dp.toPx(), StrokeCap.Round)
                    }
                    if (trail.done) {
                        val start = points.last()
                        val end = start + (points[shape.closesTo] - start) * grow.value
                        drawLine(LINE, start, end, 4.dp.toPx(), StrokeCap.Round)
                    }
                }
                for ((index, center) in centers.withIndex()) {
                    StarView(
                        number = index + 1,
                        count = trail.count,
                        isConnected = index < trail.next,
                        isNext = index == trail.next,
                        pulse = pulse,
                        target = target,
                        position = DpOffset(center.x.dp - target / 2, center.y.dp - target / 2),
                        onTap = {
                            trail = trail.tap(index)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun StarView(
        number: Int,
        count: Int,
        isConnected: Boolean,
        isNext: Boolean,
        pulse: Float,
        target: Dp,
        position: DpOffset,
        onTap: () -> Unit,
    ) {
        val description = stringResource(R.string.game_star, number, count)
        val state = stringResource(
            when {
                isConnected -> R.string.game_star_connected
                isNext -> R.string.game_star_next
                else -> R.string.game_star_later
            },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(position.x, position.y)
                .size(target)
                .clickable(
                    enabled = isNext,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                )
                .semantics {
                    contentDescription = description
                    stateDescription = state
                    traversalIndex = number.toFloat()
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                if (isNext) {
                    drawCircle(
                        Brush.radialGradient(listOf(LIT.copy(alpha = 0.55f), Color.Transparent), center, radius),
                        radius = radius,
                    )
                }
                val scale = when {
                    isNext -> 0.86f + pulse * 0.14f
                    isConnected -> 0.8f
                    else -> 0.72f
                }
                val star = starPath(center, radius * scale)
                if (isConnected || isNext) {
                    drawPath(star, LIT)
                } else {
                    drawPath(star, DIM.copy(alpha = 0.25f))
                    drawPath(star, DIM, style = Stroke(2.dp.toPx()))
                }
            }
            Text(
                number.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = if (isConnected || isNext) ON_LIT else Color.White,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }

    private fun DrawScope.starPath(center: Offset, radius: Float): Path = Path().apply {
        for (point in 0 until 10) {
            val reach = if (point % 2 == 0) radius else radius * 0.58f
            val angle = -Math.PI / 2 + point * Math.PI / 5
            val x = center.x + reach * cos(angle).toFloat()
            val y = center.y + reach * sin(angle).toFloat()
            if (point == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
