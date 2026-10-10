package com.ikverse.deskglow.data

import com.ikverse.deskglow.F1Samples
import com.ikverse.deskglow.widgets.sessionProgress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The live-timing parts that make a widget say what is happening: delays and restart times from race
 * control, tyres, penalties and gains, the qualifying cut-off, what is thrown away on arrival, and
 * how the feed's state reaches the calendar widgets.
 */
class F1LiveTest {
    private fun at(text: String) = Instant.parse(text)

    private fun topicsOf(resource: String): Map<String, JSONObject> {
        val all = JSONObject(javaClass.classLoader!!.getResource(resource)!!.readText())
        return all.keys().asSequence().associateWith { all.getJSONObject(it) }
    }

    private fun snapshot(topics: Map<String, JSONObject>) = LiveTiming().apply {
        topics.forEach { (topic, data) -> apply(LiveMessage(topic, JSONObject(data.toString()), full = true)) }
    }

    private fun json(text: String) = JSONObject(text)

    private fun raceControl(vararg lines: String): JSONObject {
        val messages = org.json.JSONArray()
        lines.forEachIndexed { i, text ->
            messages.put(JSONObject().put("Utc", "2026-10-10T09:%02d:00".format(i)).put("Category", "Other").put("Message", text))
        }
        return JSONObject().put("Messages", messages)
    }

    private fun raceControlAt(offset: String, text: String) = readRaceControl(
        JSONObject().put("Messages", org.json.JSONArray().put(JSONObject().put("Utc", "2026-10-10T09:17:36").put("Message", text))), offsetOf(offset),
    )

    // ---- a sprint held back by rain, from the feed as it stood before the start ----

    @Test
    fun `a delayed sprint says so, with its new time and the rain, and its grid`() {
        val s = snapshot(topicsOf("f1live-sprint-delayed.json")).session()!!
        assertEquals("Sprint", s.name)
        assertEquals(at("2026-10-10T09:00:00Z"), s.start) // 17:00 at the track, eight hours ahead of UTC
        assertTrue(s.delayed)
        // "FORMATION LAP WILL START AT 17:30", in the track's own time.
        assertEquals(at("2026-10-10T09:30:00Z"), s.restart)
        assertEquals("Formation lap", s.restartLabel)
        assertEquals(80, s.rainRisk)
        assertTrue(s.raining)
        assertEquals(44.5, s.trackTemp!!, 0.0)
        assertEquals(LivePhase.Delayed, s.phase(at("2026-10-10T09:10:00Z")))
        assertEquals(22, s.rows.size)
        assertEquals(listOf("VER", "RUS", "LEC", "PIA"), s.rows.take(4).map { it.code })
        assertEquals(4, s.rows[3].grid)
        assertEquals("Red Bull Racing", s.rows[0].team)
        assertEquals(21, s.totalLaps)
    }

    @Test
    fun `before the start nothing is late, until the start has gone by or race control says so`() {
        val delayed = snapshot(topicsOf("f1live-sprint-delayed.json")).session()!!
        val quiet = delayed.copy(delayed = false)
        assertEquals(LivePhase.PreStart, quiet.phase(at("2026-10-10T08:55:00Z")))
        // A couple of minutes' grace, then it is late.
        assertEquals(LivePhase.PreStart, quiet.phase(at("2026-10-10T09:01:00Z")))
        assertEquals(LivePhase.Delayed, quiet.phase(at("2026-10-10T09:03:00Z")))
        assertEquals(LivePhase.Delayed, delayed.phase(at("2026-10-10T08:55:00Z")))
    }

    @Test
    fun `race control's news is in plain words, once, without the sector flags`() {
        val s = snapshot(topicsOf("f1live-sprint-delayed.json")).session()!!
        assertEquals(
            listOf("[BEA] under investigation", "Rain risk 80%", "Conditions changing", "Start delayed", "Low grip", "Formation lap"),
            s.events.map { it.text },
        )
        // "Formation lap(s) behind safety car" is not a safety car.
        assertFalse(s.events.any { it.text.contains("afety") })
        assertEquals(EventKind.Incident, s.events.first().kind)
    }

