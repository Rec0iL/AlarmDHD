package dev.rec0il.alarmdhd

import android.app.Application
import dev.rec0il.alarmdhd.alarm.Notifications
import dev.rec0il.alarmdhd.data.AlarmStore
import dev.rec0il.alarmdhd.sound.MusicLibrary

class AlarmDhdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmStore.init(this)
        MusicLibrary.init(this)
        Notifications.createChannels(this)
    }
}
