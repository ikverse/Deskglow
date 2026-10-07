package com.ikverse.deskglow

import com.ikverse.deskglow.data.BatteryState
import com.ikverse.deskglow.data.ChargeStatus
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.Feeds
import com.ikverse.deskglow.data.Http
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.WeatherState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/** Fixed data, as the Note 9 reported it on 2026-10-07, for screens under test. */
class FakeFeeds : Feeds {
    override val minute = MutableStateFlow(LocalDateTime.of(2026, 10, 7, 20, 5))
    override val second = MutableStateFlow(LocalDateTime.of(2026, 10, 7, 20, 5, 9))
    override val battery = MutableStateFlow(BatteryState(100, ChargeStatus.Full, true, 4224, 335, 296))
    override val notifications = MutableStateFlow<NotificationState>(NotificationState.Apps(emptyList()))
    override val media = MutableStateFlow<MediaState>(MediaState.Idle)
    override val nextEvent = MutableStateFlow<EventState>(EventState.None)
    override val weather = MutableStateFlow<WeatherState>(WeatherState.NoCity)
}

/**
 * Google Fonts as the app sees it, without the network: the real cut of the family list, a CSS
 * answer for any font request, and a real TrueType file (one of the bundled fonts) for the font itself.
 */
class FakeFontsHttp(private val online: Boolean = true) : Http {
    val requests = mutableListOf<String>()
    private val catalog = javaClass.classLoader!!.getResource("google-fonts-catalog.json")!!.readText()
    private val font = File("src/main/res/font/outfit.ttf").readBytes()

    override fun get(url: String): ByteArray {
        requests += url
        if (!online) throw IOException("offline")
        return when {
            url.startsWith("https://fonts.google.com/metadata/fonts") -> catalog.toByteArray()
            url.startsWith("https://fonts.googleapis.com/css2") ->
                "@font-face { src: url(https://fonts.gstatic.com/fake/${url.hashCode()}.ttf) format('truetype'); }".toByteArray()
            url.startsWith("https://fonts.gstatic.com/") -> font
            else -> throw IOException("unexpected $url")
        }
    }
}
