package com.ikverse.deskglow.widgets

import com.ikverse.deskglow.data.Champion
import com.ikverse.deskglow.data.F1Data
import com.ikverse.deskglow.data.F1Entry
import com.ikverse.deskglow.data.F1Race
import com.ikverse.deskglow.data.F1Result
import com.ikverse.deskglow.data.F1Seasons
import com.ikverse.deskglow.data.F1Session
import com.ikverse.deskglow.data.SeasonRound
import java.time.Duration
import java.time.Instant

/** What PitWall draws: which screen, the data behind it, and (for a made-up one) the moment it is set at. */
internal class PitWallScene(val mode: PitWallMode, val data: F1Data?, val seasons: F1Seasons?, val sampleAt: Instant? = null)

/**
 * Made-up scenes for the editor, one for each of PitWall's screens, so each can be seen and set up
 * whatever the calendar says today. They are the same on every phone and never leave the editor.
 */
internal object PitWallSample {
    private fun driver(position: Int, code: String, name: String, team: String, points: Double) = F1Entry(code, name, team, points, position)
    private fun team(position: Int, id: String, name: String, points: Double) = F1Entry(id, name, id, points, position)

    private val drivers = listOf(
        driver(1, "NOR", "Lando Norris", "mclaren", 389.0), driver(2, "VER", "Max Verstappen", "red_bull", 377.0),
        driver(3, "PIA", "Oscar Piastri", "mclaren", 358.0), driver(4, "RUS", "George Russell", "mercedes", 301.0),
        driver(5, "HAM", "Lewis Hamilton", "ferrari", 231.0), driver(6, "LEC", "Charles Leclerc", "ferrari", 224.0),
        driver(7, "ANT", "Kimi Antonelli", "mercedes", 170.0), driver(8, "SAI", "Carlos Sainz", "williams", 121.0),
        driver(9, "ALB", "Alexander Albon", "williams", 98.0), driver(10, "ALO", "Fernando Alonso", "aston_martin", 74.0),
        driver(11, "HAD", "Isack Hadjar", "red_bull", 66.0), driver(12, "HUL", "Nico Hülkenberg", "audi", 52.0),
    )

    private val constructors = listOf(
        team(1, "mclaren", "McLaren", 747.0), team(2, "mercedes", "Mercedes", 471.0), team(3, "ferrari", "Ferrari", 455.0),
        team(4, "red_bull", "Red Bull", 443.0), team(5, "williams", "Williams", 219.0), team(6, "aston_martin", "Aston Martin", 102.0),
        team(7, "haas", "Haas", 81.0), team(8, "audi", "Audi", 66.0), team(9, "rb", "Racing Bulls", 58.0), team(10, "alpine", "Alpine", 40.0),
        team(11, "cadillac", "Cadillac", 12.0),
    )

    /** A weekend whose first practice is at [first]: three practices, qualifying a day on, the race the day after that. */
    private fun meeting(round: Int, name: String, place: String, first: Instant) = F1Race(
        round, name, place,
        listOf(
            F1Session("FP1", first), F1Session("FP2", first.plus(Duration.ofHours(4))), F1Session("FP3", first.plus(Duration.ofHours(23))),
            F1Session("Qualifying", first.plus(Duration.ofHours(27))), F1Session("Race", first.plus(Duration.ofHours(51))),
        ),
    )

    private fun data(races: List<F1Race>, last: F1Result?) = F1Data(
        races, last, drivers, constructors,
        previousDrivers = mapOf("NOR" to 1, "VER" to 3, "PIA" to 2, "RUS" to 4, "HAM" to 6, "LEC" to 5),
        previousConstructors = emptyMap(), round = 23, track = F1LiveSample.track,
    )

    private val podium = F1Result(23, "Qatar GP", listOf(drivers[2], drivers[1], drivers[7]))

    /** A sprint under the safety car: the live-session widget's own sample. */
    val live = PitWallScene(PitWallMode.Live(F1LiveSample.race), null, null, F1LiveSample.AT)

