package dev.rec0il.alarmdhd.game

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The line above the play area: what to do and how far along it is. TalkBack reads out every change. */
@Composable
internal fun GameStatus(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Leaves a moment for the last move to land, then ends the game. */
@Composable
internal fun WinWhen(won: Boolean, pauseMs: Long, onWin: () -> Unit) {
    LaunchedEffect(won) {
        if (won) {
            delay(pauseMs)
            onWin()
        }
    }
}

/** Progress for the status line, in steps of ten so TalkBack isn't talking over a moving finger. */
internal fun percentStep(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 10).toInt() * 10

/** One piece of a brush's path, in fractions of the area being brushed. A tap is a piece with no length. */
internal class BrushStroke(val from: Offset, val to: Offset)

/** Which parts of an area a brush has been over. It only ever grows, so no stroke is wasted. */
internal class Coverage(private val columns: Int, private val rows: Int, private val covered: Set<Int> = emptySet()) {
    val fraction: Float get() = covered.size.toFloat() / (columns * rows)

    /** [radius] is the brush's size in fractions of the area, which differ when the area isn't square. */
    fun stroke(stroke: BrushStroke, radius: Size): Coverage {
        val fromX = stroke.from.x / radius.width
        val fromY = stroke.from.y / radius.height
        val alongX = stroke.to.x / radius.width - fromX
        val alongY = stroke.to.y / radius.height - fromY
        val length = alongX * alongX + alongY * alongY
        val now = covered.toMutableSet()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = (column + 0.5f) / columns / radius.width - fromX
                val y = (row + 0.5f) / rows / radius.height - fromY
                val nearest = if (length == 0f) 0f else ((x * alongX + y * alongY) / length).coerceIn(0f, 1f)
                val offX = x - alongX * nearest
                val offY = y - alongY * nearest
                if (offX * offX + offY * offY <= 1f) now += row * columns + column
            }
        }
        return Coverage(columns, rows, now)
    }

    /** The middle of the first row with something left to cover, for playing without dragging. */
    fun nextRowY(): Float {
        val row = (0 until rows).firstOrNull { row -> (0 until columns).any { row * columns + it !in covered } } ?: 0
        return (row + 0.5f) / rows
    }
}

/** Reports where a finger goes as [BrushStroke]s, starting the moment it touches down. */
internal fun Modifier.brushInput(onStroke: (BrushStroke) -> Unit): Modifier = pointerInput(Unit) {
    val minStep = 4.dp.toPx()
    fun fraction(position: Offset) = Offset(position.x / size.width, position.y / size.height)
    awaitEachGesture {
        val down = awaitFirstDown()
        var last = down.position
        down.consume()
        onStroke(BrushStroke(fraction(last), fraction(last)))
        drag(down.id) { change ->
            if ((change.position - last).getDistance() >= minStep) {
                onStroke(BrushStroke(fraction(last), fraction(change.position)))
                last = change.position
            }
            change.consume()
        }
    }
}

internal fun DrawScope.drawBrushStroke(
    stroke: BrushStroke,
    radius: Float,
    color: Color,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    val from = Offset(stroke.from.x * size.width, stroke.from.y * size.height)
    val to = Offset(stroke.to.x * size.width, stroke.to.y * size.height)
    // A line with no length draws nothing, so the circle is what makes a tap show up.
    drawCircle(color, radius, from, blendMode = blendMode)
    if (from != to) drawLine(color, from, to, radius * 2, StrokeCap.Round, blendMode = blendMode)
}

/** Two rows of hills along the bottom of whatever is being drawn. */
internal fun DrawScope.drawHills(far: Color, near: Color) {
    val width = size.width
    val height = size.height
    drawPath(
        Path().apply {
            moveTo(0f, height * 0.84f)
            cubicTo(width * 0.25f, height * 0.76f, width * 0.45f, height * 0.9f, width * 0.68f, height * 0.83f)
            cubicTo(width * 0.82f, height * 0.79f, width * 0.92f, height * 0.8f, width, height * 0.84f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        },
        far,
    )
    drawPath(
        Path().apply {
            moveTo(0f, height * 0.93f)
            cubicTo(width * 0.3f, height * 0.86f, width * 0.6f, height * 0.97f, width, height * 0.89f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        },
        near,
    )
}
