package dev.rec0il.alarmdhd.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Anything that can invalidate the scheduled alarm: reboot, clock change, app update, permission change. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmEngine.reschedule(context)
    }
}
