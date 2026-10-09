package com.ikverse.deskglow

import com.ikverse.deskglow.data.AlarmState
import com.ikverse.deskglow.data.BatteryState
import com.ikverse.deskglow.data.ChargeStatus
import com.ikverse.deskglow.data.EventState
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.Feeds
import com.ikverse.deskglow.data.Http
import com.ikverse.deskglow.data.MediaState
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.data.PrayerState
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
    override val alarm = MutableStateFlow(AlarmState(null))
    override val f1 = MutableStateFlow<F1State>(F1State.Loading)
    /** One prayer feed for every method, set directly by tests. */
    val prayers = MutableStateFlow<PrayerState>(PrayerState.NoLocation)
    override fun prayer(method: Int, school: Int) = prayers
}

/**
 * Jolpica answers cut down to three race weekends (Singapore done, Japan next, a sprint weekend in
 * Austin), the Singapore result, and short standings after rounds 17 and 16.
 */
object F1Samples {
    private fun race(round: Int, name: String, locality: String, country: String, date: String, time: String, sessions: String) =
        """{"season":"2026","round":"$round","raceName":"$name","Circuit":{"circuitId":"c$round","circuitName":"Circuit","Location":{"locality":"$locality","country":"$country"}},"date":"$date","time":"$time"$sessions}"""

    val calendar = """{"MRData":{"RaceTable":{"season":"2026","Races":[""" +
        race(17, "Singapore Grand Prix", "Marina Bay", "Singapore", "2026-10-04", "12:00:00Z",
            ""","FirstPractice":{"date":"2026-10-02","time":"09:30:00Z"},"SecondPractice":{"date":"2026-10-02","time":"13:00:00Z"},"ThirdPractice":{"date":"2026-10-03","time":"09:30:00Z"},"Qualifying":{"date":"2026-10-03","time":"13:00:00Z"}""") + "," +
        race(18, "Japanese Grand Prix", "Suzuka", "Japan", "2026-10-11", "05:00:00Z",
            ""","FirstPractice":{"date":"2026-10-09","time":"02:30:00Z"},"SecondPractice":{"date":"2026-10-09","time":"06:00:00Z"},"ThirdPractice":{"date":"2026-10-10","time":"02:30:00Z"},"Qualifying":{"date":"2026-10-10","time":"06:00:00Z"}""") + "," +
        race(19, "United States Grand Prix", "Austin", "USA", "2026-10-25", "19:00:00Z",
            ""","FirstPractice":{"date":"2026-10-23","time":"17:30:00Z"},"SprintQualifying":{"date":"2026-10-23","time":"21:30:00Z"},"Sprint":{"date":"2026-10-24","time":"18:00:00Z"},"Qualifying":{"date":"2026-10-24","time":"22:00:00Z"}""") +
        "]}}}"

    private fun driver(code: String, given: String, family: String) = """"Driver":{"driverId":"${family.lowercase()}","code":"$code","givenName":"$given","familyName":"$family"}"""
    private fun team(id: String, name: String) = """{"constructorId":"$id","name":"$name"}"""

    val results = """{"MRData":{"RaceTable":{"season":"2026","round":"17","Races":[{"round":"17","raceName":"Singapore Grand Prix","Results":[""" +
        """{"position":"1","points":"25",${driver("NOR", "Lando", "Norris")},"Constructor":${team("mclaren", "McLaren")}},""" +
        """{"position":"2","points":"18",${driver("VER", "Max", "Verstappen")},"Constructor":${team("red_bull", "Red Bull")}},""" +
        """{"position":"3","points":"15",${driver("LEC", "Charles", "Leclerc")},"Constructor":${team("ferrari", "Ferrari")}},""" +
        """{"position":"4","points":"12",${driver("PIA", "Oscar", "Piastri")},"Constructor":${team("mclaren", "McLaren")}}""" +
        "]}]}}}"

    private fun driverStandings(round: Int, rows: List<Triple<String, String, Double>>) =
        """{"MRData":{"StandingsTable":{"season":"2026","round":"$round","StandingsLists":[{"season":"2026","round":"$round","DriverStandings":[""" +
            rows.mapIndexed { i, (code, teamId, points) ->
                """{"position":"${i + 1}","points":"$points",${driver(code, code, code.lowercase())},"Constructors":[${team(teamId, teamId)}]}"""
            }.joinToString(",") + "]}]}}}"

    val drivers = driverStandings(17, listOf(
        Triple("NOR", "mclaren", 331.0), Triple("PIA", "mclaren", 324.0), Triple("VER", "red_bull", 290.0),
        Triple("RUS", "mercedes", 230.0), Triple("LEC", "ferrari", 200.5), Triple("HAM", "ferrari", 152.0), Triple("ANT", "mercedes", 120.0),
    ))
    val previousDrivers = driverStandings(16, listOf(
        Triple("PIA", "mclaren", 312.0), Triple("NOR", "mclaren", 306.0), Triple("VER", "red_bull", 272.0),
        Triple("RUS", "mercedes", 222.0), Triple("LEC", "ferrari", 185.5), Triple("HAM", "ferrari", 146.0), Triple("ANT", "mercedes", 114.0),
    ))

    val constructors = """{"MRData":{"StandingsTable":{"season":"2026","round":"17","StandingsLists":[{"round":"17","ConstructorStandings":[""" +
        """{"position":"1","points":"655","Constructor":${team("mclaren", "McLaren")}},""" +
        """{"position":"2","points":"350","Constructor":${team("mercedes", "Mercedes")}},""" +
        """{"position":"3","points":"340.5","Constructor":${team("ferrari", "Ferrari")}},""" +
        """{"position":"4","points":"300","Constructor":${team("red_bull", "Red Bull")}}""" +
        "]}]}}}"

    internal val raw = com.ikverse.deskglow.data.F1Raw(calendar, results, drivers, constructors, previousDrivers, null, 0, 0)
    val data = com.ikverse.deskglow.data.parseF1(raw)
}

/**
 * Google Fonts as the app sees it, without the network: the real cut of the family list, a CSS
 * answer for any font request, and a real TrueType file (one of the bundled fonts) for the font itself.
 */
class FakeFontsHttp(private val online: Boolean = true) : Http {
    val requests = mutableListOf<String>()

    /** When set, the font catalogue still loads but every font file fails to download, as on a connection that drops. */
    var failDownloads = false
    private val catalog = javaClass.classLoader!!.getResource("google-fonts-catalog.json")!!.readText()
    private val font = File("src/main/res/font/outfit.ttf").readBytes()

    override fun get(url: String): ByteArray {
        requests += url
        if (!online) throw IOException("offline")
        return when {
            url.startsWith("https://fonts.google.com/metadata/fonts") -> catalog.toByteArray()
            url.startsWith("https://fonts.googleapis.com/css2") ->
                "@font-face { src: url(https://fonts.gstatic.com/fake/${url.hashCode()}.ttf) format('truetype'); }".toByteArray()
            url.startsWith("https://fonts.gstatic.com/") -> if (failDownloads) throw IOException("download dropped") else font
            else -> throw IOException("unexpected $url")
        }
    }
}
