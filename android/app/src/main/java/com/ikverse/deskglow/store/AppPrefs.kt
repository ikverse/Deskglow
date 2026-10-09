package com.ikverse.deskglow.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ikverse.deskglow.model.Orientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The most screens the display can have. */
const val MAX_PAGES = 5

enum class BrightnessMode { System, Dim, Custom, Auto }

data class Brightness(val mode: BrightnessMode = BrightnessMode.Dim, val level: Int = 30)

/** The place the weather is shown for: found from the phone's position, or chosen by name. */
data class City(val name: String, val region: String, val latitude: Double, val longitude: Double) {
    val label: String get() = if (region.isBlank()) name else "$name, $region"

    fun toJson(): String = JSONObject()
        .put("name", name).put("region", region)
        .put("latitude", latitude).put("longitude", longitude)
        .toString()

    companion object {
        fun fromJson(text: String): City? = runCatching {
            val json = JSONObject(text)
            City(json.getString("name"), json.optString("region"), json.getDouble("latitude"), json.getDouble("longitude"))
        }.getOrNull()
    }
}

/** App-wide settings (everything that is not part of a widget), with a live view of each. */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("deskglow", Context.MODE_PRIVATE)

    private val brightnessState = MutableStateFlow(
        Brightness(
            runCatching { BrightnessMode.valueOf(prefs.getString(KEY_BRIGHTNESS_MODE, null) ?: "") }.getOrDefault(BrightnessMode.Dim),
            prefs.getInt(KEY_BRIGHTNESS_LEVEL, 30),
        ),
    )
    val brightness: StateFlow<Brightness> = brightnessState.asStateFlow()

    private val burnInState = MutableStateFlow(prefs.getBoolean(KEY_BURN_IN, true))
    val burnIn: StateFlow<Boolean> = burnInState.asStateFlow()

    private val cityState = MutableStateFlow(prefs.getString(KEY_CITY, null)?.let(City::fromJson))
    val city: StateFlow<City?> = cityState.asStateFlow()

    private val autoLocationState = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LOCATION, true))
    val autoLocation: StateFlow<Boolean> = autoLocationState.asStateFlow()

    /** The place last found from the phone's position, kept so the weather shows at once on the next start. */
    private val detectedCityState = MutableStateFlow(prefs.getString(KEY_DETECTED_CITY, null)?.let(City::fromJson))
    val detectedCity: StateFlow<City?> = detectedCityState.asStateFlow()

    /**
     * How many screens the display has in each orientation, from 1 to [MAX_PAGES]. A count saved by the
     * version that shared one number between both is where each of them starts.
     */
    private val pageCountStates = Orientation.entries.associateWith { orientation ->
        MutableStateFlow(prefs.getInt(countKey(orientation), prefs.getInt(KEY_PAGE_COUNT, 1)).coerceIn(1, MAX_PAGES))
    }

    fun pageCount(orientation: Orientation): StateFlow<Int> = pageCountStates.getValue(orientation).asStateFlow()

    fun setPageCount(orientation: Orientation, count: Int) {
        val clamped = count.coerceIn(1, MAX_PAGES)
        prefs.edit { putInt(countKey(orientation), clamped) }
        pageCountStates.getValue(orientation).value = clamped
    }

    /** The screen the display was last left on in an orientation (0 is the first), for it to open there again. */
    fun lastPage(orientation: Orientation): Int = prefs.getInt(lastPageKey(orientation), 0).coerceIn(0, pageCountStates.getValue(orientation).value - 1)

    fun setLastPage(orientation: Orientation, page: Int) {
        prefs.edit { putInt(lastPageKey(orientation), page.coerceAtLeast(0)) }
    }

    private fun countKey(orientation: Orientation) = "${KEY_PAGE_COUNT}_${orientation.name.lowercase()}"
    private fun lastPageKey(orientation: Orientation) = "last_page_${orientation.name.lowercase()}"

    fun setAutoLocation(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_LOCATION, on) }
        autoLocationState.value = on
    }

    fun setDetectedCity(city: City?) {
        prefs.edit { if (city == null) remove(KEY_DETECTED_CITY) else putString(KEY_DETECTED_CITY, city.toJson()) }
        detectedCityState.value = city
    }

    fun setBrightness(value: Brightness) {
        prefs.edit { putString(KEY_BRIGHTNESS_MODE, value.mode.name).putInt(KEY_BRIGHTNESS_LEVEL, value.level) }
        brightnessState.value = value
    }

    fun setBurnIn(on: Boolean) {
        prefs.edit { putBoolean(KEY_BURN_IN, on) }
        burnInState.value = on
    }

    fun setCity(city: City?) {
        prefs.edit {
            if (city == null) remove(KEY_CITY) else putString(KEY_CITY, city.toJson())
            remove(KEY_WEATHER_CACHE)
        }
        cityState.value = city
    }

    var weatherCache: String?
        get() = prefs.getString(KEY_WEATHER_CACHE, null)
        set(value) = prefs.edit { putString(KEY_WEATHER_CACHE, value) }

    /** The last prayer times fetched, for today and tomorrow, and where they were for. */
    var prayerCache: String?
        get() = prefs.getString(KEY_PRAYER_CACHE, null)
        set(value) = prefs.edit { putString(KEY_PRAYER_CACHE, value) }

    /** The last F1 calendar, results and standings fetched. */
    var f1Cache: String?
        get() = prefs.getString(KEY_F1_CACHE, null)
        set(value) = prefs.edit { putString(KEY_F1_CACHE, value) }

    /** The outline of the last circuit fetched for the race-weekend widget. */
    var f1Track: String?
        get() = prefs.getString(KEY_F1_TRACK, null)
        set(value) = prefs.edit { putString(KEY_F1_TRACK, value) }

    /** The classification of the last F1 session to finish, for the live-session widget. */
    var f1LiveResult: String?
        get() = prefs.getString(KEY_F1_LIVE, null)
        set(value) = prefs.edit { putString(KEY_F1_LIVE, value) }

    var pickedFonts: String?
        get() = prefs.getString(KEY_PICKED_FONTS, null)
        set(value) = prefs.edit { putString(KEY_PICKED_FONTS, value) }

    private companion object {
        const val KEY_BRIGHTNESS_MODE = "brightness_mode"
        const val KEY_BRIGHTNESS_LEVEL = "brightness_level"
        const val KEY_BURN_IN = "burn_in"
        const val KEY_CITY = "city"
        const val KEY_AUTO_LOCATION = "auto_location"
        const val KEY_DETECTED_CITY = "detected_city"
        const val KEY_WEATHER_CACHE = "weather_cache"
        const val KEY_PRAYER_CACHE = "prayer_cache"
        const val KEY_F1_CACHE = "f1_cache"
        const val KEY_F1_TRACK = "f1_track"
        const val KEY_F1_LIVE = "f1_live_result"
        const val KEY_PICKED_FONTS = "picked_fonts"
        const val KEY_PAGE_COUNT = "page_count"
    }
}
