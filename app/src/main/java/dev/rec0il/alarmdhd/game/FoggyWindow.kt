package dev.rec0il.alarmdhd.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlin.random.Random

/** Wipe the fog off a window to find the sunrise. Wiped glass stays clear, and the last bit melts by itself. */
object FoggyWindow : MiniGame {
    override val id = "foggy_window"
    override val title = R.string.game_foggy_window

    private const val COLUMNS = 5
    private const val ROWS = 8
    private const val ROWS_PER_SWEEP = 2
    private const val CLEAR_ENOUGH = 0.8f
    private const val DROPLETS = 90
    private const val WIN_PAUSE_MS = 1400L
    private val BRUSH_RADIUS = 46.dp
    private val FOG_TOP = Color(0xFF8C95A8)
    private val FOG_BOTTOM = Color(0xFF6F7890)
    private val FRAME = Color(0xFF4A3214)
    private val SKY = listOf(Color(0xFF2B2A5A), Color(0xFF8C4A7C), Color(0xFFFF9E57), Color(0xFFFFD27F))
    private val SUN = Color(0xFFFFF0B8)
    private val HILL_FAR = Color(0xFF4A3A5E)
    private val HILL_NEAR = Color(0xFF241F38)

    @Composable
    override fun Play(onWin: () -> Unit) {
        var fog by remember { mutableStateOf(Coverage(COLUMNS, ROWS)) }
        val wiped = remember { mutableStateListOf<BrushStroke>() }
        val droplets = remember { List(DROPLETS) { Offset(Random.nextFloat(), Random.nextFloat()) } }
        val clear = fog.fraction >= CLEAR_ENOUGH
        val progress = percentStep(fog.fraction / CLEAR_ENOUGH)
        val fogAlpha by animateFloatAsState(if (clear) 0f else 1f, tween(800), label = "fog")
        val sunUp by animateFloatAsState(if (clear) 1f else 0f, tween(WIN_PAUSE_MS.toInt()), label = "sun")
        val haptics = LocalHapticFeedback.current

        WinWhen(clear, WIN_PAUSE_MS, onWin)
        LaunchedEffect(progress) {
            if (progress > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (clear) stringResource(R.string.game_fog_done) else stringResource(R.string.game_fog_status, progress),
            )
            BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
                val radius = with(LocalDensity.current) { BRUSH_RADIUS.toPx() }
                val brush = Size(radius / constraints.maxWidth, radius / constraints.maxHeight)
                val wipe = { stroke: BrushStroke ->
                    wiped.add(stroke)
                    fog = fog.stroke(stroke, brush)
                }
                val description = stringResource(R.string.game_fog_window)
                val action = stringResource(R.string.game_fog_wipe)
                Box(
                    Modifier
                        .fillMaxSize()
                        .brushInput(wipe)
                        .semantics {
                            contentDescription = description
                            onClick(label = action) {
                                repeat(ROWS_PER_SWEEP) {
                                    val y = fog.nextRowY()
                                    wipe(BrushStroke(Offset(0f, y), Offset(1f, y)))
                                }
                                true
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawSunrise(sunUp) }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                alpha = fogAlpha
                            },
                    ) {
                        drawRect(Brush.verticalGradient(listOf(FOG_TOP, FOG_BOTTOM)))
                        for (droplet in droplets) {
                            drawCircle(
                                Color.White.copy(alpha = 0.12f),
                                radius = 2.dp.toPx() + droplet.x * 3.dp.toPx(),
                                center = Offset(droplet.x * size.width, droplet.y * size.height),
                            )
                        }
                        for (stroke in wiped) drawBrushStroke(stroke, radius, Color.Black, BlendMode.Clear)
                    }
                    Canvas(Modifier.fillMaxSize()) { drawFrame() }
                }
            }
        }
    }

    private fun DrawScope.drawSunrise(sunUp: Float) {
        drawRect(Brush.verticalGradient(SKY))
        val sunRadius = size.minDimension * 0.16f
        val sun = Offset(size.width / 2, size.height * (0.76f - 0.12f * sunUp))
        drawCircle(
            Brush.radialGradient(listOf(SUN.copy(alpha = 0.6f), Color.Transparent), sun, sunRadius * 3),
            radius = sunRadius * 3,
            center = sun,
        )
        drawCircle(SUN, sunRadius, sun)
        drawHills(HILL_FAR, HILL_NEAR)
    }

    private fun DrawScope.drawFrame() {
        val thick = 10.dp.toPx()
        drawRoundRect(FRAME, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(thick * 2))
        drawLine(FRAME, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), thick)
        drawLine(FRAME, Offset(0f, size.height * 0.45f), Offset(size.width, size.height * 0.45f), thick)
    }
}