    @Test
    fun `news stays on show for ninety seconds`() {
        val s = snapshot(topicsOf("f1live-sprint-delayed.json")).session()!!
        val last = s.events.last().at
        assertEquals("Formation lap", s.freshEvent(last.plusSeconds(30))!!.text)
        assertNull(s.freshEvent(last.plusSeconds(120)))
    }

    // ---- race control ----

    @Test
    fun `penalties add up and are served, and a stopped car and a red flag are news`() {
        val rc = readRaceControl(
            raceControl(
                "FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 1 (NOR) - CAUSING A COLLISION",
                "FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 1 (NOR) - SPEEDING IN THE PIT LANE",
                "FIA STEWARDS: 10 SECOND STOP/GO PENALTY FOR CAR 87 (BEA) - UNSAFE RELEASE",
                "CAR 87 (BEA) 10 SECOND STOP/GO PENALTY SERVED",
                "CAR 43 (COL) STOPPED ON TRACK",
                "FIA STEWARDS: INCIDENT INVOLVING CAR 14 (ALO) NO FURTHER INVESTIGATION",
                "CAR 5 (BOR) TIME 1:32.100 DELETED - TRACK LIMITS AT TURN 10",
                "RED FLAG",
                "VIRTUAL SAFETY CAR DEPLOYED",
                "VIRTUAL SAFETY CAR ENDING",
                "CHEQUERED FLAG",
            ),
            offsetOf("08:00:00"),
        )
        assertEquals(mapOf("1" to 10), rc.penalties)
        assertEquals(
            // Of nine (the same penalty twice reads as one), the last six are kept.
            listOf("[ALO] no further action", "[BOR] lap time deleted", "Red flag", "Virtual safety car", "VSC ending", "Chequered flag"),
            rc.events.map { it.text },
        )
    }

    @Test
    fun `a restart time is read in the track's time, and a late one means the next day`() {
        val rc = raceControlAt("08:00:00", "RACE WILL RESUME AT 18:10")
        assertEquals(at("2026-10-10T10:10:00Z"), rc.restart)
        assertEquals("Resumes", rc.restartLabel)
        // 00:10 at the track after a message sent at 23:50 there is the day after.
        val late = readRaceControl(
            JSONObject().put("Messages", org.json.JSONArray().put(JSONObject().put("Utc", "2026-10-10T15:50:00").put("Message", "SESSION WILL RESUME AT 00:10"))),
            offsetOf("08:00:00"),
        )
        assertEquals(at("2026-10-10T16:10:00Z"), late.restart)
        assertEquals("Starts", raceControlAt("08:00:00", "SPRINT WILL START AT 17:45").restartLabel)
    }

    @Test
    fun `messages that arrived as changes before any snapshot are read in order`() {
        val rc = readRaceControl(
            json("""{"Messages":{"1":{"Utc":"2026-10-10T09:01:00","Message":"DELAYED START"},"0":{"Utc":"2026-10-10T09:00:00","Message":"RISK OF RAIN FOR THE RACE IS 40%"}}}"""),
            offsetOf("08:00:00"),
        )
        assertEquals(listOf("Rain risk 40%", "Start delayed"), rc.events.map { it.text })
        assertTrue(rc.delayed)
    }

    // ---- a race ----

