package dev.rec0il.alarmdhd.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.rec0il.alarmdhd.model.AppState
import dev.rec0il.alarmdhd.model.LiveState
import dev.rec0il.alarmdhd.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * The app's only state. It lives in device-protected storage so alarms can still be read and
 * rung after an overnight reboot, before the phone has been unlocked.
 */
object AlarmStore {
    private const val KEY_SETTINGS = "settings"
    private const val KEY_LIVE = "live"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var prefs: SharedPreferences
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState

    fun init(context: Context) {
        prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("alarmdhd", Context.MODE_PRIVATE)
        mutableState.value = AppState(
            settings = read(KEY_SETTINGS) ?: Settings(),
            live = read(KEY_LIVE) ?: LiveState(),
        )
    }

    private inline fun <reified T> read(key: String): T? {
        val text = prefs.getString(key, null) ?: return null
        return try {
            json.decodeFromString<T>(text)
        } catch (e: Exception) {
            Log.e("AlarmStore", "Couldn't read $key", e)
            null
        }
    }

    @Synchronized
    fun update(transform: (AppState) -> AppState): AppState {
        val old = mutableState.value
        val new = transform(old)
        if (new == old) return old
        val edit = prefs.edit()
        if (new.settings != old.settings) edit.putString(KEY_SETTINGS, json.encodeToString(new.settings))
        if (new.live != old.live) edit.putString(KEY_LIVE, json.encodeToString(new.live))
        // Written at once, not in the background: a snooze lost to a crash would be a missed alarm.
        edit.commit()
        mutableState.value = new
        return new
    }
}
