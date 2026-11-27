package com.ikverse.deskglow.data

import androidx.test.core.app.ApplicationProvider
import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The look back on a finished season: reading Jolpica's answers, and asking for them only between seasons. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class F1SeasonsTest {
    private fun driver(code: String, family: String) = """"Driver":{"driverId":"${family.lowercase()}","code":"$code","givenName":"A","familyName":"$family"}"""
    private fun team(id: String) = """"Constructor":{"constructorId":"$id","name":"$id"}"""

    /** One place of two races, as "results/{place}" answers. */
    private fun results(place: Int, first: Pair<String, String>, second: Pair<String, String>, grid: Int = place) =
        """{"MRData":{"total":"2","RaceTable":{"Races":[""" +
            """{"round":"1","raceName":"Australian Grand Prix","Results":[{"position":"$place","grid":"$grid","points":"25",${driver(first.first, first.first)},${team(first.second)}}]},""" +
            """{"round":"2","raceName":"Chinese Grand Prix","Results":[{"position":"$place","grid":"$grid","points":"25",${driver(second.first, second.first)},${team(second.second)}}]}""" +
            "]}}}"

    /** "qualifying/1" as Jolpica answers it: round 1's pole, and for round 2 only a stray row of another place. */
    private val qualifying = """{"MRData":{"total":"3","RaceTable":{"Races":[""" +
        """{"round":"1","raceName":"Australian Grand Prix","QualifyingResults":[{"position":"1",${driver("PIA", "Piastri")},${team("mclaren")}}]},""" +
        """{"round":"2","raceName":"Chinese Grand Prix","QualifyingResults":[{"position":"4",${driver("HAM", "Hamilton")},${team("ferrari")}}]}""" +
        "]}}}"

    private val noRaces = """{"MRData":{"total":"0","RaceTable":{"season":"2027","Races":[]}}}"""

    @Test
    fun `a season's races come from the top three of each, the pole from qualifying or the grid`() {
        val rows = (1..3).flatMap { place ->
            parseResultRows(results(place, listOf("NOR" to "mclaren", "VER" to "red_bull", "LEC" to "ferrari")[place - 1], listOf("VER" to "red_bull", "NOR" to "mclaren", "RUS" to "mercedes")[place - 1]))
        }
        val rounds = seasonRounds(rows, parsePoles(qualifying))
        assertEquals(listOf(1, 2), rounds.map { it.round })
        assertEquals("Australian GP", rounds[0].name)
        assertEquals("NOR", rounds[0].winner?.id)
        assertEquals(listOf("NOR", "VER", "LEC"), rounds[0].podium.map { it.id })
        assertEquals("mclaren", rounds[0].winner?.teamId)
        // Qualifying says who was quickest in round 1; its stray row for round 2 is passed over, and the winner started first there.
        assertEquals("PIA", rounds[0].pole?.id)
        assertEquals("VER", rounds[1].pole?.id)
    }

    @Test
    fun `what is kept in the file reads back the same`() {
        val entry = F1Entry("NOR", "Lando Norris", "mclaren", 412.5, 1)
        val kept = Kept(
            F1Seasons(
                2026, listOf(SeasonRound(1, "Australian GP", entry, null, listOf(entry))), listOf(entry), listOf(F1Entry("mclaren", "McLaren", "mclaren", 800.0, 1)),
                parseCalendar(F1Samples.calendar), listOf(Champion(2025, entry, null)),
            ),
            seasonAt = 5, nextAt = 7, nextBody = F1Samples.calendar,
        )
        assertEquals(kept, Kept.fromJson(kept.toJson()))
    }

    @Test
    fun `the season looked back on follows the calendar`() {
        val races = F1Samples.data.races
        assertNull(seasonToReview(races, Instant.parse("2026-10-06T00:00:00Z")))
        // Austin, the last, ends at 21:00 on the 25th: twelve hours on, the season is over.
        assertNull(seasonToReview(races, Instant.parse("2026-10-26T08:00:00Z")))
        assertEquals(2026, seasonToReview(races, Instant.parse("2026-10-26T10:00:00Z")))
        // Before a season's first weekend it is the one before, until a week ahead of it.
        assertEquals(2025, seasonToReview(races, Instant.parse("2026-09-01T00:00:00Z")))
        assertNull(seasonToReview(races, Instant.parse("2026-09-28T00:00:00Z")))
        assertEquals(2026, seasonToReview(emptyList(), Instant.parse("2027-01-05T00:00:00Z")))
    }

    private class Jolpica(private val answer: (String) -> String) : Http {
        val asked = CopyOnWriteArrayList<String>()
        override fun get(url: String): ByteArray {
            asked += url.removePrefix(F1Repository.BASE + "/")
            return answer(url).toByteArray()
        }
    }

    private fun answers() = Jolpica { url ->
        when {
            "/results/1.json" in url -> results(1, "NOR" to "mclaren", "VER" to "red_bull")
            "/results/2.json" in url -> results(2, "VER" to "red_bull", "NOR" to "mclaren")
            "/results/3.json" in url -> results(3, "LEC" to "ferrari", "RUS" to "mercedes")
            "/qualifying/1.json" in url -> qualifying
            "/2027.json" in url -> noRaces
            "driverstandings" in url -> F1Samples.drivers
            "constructorstandings" in url -> F1Samples.constructors
            else -> throw IOException("unexpected $url")
        }
    }

    private val calendar = MutableStateFlow<F1State>(F1State.Ready(F1Samples.data))
    private fun prefs() = AppPrefs(ApplicationProvider.getApplicationContext())

    @Test
    fun `nothing is asked for while a season is under way`() = runTest {
        val http = answers()
        val repository = F1SeasonsRepository(prefs(), http, { Instant.parse("2026-10-06T00:00:00Z").toEpochMilli() }, StandardTestDispatcher(testScheduler))
        val job = launch { repository.updates(calendar).collect {} }
        advanceTimeBy(3 * 60 * 60 * 1000L)
        job.cancel()
        assertTrue(http.asked.toString(), http.asked.isEmpty())
    }

    @Test
    fun `between seasons the season, the next calendar and ten years of champions are fetched once, and kept`() = runTest {
        val http = answers()
        val prefs = prefs()
        val now = { Instant.parse("2026-10-27T00:00:00Z").toEpochMilli() }
        val seasons = F1SeasonsRepository(prefs, http, now, StandardTestDispatcher(testScheduler)).updates(calendar).first { it?.honour?.size == 10 }!!
        assertEquals(2026, seasons.season)
        assertEquals(listOf("Australian GP", "Chinese GP"), seasons.rounds.map { it.name })
        assertEquals("NOR", seasons.drivers.first().id)
        assertEquals("mclaren", seasons.constructors.first().id)
        assertEquals((2025 downTo 2016).toList(), seasons.honour.map { it.year })
        assertEquals("NOR", seasons.honour.first().driver?.id)
        // Three places, the poles, two tables, the next calendar, and a driver and a team for each of ten years.
        assertEquals(http.asked.toString(), 27, http.asked.size)
        assertTrue(http.asked.contains("2026/results/1.json?limit=100") && http.asked.contains("2027.json?limit=100") && http.asked.contains("2016/constructorstandings/1.json"))

        // Started again, it shows what it kept and asks only for the season, which is still short of its rounds, half an hour on.
        val again = answers()
        val kept = F1SeasonsRepository(prefs, again, now, StandardTestDispatcher(testScheduler)).updates(calendar).first { it != null }!!
        assertEquals(seasons, kept)
        assertTrue(again.asked.toString(), again.asked.isEmpty())
    }
}
