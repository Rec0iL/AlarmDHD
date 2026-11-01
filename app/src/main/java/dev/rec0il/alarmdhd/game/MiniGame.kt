package dev.rec0il.alarmdhd.game

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import dev.rec0il.alarmdhd.ui.MiniGameActivity
import kotlin.random.Random

/**
 * A short game that stops the day's alarms when won. Rules for every game: no way to lose, no
 * timer, no penalty for wrong moves, about 30 seconds or less, always winnable, works with TalkBack.
 *
 * To add a game: implement this in its own file and add it to [MiniGames.all]. Nothing else changes.
 */
interface MiniGame {
    val id: String

    @get:StringRes
    val title: Int

    @Composable
    fun Play(onWin: () -> Unit)
}

object MiniGames {
    val all: List<MiniGame> = listOf(
        BubblePop,
        FoggyWindow,
        PourCoffee,
        WakeSheep,
        WaterPlant,
        ConnectStars,
        MemoryPairs,
        PumpBalloon,
        SockDrawer,
        PaintSky,
    )

    /** Never the same game twice in a row, while there's more than one to choose from. */
    fun pick(lastId: String?, random: Random = Random.Default): MiniGame =
        all.filterNot { it.id == lastId }.ifEmpty { all }.random(random)
}

object MiniGameIntents {
    fun open(context: Context): Intent = Intent(context, MiniGameActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
