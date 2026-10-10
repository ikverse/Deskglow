package com.ikverse.deskglow.data

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.ikverse.deskglow.store.City
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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
    /** Level, status, voltage and temperature, updated only when Android reports a change. */
    val battery: StateFlow<BatteryState>
    /** The same, plus the current (and so the power) re-read every few seconds. Only widgets that show it read this. */
    val batteryPower: StateFlow<BatteryState>
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
    /** Android's code for what the phone is plugged into: 0 nothing, 1 wall, 2 USB, 4 wireless, 8 dock. */
    val plugSource: Int = 0,
    /** Android's battery health code: 2 good, 3 overheated, 4 dead, 5 over voltage, 6 failure, 7 cold. */
    val healthCode: Int = 0,
    /** Charge cycles, from phones that report them (Android 14 and later); null otherwise. */
    val cycles: Int? = null,
) {
    val temperatureC: Double get() = temperatureTenths / 10.0
    val volts: Double get() = voltageMv / 1000.0
    val watts: Double? get() = currentMa?.let { voltageMv * it / 1_000_000.0 }
}

/** One app with unread notifications, and its small status-bar icon. */
data class NotifiedApp(val packageName: String, val icon: ImageBitmap?, /** How many notifications the app has up. */ val count: Int = 1)

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
    /** The hours ahead, from the start of today in the city; empty in an older cached answer. */
    val hours: List<HourForecast> = emptyList(),
    /** Today's sunrise and sunset in the city's own time. */
    val sunrise: LocalDateTime? = null,
    val sunset: LocalDateTime? = null,
    val uvIndex: Double? = null,
    /** The city's offset from UTC, for turning the phone's clock into the city's. */
    val utcOffsetSeconds: Int = 0,
) {
    /** The city's clock when the phone's reads [phoneNow]. */
    fun cityTime(phoneNow: LocalDateTime): LocalDateTime =
        LocalDateTime.ofInstant(phoneNow.atZone(ZoneId.systemDefault()).toInstant(), ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
}

/** One hour of the forecast, in the city's own time. */
data class HourForecast(val time: LocalDateTime, val temperatureC: Double, val code: Int, val isDay: Boolean)

sealed interface WeatherState {
    data object NoCity : WeatherState
    data class Loading(val city: City) : WeatherState
    data class Ready(val city: City, val weather: Weather) : WeatherState
    /** The last fetch failed; [last] is what was shown before, if anything. */
    data class Failed(val city: City, val last: Weather?) : WeatherState
}
