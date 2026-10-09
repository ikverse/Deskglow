package com.ikverse.deskglow.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.F1Roster
import com.ikverse.deskglow.data.F1State
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.StandingRow
import com.ikverse.deskglow.data.standingRows
import com.ikverse.deskglow.data.teamColour
import com.ikverse.deskglow.model.ColourKey
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey
import java.util.Locale
import kotlin.math.min

object F1StandingsWidget : WidgetType {
    val TABLE = TextKey("table", "drivers")
    val ROWS = IntKey("rows", 5)
    val VALUE = TextKey("value", "points")
    val SHOW_MOVES = FlagKey("showMoves", true)
    val FAV_DRIVER = TextKey("favDriver", "")
    val FAV_TEAM = TextKey("favTeam", "")
    val ACCENT = ColourKey("accent", 0xFFE10600.toInt())

    override val id = "f1standings"
    override val label = "F1 standings"
    override val blurb = "The championship table, with your favourite always on it"
    override val width = 176
    override val height = 208
    override val defaults: Settings = Common.base()

    private fun teams(settings: Settings) = settings[TABLE] == "constructors"

    override fun title(settings: Settings) = "F1 standings · " + if (teams(settings)) "Constructors" else "Drivers"

    override fun fields(settings: Settings) = listOf(
        ChoiceField("Championship", TABLE, listOf("drivers" to "Drivers", "constructors" to "Constructors")),
        SliderField("Rows", ROWS, 3..10),
        ChoiceField("Numbers", VALUE, listOf("points" to "Points", "gap" to "Gap to the leader")),
        ToggleField("Show ▲▼ since the last round", SHOW_MOVES),
        if (teams(settings)) {
            ChoiceField("Favourite team", FAV_TEAM, listOf("" to "None") + F1Roster.teams)
        } else {
            ChoiceField("Favourite driver", FAV_DRIVER, listOf("" to "None") + F1Roster.drivers.map { (code, name) -> code to "$code · $name" })
        },
        ColourField("Highlight colour", ACCENT),
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "F1 data from the Jolpica F1 API (api.jolpi.ca). Not affiliated with Formula 1."

    /** "341", "341.5", or for the gap "−24" ("—" for the leader). */
    fun valueText(points: Double, leader: Double, gap: Boolean): String {
        fun fmt(v: Double) = if (v % 1.0 == 0.0) String.format(Locale.US, "%d", v.toLong()) else String.format(Locale.US, "%.1f", v)
        if (!gap) return fmt(points)
        val behind = leader - points
        return if (behind <= 0.0) "—" else "−" + fmt(behind)
    }

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.f1.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val w = constraints.maxWidth.toFloat()
            val data = when (val s = state) {
                F1State.Loading -> return@BoxWithConstraints EditorHint("Loading F1 standings…", min(h * 0.1f, w * 0.08f))
                is F1State.Failed -> s.last ?: return@BoxWithConstraints EditorHint("No F1 data yet", min(h * 0.1f, w * 0.08f))
                is F1State.Ready -> s.data
            }
            val teams = teams(settings)
            val entries = if (teams) data.constructors else data.drivers
            if (entries.isEmpty()) return@BoxWithConstraints EditorHint("Standings start after the first race", min(h * 0.1f, w * 0.08f))
            val favourite = settings[if (teams) FAV_TEAM else FAV_DRIVER]
            val (top, extra) = standingRows(entries, if (teams) data.previousConstructors else data.previousDrivers, settings[ROWS], favourite)

            val colour = Color(settings[Common.COLOUR])
            val accent = Color(settings[ACCENT])
            val slots = top.size + 1.2f + (if (extra != null) 1.3f else 0f)
            val row = min(h / slots, w * 0.16f)
            val text = row * 0.56f
            val leader = entries.first().points
            val gap = settings[VALUE] == "gap"
            val moves = settings[SHOW_MOVES]

            @Composable
            fun Line(r: StandingRow) {
                val fav = favourite.isNotEmpty() && r.entry.id == favourite
                Row(Modifier.fillMaxWidth().height(pxToDp(row)), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${r.entry.position}", color = Muted, fontSize = pxToSp(text * 0.9f), textAlign = TextAlign.End,
                        maxLines = 1, softWrap = false, modifier = Modifier.width(pxToDp(text * 1.3f)),
                    )
                    Spacer(Modifier.width(pxToDp(text * 0.45f)))
                    Box(Modifier.width(pxToDp(text * 0.2f)).height(pxToDp(row * 0.62f)).background(Color(teamColour(r.entry.teamId)), RoundedCornerShape(pxToDp(text * 0.1f))))
                    Spacer(Modifier.width(pxToDp(text * 0.45f)))
                    Text(
                        if (teams) r.entry.name else r.entry.id,
                        color = if (fav) accent else colour, fontSize = pxToSp(text), fontWeight = if (fav) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(valueText(r.entry.points, leader, gap), color = if (fav) accent else Muted, fontSize = pxToSp(text * 0.92f), maxLines = 1, softWrap = false)
                    if (moves) {
                        val move = r.move ?: 0
                        Text(
                            when { move > 0 -> "▲"; move < 0 -> "▼"; else -> "" },
                            color = if (move > 0) Color(0xFF3DDC84) else Color(0xFFFF5A5F), fontSize = pxToSp(text * 0.55f),
                            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(pxToDp(text * 1.0f)),
                        )
                    }
                }
            }

            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    (if (teams) "CONSTRUCTORS" else "DRIVERS") + if (data.round > 0) " · ROUND ${data.round}" else "",
                    color = Muted, fontSize = pxToSp(text * 0.72f), fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, maxLines = 1, softWrap = false,
                    modifier = Modifier.height(pxToDp(row * 1.2f)).padding(top = pxToDp(row * 0.3f)),
                )
                top.forEach { Line(it) }
                if (extra != null) {
                    Box(Modifier.fillMaxWidth().height(pxToDp(row * 0.3f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(pxToDp((row * 0.02f).coerceAtLeast(1f))).background(Color(0xFF2A2A2A)))
                    }
                    Line(extra)
                }
            }
        }
    }
}