    private fun raceTopics(lap: Int = 18) = mapOf(
        "SessionInfo" to json("""{"Key":1,"Type":"Race","Name":"Race","StartDate":"2026-10-11T20:00:00","GmtOffset":"08:00:00","Meeting":{"Name":"Singapore Grand Prix"}}"""),
        "SessionStatus" to json("""{"Status":"Started"}"""),
        "TrackStatus" to json("""{"Status":"1"}"""),
        "LapCount" to json("""{"CurrentLap":$lap,"TotalLaps":21}"""),
        "DriverList" to json(
            """{"1":{"Tla":"NOR","TeamColour":"F47600","TeamName":"McLaren"},"3":{"Tla":"VER","TeamColour":"4781D7","TeamName":"Red Bull Racing"},
            "63":{"Tla":"RUS","TeamColour":"00D7B6","TeamName":"Mercedes"},"43":{"Tla":"COL","TeamColour":"FF87BC","TeamName":"Alpine"}}""",
        ),
        "TimingData" to json(
            """{"Lines":{
            "3":{"Position":"1","GapToLeader":"","IntervalToPositionAhead":{"Value":"","Catching":false},"BestLapTime":{"Value":"1:32.100"},"LastLapTime":{"Value":"1:33.000"},"NumberOfPitStops":1},
            "63":{"Position":"2","GapToLeader":"+1.4","IntervalToPositionAhead":{"Value":"+1.4","Catching":true},"BestLapTime":{"Value":"1:31.900"},"LastLapTime":{"Value":"1:32.700"}},
            "43":{"Position":"3","GapToLeader":"+9.0","IntervalToPositionAhead":{"Value":"+7.6"},"Retired":true},
            "1":{"Position":"4","GapToLeader":"+12.5","IntervalToPositionAhead":{"Value":"+3.5","Catching":false},"BestLapTime":{"Value":"1:32.400"},"LastLapTime":{"Value":"1:32.900"}}}}""",
        ),
        "TimingAppData" to json(
            """{"Lines":{"1":{"GridPos":"5","Stints":[{"Compound":"MEDIUM","TotalLaps":12},{"Compound":"HARD","TotalLaps":6}]},
            "3":{"GridPos":"1","Stints":{"0":{"Compound":"SOFT","TotalLaps":18}}},"63":{"GridPos":"2","Stints":[{"Compound":"INTERMEDIATE","TotalLaps":18}]}}}""",
        ),
        "RaceControlMessages" to raceControl("FIA STEWARDS: 5 SECOND TIME PENALTY FOR CAR 1 (NOR) - CAUSING A COLLISION"),
    )

    @Test
    fun `a race is read with tyres, stops, places gained, penalties, the fastest lap and a retirement last`() {
        val s = snapshot(raceTopics()).session()!!
        assertEquals(listOf("VER", "RUS", "NOR", "COL"), s.rows.map { it.code })
        val nor = s.rows[2]
        assertEquals('H', nor.tyre)
        assertEquals(6, nor.tyreLaps)
        assertEquals(1, nor.stops)
        assertEquals(5, nor.grid)
        assertEquals(1, nor.gained) // started fifth, now fourth
        assertEquals(5, nor.penaltySeconds)
        // Stints that arrived as an object keyed by index read the same, and a count of stops from timing counts too.
        val ver = s.rows[0]
        assertEquals('S', ver.tyre)
        assertEquals(18, ver.tyreLaps)
        assertEquals(1, ver.stops)
        assertEquals(93_000L, ver.lastLapMs)
        // The fastest lap is the quickest best, and only one car has it.
        assertEquals(listOf("RUS"), s.rows.filter { it.fastest }.map { it.code })
        assertTrue(s.rows[1].catching)
        assertFalse(s.rows[0].catching)
        val col = s.rows[3]
        assertTrue(col.retired)
        assertTrue(col.out)
        assertEquals("Leader", s.rows[0].gap)
    }

    @Test
    fun `tyre changes arrive as changes into a list, and the last stint counts`() {
        val timing = snapshot(raceTopics())
        assertTrue(timing.apply(LiveMessage("TimingAppData", json("""{"Lines":{"1":{"Stints":{"1":{"TotalLaps":7}}}}}"""), full = false)))
        assertEquals(7, timing.session()!!.rows[2].tyreLaps)
        // A new set: a third stint, and so a second stop.
        timing.apply(LiveMessage("TimingAppData", json("""{"Lines":{"1":{"Stints":{"2":{"Compound":"SOFT","TotalLaps":0}}}}}"""), full = false))
        val nor = timing.session()!!.rows[2]
        assertEquals('S', nor.tyre)
        assertEquals(2, nor.stops)
    }

