package dev.rec0il.alarmdhd.game

import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.rec0il.alarmdhd.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** [kinds] says which drawer each sock belongs in. A drop in any other drawer changes nothing. */
internal class SockPile(val kinds: List<Int>, val putAway: Set<Int> = emptySet()) {
    val left: Int get() = kinds.size - putAway.size
    val won: Boolean get() = left == 0

    fun fits(sock: Int, drawer: Int?): Boolean = kinds[sock] == drawer

    fun drop(sock: Int, drawer: Int?): SockPile = if (fits(sock, drawer)) SockPile(kinds, putAway + sock) else this

    fun inDrawer(drawer: Int): Int = putAway.count { kinds[it] == drawer }

    companion object {
        fun deal(drawers: Int, perDrawer: Int): SockPile =
            SockPile((0 until drawers).flatMap { drawer -> List(perDrawer) { drawer } }.shuffled())
    }
}

/** Drag each sock to the drawer showing the same sock. Wrong drops just bounce back, as often as it takes. */
object SockDrawer : MiniGame {
    override val id = "sock_drawer"
    override val title = R.string.game_sock_drawer

    private const val COLUMNS = 3
    private const val ROWS = 2
    private const val PER_DRAWER = 2
    private const val DRAWERS_SHARE = 0.36f
    private const val WIN_PAUSE_MS = 500L
    private val DROP_SLACK = 32.dp

    private enum class Pattern { STRIPES, DOTS, TOE }

    private class Kind(@param:StringRes val name: Int, val base: Color, val accent: Color, val pattern: Pattern)

    private val KINDS = listOf(
        Kind(R.string.game_sock_orange, Color(0xFFFFB454), Color(0xFFB9622B), Pattern.STRIPES),
        Kind(R.string.game_sock_blue, Color(0xFF7FC8F8), Color(0xFFF1F2F6), Pattern.DOTS),
        Kind(R.string.game_sock_purple, Color(0xFFB9A2FF), Color(0xFF5E43B8), Pattern.TOE),
    )

