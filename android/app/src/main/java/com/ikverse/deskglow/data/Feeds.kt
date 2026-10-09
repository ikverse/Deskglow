package com.ikverse.deskglow.data

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.ikverse.deskglow.store.City
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/**
 * Everything the widgets show that comes from outside the app. Each feed runs only while something
 * on screen is reading it and stops a moment after the last reader goes, so with the screen saver
 * off nothing here does any work at all.
 */
interface Feeds {
    /** The time, updated on each new minute (and at once when the clock or time zone is changed). */
    val minute: StateFlow<LocalDateTime>
    /** The time, updated every second. Only a clock showing seconds reads this. */
    val second: StateFlow<LocalDateTime>
    val battery: StateFlow<BatteryState>
    val notifications: StateFlow<NotificationState>
    val media: StateFlow<MediaState>
    val nextEvent: StateFlow<EventState>
    val weather: StateFlow<WeatherState>
    val alarm: StateFlow<AlarmState>
    val f1: StateFlow<F1State>
    /** The F1 session on now, live, or the last one's result; connects to live timing only while a session runs. */
    val f1Live: StateFlow<F1LiveState>
    /** Prayer times by Aladhan [method] and Asr [school]; widgets with the same choices share one feed. */
    fun prayer(method: Int, school: Int): StateFlow<PrayerState>
}

val LocalFeeds = staticCompositionLocalOf<Feeds> { error("No feeds provided") }

enum class ChargeStatus { Charging, Full, Discharging, NotCharging, Unknown }

data class BatteryState(
    val level: Int = 0,
    val status: ChargeStatus = ChargeStatus.Unknown,
    val plugged: Boolean = false,
    val voltageMv: Int = 0,
    val temperatureTenths: Int = 0,
    /** Current in mA, always positive; null where the phone does not report it. */
    val currentMa: Int? = null,
    /** Time until full in ms while charging; null when Android does not know. */
    val timeToFullMs: Long? = null,
) {
    val temperatureC: Double get() = temperatureTenths / 10.0
    val volts: Double get() = voltageMv / 1000.0
    val watts: Double? get() = currentMa?.let { voltageMv * it / 1_000_000.0 }
}

/** One app with unread notifications, and its small status-bar icon. */
data class NotifiedApp(val packageName: String, val icon: ImageBitmap?)

sealed interface NotificationState {
    data object NoAccess : NotificationState
    data class Apps(val apps: List<NotifiedApp>) : NotificationState
}

sealed interface MediaState {
    data object NoAccess : MediaState
    data object Idle : MediaState
    data class Track(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val positionMs: Long,
        /** When [positionMs] was measured, on the elapsed-realtime clock, so progress can be worked out later. */
        val positionAtElapsedMs: Long,
        val playing: Boolean,
        val speed: Float,
    ) : MediaState
}

sealed interface EventState {
    data object NoAccess : EventState
    data object None : EventState
    data class Next(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean) : EventState
}

/** What the sky is doing, in the few kinds the weather icon draws. */
enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Storm }

data class Weather(
    val temperatureC: Double,
    val code: Int,
    val isDay: Boolean,
    val highC: Double,
    val lowC: Double,
    val fetchedAtMs: Long,
    /** Null when the answer did not include them (an older cached answer, or a gap in the data). */
    val feelsLikeC: Double? = null,
    val humidityPercent: Int? = null,
    val windKmh: Double? = null,
    val rainChancePercent: Int? = null,
)

sealed interface WeatherState {
    data object NoCity : WeatherState
    data class Loading(val city: City) : WeatherState
    data class Ready(val city: City, val weather: Weather) : WeatherState
    /** The last fetch failed; [last] is what was shown before, if anything. */
    data class Failed(val city: City, val last: Weather?) : WeatherState
}