    @Test
    fun `laps to go are counted down at the end of a race`() {
        val now = Instant.EPOCH
        fun progress(lap: Int) = sessionProgress(snapshot(raceTopics(lap)).session()!!, now)
        assertEquals("Lap 10/21", progress(10))
        assertEquals("3 to go", progress(18))
        assertEquals("1 to go", progress(20))
        assertEquals("Final lap", progress(21))
        assertEquals(3, snapshot(raceTopics(18)).session()!!.lapsToGo)
    }

    @Test
    fun `phases follow the flag, the red flag and the end`() {
        val running = snapshot(raceTopics()).session()!!
        val now = Instant.parse("2026-10-11T12:30:00Z")
        assertEquals(LivePhase.Running, running.phase(now))
        assertEquals(LivePhase.SafetyCar, running.copy(flag = TrackFlag.SafetyCar).phase(now))
        assertEquals(LivePhase.VirtualSafetyCar, running.copy(flag = TrackFlag.VscEnding).phase(now))
        assertEquals(LivePhase.Red, running.copy(status = "Aborted", flag = TrackFlag.Red).phase(now))
        assertEquals(LivePhase.Finished, running.copy(status = "Finished", finished = true).phase(now))
        assertEquals(LivePhase.Final, running.copy(status = "Finalised", finished = true).phase(now))
    }

    // ---- qualifying ----

    private fun qualifying(part: Int = 2, cars: Int = 12): Map<String, JSONObject> {
        val lines = StringBuilder()
        for (i in 1..cars) {
            if (i > 1) lines.append(',')
            val lap = lapTime(89.0 + 0.1 * i)
            lines.append("\"$i\":{\"Position\":\"$i\",\"BestLapTimes\":[{\"Value\":\"\"},{\"Value\":\"$lap\"},{\"Value\":\"\"}]}")
        }
        return mapOf(
            "SessionInfo" to json("""{"Key":2,"Type":"Qualifying","Name":"Qualifying","StartDate":"2026-10-10T16:00:00","GmtOffset":"08:00:00"}"""),
            "SessionStatus" to json("""{"Status":"Started"}"""),
            "TimingData" to json("""{"SessionPart":$part,"NoEntries":[22,16,10],"Lines":{$lines}}"""),
        )
    }

    @Test
    fun `in qualifying the cut-off, the margin to it and the drop zone are worked out from the part's times`() {
        val s = snapshot(qualifying()).session()!!
        assertEquals(10, s.cutoff)
        fun margin(position: Int) = s.cutoffMargin(s.rows.first { it.position == position })!!
        // Ninth is two tenths quicker than the first car out (eleventh); tenth, one tenth.
        assertEquals(0.2, margin(9), 0.0005)
        assertEquals(0.1, margin(10), 0.0005)
        // Eleventh is a tenth slower than the last car in; twelfth, two tenths.
        assertEquals(-0.1, margin(11), 0.0005)
        assertEquals(-0.2, margin(12), 0.0005)
        assertEquals(listOf(11, 12), s.dropZone().map { it.position })
        assertEquals("1:29.900", s.rows[8].bestLap)
        // In the last part nobody goes out at a cut-off, and a car without a time has no margin.
        assertNull(snapshot(qualifying(part = 3)).session()!!.cutoff)
        assertNull(s.cutoffMargin(s.rows[0].copy(bestLap = "")))
    }

    @Test
    fun `without the timing a first part is not the end of qualifying`() {
        val lite = qualifying().filterKeys { it != "TimingData" }
        assertFalse(snapshot(lite.plus("SessionStatus" to json("""{"Status":"Finished"}"""))).session()!!.finished)
        assertTrue(snapshot(lite.plus("SessionStatus" to json("""{"Status":"Finalised"}"""))).session()!!.finished)
        // A race or a practice has no parts: finished is finished.
        val race = raceTopics().filterKeys { it != "TimingData" }.plus("SessionStatus" to json("""{"Status":"Finished"}"""))
        assertTrue(snapshot(race).session()!!.finished)
        assertTrue(snapshot(race.plus("SessionStatus" to json("""{"Status":"Finished"}"""))).finished())
    }

