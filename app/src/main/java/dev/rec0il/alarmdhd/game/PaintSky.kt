package dev.rec0il.alarmdhd.game

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rec0il.alarmdhd.R
import kotlin.random.Random

/** Paint the night sky in any colours at all. Paint stays where it lands, and every colour counts. */
object PaintSky : MiniGame {
    override val id = "paint_sky"
    override val title = R.string.game_paint_sky

    private const val COLUMNS = 5
    private const val ROWS = 8
    private const val ROWS_PER_SWEEP = 2
    private const val PAINTED_ENOUGH = 0.75f
    private const val STARS = 50
    private const val WIN_PAUSE_MS = 1400L
    private val BRUSH_RADIUS = 44.dp
    private val SWATCH_HEIGHT = 64.dp
    private val NIGHT_TOP = Color(0xFF0B1030)
    private val NIGHT_BOTTOM = Color(0xFF1E2552)
    private val SUN = Color(0xFFFFF0B8)
    private val HILL_FAR = Color(0xFF34284E)
    private val HILL_NEAR = Color(0xFF17132A)

    private class Paint(val color: Color, @param:StringRes val name: Int)

    private class PaintedStroke(val stroke: BrushStroke, val color: Color)

    private val PAINTS = listOf(
        Paint(Color(0xFFFFB454), R.string.game_paint_orange),
        Paint(Color(0xFFFF8A80), R.string.game_paint_coral),
        Paint(Color(0xFFFFE08A), R.string.game_paint_yellow),
        Paint(Color(0xFF7FC8F8), R.string.game_paint_blue),
        Paint(Color(0xFFB9A2FF), R.string.game_paint_violet),
    )

    @Composable
    override fun Play(onWin: () -> Unit) {
        var sky by remember { mutableStateOf(Coverage(COLUMNS, ROWS)) }
        var paint by remember { mutableStateOf(PAINTS.first()) }
        val painted = remember { mutableStateListOf<PaintedStroke>() }
        val stars = remember { List(STARS) { Offset(Random.nextFloat(), Random.nextFloat()) } }
        val done = sky.fraction >= PAINTED_ENOUGH
        val progress = percentStep(sky.fraction / PAINTED_ENOUGH)
        val sunUp by animateFloatAsState(if (done) 1f else 0f, tween(WIN_PAUSE_MS.toInt()), label = "sun")
        val haptics = LocalHapticFeedback.current

        WinWhen(done, WIN_PAUSE_MS, onWin)
        LaunchedEffect(progress) {
            if (progress > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (done) stringResource(R.string.game_paint_done) else stringResource(R.string.game_paint_status, progress),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                val radius = with(LocalDensity.current) { BRUSH_RADIUS.toPx() }
                val brush = Size(radius / constraints.maxWidth, radius / constraints.maxHeight)
                val brushOn = { stroke: BrushStroke ->
                    painted.add(PaintedStroke(stroke, paint.color))
                    sky = sky.stroke(stroke, brush)
                }
                val description = stringResource(R.string.game_paint_description)
                val action = stringResource(R.string.game_paint_action)
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .brushInput(brushOn)
                        .semantics {
                            contentDescription = description
                            onClick(label = action) {
                                repeat(ROWS_PER_SWEEP) {
                                    val y = sky.nextRowY()
                                    brushOn(BrushStroke(Offset(0f, y), Offset(1f, y)))
                                }
                                true
                            }
                        },
                ) {
                    drawRect(Brush.verticalGradient(listOf(NIGHT_TOP, NIGHT_BOTTOM)))
                    for (star in stars) {
                        drawCircle(Color.White.copy(alpha = 0.5f), 1.5.dp.toPx(), Offset(star.x * size.width, star.y * size.height))
                    }
                    for (line in painted) drawBrushStroke(line.stroke, radius, line.color)
                    if (sunUp > 0f) {
                        val sunRadius = size.minDimension * 0.15f
                        val sun = Offset(size.width / 2, size.height * (0.95f - 0.3f * sunUp))
                        drawCircle(
                            Brush.radialGradient(listOf(SUN.copy(alpha = 0.7f), Color.Transparent), sun, sunRadius * 3),
                            radius = sunRadius * 3,
                            center = sun,
                        )
                        drawCircle(SUN, sunRadius, sun)
                    }
                    drawHills(HILL_FAR, HILL_NEAR)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .selectableGroup(),
            ) {
                for (choice in PAINTS) {
                    val name = stringResource(choice.name)
                    val isChosen = choice == paint
                    Box(
                        Modifier
                            .weight(1f)
                            .height(SWATCH_HEIGHT)
                            .clip(RoundedCornerShape(16.dp))
                            .background(choice.color)
                            .border(
                                width = if (isChosen) 4.dp else 0.dp,
                                color = if (isChosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = RoundedCornerShape(16.dp),
                            )
                            .selectable(
                                selected = isChosen,
                                role = Role.RadioButton,
                                onClick = {
                                    paint = choice
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                            )
                            .semantics { contentDescription = name },
                    )
                }
            }
        }
    }
}
