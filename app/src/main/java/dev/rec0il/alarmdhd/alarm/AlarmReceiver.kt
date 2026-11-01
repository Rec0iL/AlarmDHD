package dev.rec0il.alarmdhd.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> AlarmEngine.onAlarmFired(context, intent.getLongExtra(EXTRA_AT, 0L))
            ACTION_TEST -> RingService.startTest(context)
            ACTION_SNOOZE -> RingService.current?.snooze()
            ACTION_DISMISS -> RingService.current?.dismiss()
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.rec0il.alarmdhd.FIRE"
        const val ACTION_TEST = "dev.rec0il.alarmdhd.TEST"
        const val ACTION_SNOOZE = "dev.rec0il.alarmdhd.SNOOZE"
        const val ACTION_DISMISS = "dev.rec0il.alarmdhd.DISMISS"
        const val EXTRA_AT = "at"
    }
}