    @Test
    fun `finished ends qualifying only when the last part is known to be the one that ended`() {
        val ended = json("""{"Status":"Finished"}""")
        // The part is not known, or is the first of three: the next part is still to come.
        val noPart = qualifying().plus("TimingData" to json("""{"NoEntries":[22,16,10],"Lines":{}}"""))
        assertFalse(snapshot(noPart.plus("SessionStatus" to ended)).session()!!.finished)
        assertFalse(snapshot(noPart.plus("SessionStatus" to ended)).finished())
        assertFalse(snapshot(qualifying(part = 1).plus("SessionStatus" to ended)).finished())
        assertTrue(snapshot(qualifying(part = 3).plus("SessionStatus" to ended)).finished())
    }

    @Test
    fun `the last part starting before the status leaves finished does not end qualifying`() {
        // Q2 has ended; the feed moves on to Q3 a moment before it says Q3 has started.
        val timing = snapshot(qualifying(part = 2).plus("SessionStatus" to json("""{"Status":"Finished"}""")))
        assertFalse(timing.finished())
        timing.apply(LiveMessage("TimingData", json("""{"SessionPart":3}"""), full = false))
        assertFalse(timing.finished())
        assertFalse(timing.session()!!.finished)
        timing.apply(LiveMessage("SessionStatus", json("""{"Status":"Started"}"""), full = false))
        assertFalse(timing.finished())
        // Q3 ends: that is the end.
        timing.apply(LiveMessage("SessionStatus", json("""{"Status":"Finished"}"""), full = false))
        assertTrue(timing.finished())
        assertTrue(timing.session()!!.finished)
    }

    @Test
    fun `between the parts of qualifying it is a break, not a delay`() {
        val long = at("2026-10-10T09:00:00Z") // an hour after the start
        val between = snapshot(qualifying(part = 1).plus("SessionStatus" to json("""{"Status":"Finished"}"""))).session()!!
        assertEquals(LivePhase.Break, between.phase(long))
        val next = snapshot(qualifying(part = 2).plus("SessionStatus" to json("""{"Status":"Inactive"}"""))).session()!!
        assertEquals(LivePhase.Break, next.phase(long))
        // Before anything has run it is still a delay.
        val before = snapshot(qualifying(part = 1).plus("SessionStatus" to json("""{"Status":"Inactive"}""")).plus("TimingData" to json("""{"SessionPart":1,"NoEntries":[22,16,10],"Lines":{}}"""))).session()!!
        assertEquals(LivePhase.Delayed, before.phase(long))
        assertEquals(LivePhase.Running, snapshot(qualifying(part = 2)).session()!!.phase(long))
    }

    @Test
    fun `before qualifying starts, the session before it is not shown as it`() {
        val held = qualifying(part = 1).plus("SessionStatus" to json("""{"Status":"Inactive"}"""))
            .plus("TimingData" to json("""{"Lines":{"1":{"Position":"1","BestLapTime":{"Value":"1:32.429"}}}}"""))
            .plus(
                "RaceControlMessages" to JSONObject().put(
                    "Messages",
                    org.json.JSONArray()
                        .put(JSONObject().put("Utc", "2026-10-10T05:00:00").put("Message", "CAR 5 (BOR) TIME 1:32.100 DELETED - TRACK LIMITS"))
                        .put(JSONObject().put("Utc", "2026-10-10T07:40:00").put("Message", "RISK OF RAIN FOR THE SESSION IS 20%")),
                ),
            )
        val s = snapshot(held).session()!!
        assertTrue(s.rows.isEmpty())
        // 16:00 at the track is 08:00 UTC: the earlier line is the last session's, the later one this session's.
        assertEquals(listOf("Rain risk 20%"), s.events.map { it.text })
    }