    @Composable
    override fun Play(onWin: () -> Unit) {
        var pile by remember { mutableStateOf(SockPile.deal(KINDS.size, PER_DRAWER)) }

        WinWhen(pile.won, WIN_PAUSE_MS, onWin)

        Column(Modifier.fillMaxSize()) {
            GameStatus(
                if (pile.won) stringResource(R.string.game_sock_done) else pluralStringResource(R.plurals.game_sock_status, pile.left, pile.left),
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()
                val drawersTop = height * (1f - DRAWERS_SHARE)
                val drawerWidth = width / KINDS.size
                val cellWidth = width / COLUMNS
                val cellHeight = drawersTop / ROWS
                val sockPx = minOf(cellWidth, cellHeight) * 0.92f
                val slack = with(density) { DROP_SLACK.toPx() }

                for (drawer in KINDS.indices) {
                    DrawerView(
                        kind = KINDS[drawer],
                        inside = pile.inDrawer(drawer),
                        modifier = Modifier
                            .offset { IntOffset((drawerWidth * drawer).roundToInt(), drawersTop.roundToInt()) }
                            .size(with(density) { drawerWidth.toDp() }, with(density) { (height - drawersTop).toDp() }),
                    )
                }
                for (sock in pile.kinds.indices) {
                    if (sock in pile.putAway) continue
                    key(sock) {
                        val home = Offset(
                            cellWidth * (sock % COLUMNS) + (cellWidth - sockPx) / 2,
                            cellHeight * (sock / COLUMNS) + (cellHeight - sockPx) / 2,
                        )
                        val kind = pile.kinds[sock]
                        SockView(
                            kind = KINDS[kind],
                            home = home,
                            sockPx = sockPx,
                            drawerSpot = Offset(drawerWidth * kind + (drawerWidth - sockPx) / 2, drawersTop),
                            fits = { center ->
                                val drawer = if (center.y >= drawersTop - slack) {
                                    (center.x / drawerWidth).toInt().coerceIn(KINDS.indices)
                                } else {
                                    null
                                }
                                pile.fits(sock, drawer)
                            },
                            onPutAway = { pile = pile.drop(sock, kind) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SockView(
        kind: Kind,
        home: Offset,
        sockPx: Float,
        drawerSpot: Offset,
        fits: (Offset) -> Boolean,
        onPutAway: () -> Unit,
    ) {
        var position by remember(home) { mutableStateOf(home) }
        var lifted by remember { mutableStateOf(false) }
        var isLanding by remember { mutableStateOf(false) }
        var flight by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val scale by animateFloatAsState(if (lifted) 1.12f else 1f, tween(100), label = "scale")
        val density = LocalDensity.current
        val description = stringResource(R.string.game_sock, stringResource(kind.name))
        val action = stringResource(R.string.game_sock_put_away)

        fun fly(to: Offset, spec: AnimationSpec<Offset>, then: () -> Unit = {}) {
            flight?.cancel()
            flight = scope.launch {
                animate(Offset.VectorConverter, position, to, animationSpec = spec) { value, _ -> position = value }
                then()
            }
        }

        fun land() {
            isLanding = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            fly(drawerSpot, tween(220), onPutAway)
        }

        Canvas(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .zIndex(if (lifted) 1f else 0f)
                .size(with(density) { sockPx.toDp() })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(home) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (isLanding) return@awaitEachGesture
                        down.consume()
                        flight?.cancel()
                        lifted = true
                        drag(down.id) { change ->
                            position += change.positionChange()
                            change.consume()
                        }
                        lifted = false
                        if (fits(position + Offset(sockPx / 2, sockPx / 2))) {
                            land()
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            fly(home, spring(dampingRatio = 0.45f, stiffness = 280f))
                        }
                    }
                }
                .semantics {
                    contentDescription = description
                    onClick(label = action) {
                        if (!isLanding) land()
                        true
                    }
                },
        ) {
            drawSock(kind, size.width)
        }
    }

    @Composable
    private fun DrawerView(kind: Kind, inside: Int, modifier: Modifier) {
        val description = stringResource(R.string.game_drawer, stringResource(kind.name), inside, PER_DRAWER)
        val front = MaterialTheme.colorScheme.surfaceContainerHighest
        val hollow = MaterialTheme.colorScheme.surfaceContainerLowest
        val handle = MaterialTheme.colorScheme.outline
        Canvas(modifier.semantics { contentDescription = description }) {
            val gap = 3.dp.toPx()
            val corner = CornerRadius(12.dp.toPx())
            val icon = minOf(size.width * 0.74f, size.height * 0.62f)
            drawRoundRect(hollow, Offset(gap, 0f), Size(size.width - gap * 2, size.height * 0.34f), corner)
            for (sock in 0 until inside) {
                translate(gap * 3 + sock * size.width * 0.3f, -size.height * 0.03f) { drawSock(kind, icon * 0.6f) }
            }
            drawRoundRect(front, Offset(gap, size.height * 0.24f), Size(size.width - gap * 2, size.height * 0.76f), corner)
            translate((size.width - icon) / 2, size.height * 0.29f) { drawSock(kind, icon) }
            drawRoundRect(
                handle,
                Offset(size.width * 0.3f, size.height - 16.dp.toPx()),
                Size(size.width * 0.4f, 8.dp.toPx()),
                CornerRadius(4.dp.toPx()),
            )
        }
    }

    /** Draws a sock in a square of [box] at the current origin. */
    private fun DrawScope.drawSock(kind: Kind, box: Float) {
        val path = Path().apply {
            moveTo(box * 0.24f, box * 0.03f)
            lineTo(box * 0.62f, box * 0.03f)
            lineTo(box * 0.62f, box * 0.5f)
            cubicTo(box * 0.62f, box * 0.57f, box * 0.7f, box * 0.6f, box * 0.82f, box * 0.66f)
            cubicTo(box * 1f, box * 0.74f, box * 0.98f, box * 0.97f, box * 0.78f, box * 0.97f)
            lineTo(box * 0.42f, box * 0.97f)
            cubicTo(box * 0.26f, box * 0.97f, box * 0.18f, box * 0.82f, box * 0.24f, box * 0.66f)
            close()
        }
        clipPath(path) {
            drawRect(kind.base, size = Size(box, box))
            when (kind.pattern) {
                Pattern.STRIPES -> for (stripe in 0..5) {
                    drawRect(kind.accent, Offset(0f, box * (0.2f + stripe * 0.15f)), Size(box, box * 0.07f))
                }
                Pattern.DOTS -> for (row in 0..5) {
                    for (column in 0..4) {
                        drawCircle(
                            kind.accent,
                            box * 0.04f,
                            Offset(box * (0.2f + column * 0.17f + (row % 2) * 0.085f), box * (0.22f + row * 0.15f)),
                        )
                    }
                }
                Pattern.TOE -> {
                    drawRect(kind.accent, Offset(box * 0.72f, box * 0.5f), Size(box * 0.3f, box * 0.5f))
                    drawCircle(kind.accent, box * 0.16f, Offset(box * 0.3f, box * 0.9f))
                }
            }
            drawRect(kind.accent, Offset(0f, 0f), Size(box, box * 0.12f))
        }
    }
}
