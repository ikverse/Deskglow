package com.ikverse.deskglow.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikverse.deskglow.display.WidgetTextStyle
import com.ikverse.deskglow.ui.Chip
import com.ikverse.deskglow.ui.Glyph
import com.ikverse.deskglow.ui.GlyphIcon
import com.ikverse.deskglow.ui.IconAction
import com.ikverse.deskglow.ui.Palette
import com.ikverse.deskglow.ui.Type
import com.ikverse.deskglow.ui.pressable
import com.ikverse.deskglow.widgets.Widgets
import kotlin.math.min

private val CATEGORIES = listOf("All", "Time", "Day", "Battery", "F1", "Prayer")

/** Which chip a widget is found under. */
private fun categoryOf(id: String) = when {
    id == "clock" || id == "alarm" -> "Time"
    id == "date" || id == "event" || id == "weather" || id == "sunmoon" || id == "notifs" || id == "media" -> "Day"
    id == "ring" || id == "stat" -> "Battery"
    id == "prayer" -> "Prayer"
    id.startsWith("f1") -> "F1"
    else -> "Other"
}

/** Dp per canvas unit that every preview is drawn at, so a clock and a stat are in true proportion to each other. */
private const val PREVIEW_SCALE = 0.36f

/**
 * The add-widget picker: every kind of widget drawn live, all at one scale, with a search box and
 * category chips to narrow them.
 */
@Composable
internal fun AddPicker(state: EditorState, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val shown = Widgets.all.filter { type ->
        (category == "All" || categoryOf(type.id) == category) &&
            (query.isBlank() || type.label.contains(query.trim(), ignoreCase = true) || type.blurb.contains(query.trim(), ignoreCase = true))
    }
    Column(modifier.background(Palette.Sheet).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add a widget", fontSize = Type.Heading, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
            IconAction(Glyph.Close, "Close", { state.pickerOpen = false }, Modifier.testTag("close picker"), tint = Palette.Muted)
        }
        SearchBox(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (name in CATEGORIES) Chip(name, { category = name }, selected = name == category)
        }
        if (shown.isEmpty()) {
            Text("Nothing matches “${query.trim()}”.", color = Palette.Muted, fontSize = Type.Body, modifier = Modifier.padding(20.dp))
        }
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), Modifier.fillMaxSize().testTag("picker grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { type ->
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.clip(shape).background(Palette.Raised).border(1.dp, Palette.Rule, shape)
                        .pressable { state.add(type) }.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The same scale for every widget, shrunk only if the widest would not fit this card.
                        val scale = min(PREVIEW_SCALE, maxWidth.value * 0.94f / type.width)
                        Box(Modifier.size((type.width * scale).dp, (type.height * scale).dp)) {
                            CompositionLocalProvider(LocalTextStyle provides WidgetTextStyle) { type.Content(type.defaults) }
                        }
                    }
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(type.label, fontSize = Type.Body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(type.blurb, fontSize = Type.Label, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(Palette.Page).border(1.dp, Palette.Rule, shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Search, tint = Palette.Muted, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Weather, F1, battery", color = Palette.Muted, fontSize = Type.Body)
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = TextStyle(color = Palette.Ink, fontSize = Type.Body),
                cursorBrush = SolidColor(Palette.Select),
                modifier = Modifier.fillMaxWidth().testTag("picker search"),
            )
        }
    }
}