    @Test
    fun `the leader's time is from the part now on, like the gaps`() {
        // Part two begun with no lap in it yet: nobody's time is a part one time.
        val fresh = qualifying(part = 2).let { topics ->
            val t = JSONObject(topics.getValue("TimingData").toString())
            val lines = t.getJSONObject("Lines")
            for (k in lines.keys()) lines.getJSONObject(k).put("BestLapTimes", org.json.JSONArray().put(JSONObject().put("Value", "1:30.000")).put(JSONObject().put("Value", "")).put(JSONObject().put("Value", "")))
            topics.plus("TimingData" to t)
        }
        val s = snapshot(fresh).session()!!
        assertEquals("", s.rows.first().gap)
    }

    // ---- what is thrown away on arrival ----

    @Test
    fun `mini-sectors and speed traps are dropped as they arrive, and a message with nothing else is ignored`() {
        val timing = LiveTiming()
        val full = json(
            """{"Withheld":false,"Lines":{"1":{"Position":"1","RacingNumber":"1","Line":1,"Sectors":[{"Segments":[{"Status":2064}]}],"Speeds":{"I1":{"Value":"300"}},"LastLapTime":{"Value":"1:33.000","Status":2049,"OverallFastest":true}}}}""",
        )
        assertTrue(timing.apply(LiveMessage("TimingData", full, full = true)))
        val line = full.getJSONObject("Lines").getJSONObject("1")
        assertFalse(line.has("Sectors"))
        assertFalse(line.has("Speeds"))
        assertFalse(line.has("RacingNumber"))
        assertFalse(line.getJSONObject("LastLapTime").has("Status"))
        assertEquals("1:33.000", line.getJSONObject("LastLapTime").getString("Value"))
        // A change with only mini-sectors in it is nothing to redraw.
        assertFalse(timing.apply(LiveMessage("TimingData", json("""{"Lines":{"1":{"Sectors":{"0":{"Segments":{"0":{"Status":2048}}}}}}}"""), full = false)))
        assertFalse(timing.apply(LiveMessage("Heartbeat", json("""{"Utc":"2026-10-10T09:06:07Z"}"""), full = false)))
        assertFalse(timing.apply(LiveMessage("DriverList", json("""{"1":{"Line":3}}"""), full = false)))
        assertTrue(timing.apply(LiveMessage("TimingData", json("""{"Lines":{"1":{"Position":"2","Sectors":{"0":{"Value":"28.1"}}}}}"""), full = false)))
    }

    @Test
    fun `a driver list keeps a code, a colour and a team, not the headshot`() {
        val timing = LiveTiming()
        val drivers = json("""{"1":{"RacingNumber":"1","Tla":"NOR","TeamColour":"F47600","TeamName":"McLaren","HeadshotUrl":"https://x/y.png","FullName":"Lando NORRIS"}}""")
        timing.apply(LiveMessage("DriverList", drivers, full = true))
        assertEquals(setOf("Tla", "TeamColour", "TeamName"), drivers.getJSONObject("1").keys().asSequence().toSet())
    }

    // ---- small helpers ----

    @Test
    fun `lap times and gaps are read and worded`() {
        assertEquals(92_274L, lapMillis("1:32.274"))
        assertEquals(59_987L, lapMillis("59.987"))
        assertNull(lapMillis(""))
        assertNull(lapMillis("+0.512"))
        assertEquals("+1 lap", gapText("1L"))
        assertEquals("+2 laps", gapText("2L"))
        assertEquals("+3 laps", gapText("+3 LAPS"))
        assertEquals("+0.512", gapText("+0.512"))
        assertEquals("Leader", gapText("Leader"))
    }

    @Test
    fun `a saved result keeps what the result shows`() {
        val s = snapshot(raceTopics()).session()!!.copy(status = "Finalised", finished = true)
        val back = LiveSession.fromJson(s.toJson())
        assertEquals(s.rows.map { it.copy(catching = false) }, back.rows)
        assertEquals('H', back.rows[2].tyre)
        assertEquals(5, back.rows[2].penaltySeconds)
        assertTrue(back.rows.first { it.fastest }.code == "RUS")
    }