    /** Qualifying is over and the race is tomorrow. */
    val weekend: PitWallScene = run {
        val at = F1LiveSample.AT.plus(Duration.ofHours(2))
        val race = meeting(18, "Singapore GP", "Marina Bay, Singapore", at.minus(Duration.ofHours(29)))
        val result = F1LiveSample.qualifying.copy(status = "Finalised", finished = true, part = 3, cutoff = null, remaining = null)
        PitWallScene(PitWallMode.Weekend(race, result, race.race), data(listOf(race), null), null, at)
    }

    /** Six days before the last race of the year. */
    val between: PitWallScene = run {
        val at = Instant.parse("2026-11-28T07:18:00Z")
        val qatar = meeting(23, "Qatar GP", "Lusail, Qatar", at.minus(Duration.ofDays(8)))
        val abuDhabi = meeting(24, "Abu Dhabi GP", "Yas Marina, UAE", at.plus(Duration.ofDays(6)).plus(Duration.ofHours(4)).plus(Duration.ofMinutes(12)))
        PitWallScene(PitWallMode.Between(abuDhabi, podium), data(listOf(qatar, abuDhabi), podium), null, at)
    }

    /** The winter after a season, eleven weeks before the next. */
    val offSeason: PitWallScene = run {
        val at = Instant.parse("2026-12-20T09:00:00Z")
        fun round(n: Int, name: String, first: Int, second: Int, third: Int, pole: Int) =
            SeasonRound(n, name, drivers[first], drivers[pole], listOf(drivers[first].copy(position = 1), drivers[second].copy(position = 2), drivers[third].copy(position = 3)))
        val next = listOf(
            meeting(1, "Australian GP", "Melbourne, Australia", Instant.parse("2027-03-05T01:30:00Z")),
            meeting(2, "Chinese GP", "Shanghai, China", Instant.parse("2027-03-12T03:30:00Z")),
            meeting(3, "Japanese GP", "Suzuka, Japan", Instant.parse("2027-03-26T02:30:00Z")),
            meeting(4, "Bahrain GP", "Sakhir, Bahrain", Instant.parse("2027-04-09T11:30:00Z")),
            meeting(5, "Saudi Arabian GP", "Jeddah, Saudi Arabia", Instant.parse("2027-04-16T13:30:00Z")),
            meeting(6, "Miami GP", "Miami, USA", Instant.parse("2027-04-30T16:30:00Z")),
        )
        fun past(year: Int, code: String, name: String, driverTeam: String, teamId: String, teamName: String) =
            Champion(year, driver(1, code, name, driverTeam, 0.0), team(1, teamId, teamName, 0.0))
        val seasons = F1Seasons(
            season = 2026,
            rounds = listOf(
                round(1, "Australian GP", 0, 3, 1, 0), round(2, "Chinese GP", 2, 0, 3, 2), round(3, "Japanese GP", 1, 0, 2, 1),
                round(12, "British GP", 4, 0, 3, 0), round(17, "Singapore GP", 3, 0, 5, 3), round(24, "Abu Dhabi GP", 0, 1, 2, 1),
            ),
            drivers = drivers.mapIndexed { i, d -> d.copy(points = d.points + 23 - i * 2) },
            constructors = constructors,
            next = next,
            honour = listOf(
                past(2025, "NOR", "Lando Norris", "mclaren", "mclaren", "McLaren"), past(2024, "VER", "Max Verstappen", "red_bull", "mclaren", "McLaren"),
                past(2023, "VER", "Max Verstappen", "red_bull", "red_bull", "Red Bull"), past(2022, "VER", "Max Verstappen", "red_bull", "red_bull", "Red Bull"),
                past(2021, "VER", "Max Verstappen", "red_bull", "mercedes", "Mercedes"), past(2020, "HAM", "Lewis Hamilton", "mercedes", "mercedes", "Mercedes"),
                past(2019, "HAM", "Lewis Hamilton", "mercedes", "mercedes", "Mercedes"), past(2018, "HAM", "Lewis Hamilton", "mercedes", "mercedes", "Mercedes"),
            ),
        )
        PitWallScene(PitWallMode.OffSeason(2026, next.first()), data(emptyList(), null), seasons, at)
    }

    fun of(preview: String): PitWallScene? = when (preview) {
        "live" -> live
        "weekend" -> weekend
        "between" -> between
        "offseason" -> offSeason
        else -> null
    }
}
