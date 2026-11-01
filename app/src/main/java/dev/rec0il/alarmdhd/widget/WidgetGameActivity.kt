package dev.rec0il.alarmdhd.widget

import android.app.Activity
import android.os.Bundle
import dev.rec0il.alarmdhd.alarm.AlarmEngine
import dev.rec0il.alarmdhd.alarm.RingService
import dev.rec0il.alarmdhd.alarm.RingState
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.model.Planner

/**
 * What the widget's button opens: an invisible screen that takes the home screen's path into the
 * game. It has to be a screen, because Android won't let a widget's broadcast open the game or start
 * the service from the background.
 */
class WidgetGameActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val running = Planner.progress(AlarmStore.state.value, AlarmEngine.now(), AlarmEngine.zone()) != null
        if (running || RingState.active.value != null) {
            RingService.beginGame(this)
        } else {
            // The run ended and the widget hadn't heard, so there's nothing to win. Opening the app refreshes it.
            startActivity(AlarmWidget.appIntent(this))
        }
        finish()
    }
}