    // ---- the calendar widgets and the feed ----

    private val f1 = F1Samples.data
    private fun sprint(status: String, flag: TrackFlag = TrackFlag.Clear) = LiveSession(
        7, "United States GP", "Sprint", "Race", at("2026-10-24T18:00:00Z"), status, false, emptyList(), flag, lap = 5, totalLaps = 19,
    )

    @Test
    fun `a sprint still running after its slot is still the live session, and a delayed one is live and delayed`() {
        val running = sprint("Started")
        val slotEnd = at("2026-10-24T19:20:00Z") // the calendar gave it an hour from 18:00
        val fed = feedStatus(f1.races, F1LiveState.Live(running), slotEnd)!!
        assertEquals("Sprint", fed.session.kind)
        assertEquals(LivePhase.Running, fed.phase)
        val view = weekendViewWithFeed(weekendView(f1, slotEnd), f1.races, fed) as WeekendView.Upcoming
        assertEquals("Sprint", view.live!!.kind)
        assertEquals("Qualifying", view.next!!.kind)
        assertEquals(fed, view.feed)

        val late = at("2026-10-24T18:20:00Z")
        val delayed = feedStatus(f1.races, F1LiveState.Live(sprint("Inactive").copy(delayed = true)), late)!!
        assertEquals(LivePhase.Delayed, delayed.phase)
        assertEquals("Sprint", (weekendViewWithFeed(weekendView(f1, late), f1.races, delayed) as WeekendView.Upcoming).live!!.kind)
    }

    @Test
    fun `before the start, or with nothing live, the calendar's view is left alone`() {
        val early = at("2026-10-24T17:58:00Z")
        val view = weekendView(f1, early)
        assertEquals(view, weekendViewWithFeed(view, f1.races, feedStatus(f1.races, F1LiveState.Live(sprint("Inactive")), early)))
        assertNull(feedStatus(f1.races, F1LiveState.Waiting, early))
        assertNull(feedStatus(f1.races, F1LiveState.Result(sprint("Finalised").copy(finished = true)), early))
        // A session the calendar does not list is none of its business.
        assertNull(feedStatus(f1.races, F1LiveState.Live(sprint("Started").copy(start = at("2026-11-30T18:00:00Z"))), early))
    }

    // ---- the transport ----

    @Test
    fun `an event stream is cut into events however its chunks fall`() {
        val events = SseEvents()
        fun add(text: String) = events.add(text.toCharArray(), text.length)
        add("data: {\"type\":6}\r\n\r\ndata: {\"x\"")
        assertEquals(listOf("{\"type\":6}"), events.take())
        add(":1}\n")
        assertTrue(events.take().isEmpty())
        add("\n: a comment\n\ndata: a\ndata: b\n\n")
        assertEquals(listOf("{\"x\":1}", "a\nb"), events.take())
        assertTrue(events.take().isEmpty())
    }

    @Test
    fun `a stream's handshake and keep-alive say nothing, and a feed call is a change`() {
        val rs = "\u001e"
        assertTrue(parseSignalR("{}$rs").isEmpty())
        assertTrue(parseSignalR("""{"type":6}$rs""").isEmpty())
        val change = parseSignalR("""{"type":1,"target":"feed","arguments":["TrackStatus",{"Status":"2"},"2026-10-10T09:00:00Z"]}$rs""").single()
        assertEquals("TrackStatus", change.topic)
        assertFalse(change.full)
    }

    @Test
    fun `the few topics are all the calendar widgets need`() {
        assertTrue(LITE_TOPICS.size < LIVE_TOPICS.size)
        assertTrue(LITE_TOPICS.none { it == "TimingData" || it == "DriverList" })
        assertTrue(LIVE_TOPICS.containsAll(LITE_TOPICS))
        assertNotNull(LIVE_TOPICS.firstOrNull { it == "TimingAppData" })
        assertEquals(Duration.ofSeconds(90), EVENT_FRESH)
    }
}
