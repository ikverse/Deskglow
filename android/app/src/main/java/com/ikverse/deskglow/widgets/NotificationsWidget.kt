package com.ikverse.deskglow.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikverse.deskglow.data.LocalFeeds
import com.ikverse.deskglow.data.NotificationState
import com.ikverse.deskglow.model.FlagKey
import com.ikverse.deskglow.model.IntKey
import com.ikverse.deskglow.model.Settings
import com.ikverse.deskglow.model.TextKey

object NotificationsWidget : WidgetType {
    val MAX = IntKey("max", 5)
    val MORE = FlagKey("more", true)
    /** "plain" icons, "pill" (the icons in a dim capsule) or "dots" (a dot for each app). */
    val STYLE = TextKey("style", "plain")
    /** "nothing", or "text" to say "All caught up" with no notifications. */
    val EMPTY = TextKey("empty", "nothing")

    override val id = "notifs"
    override val label = "Notifications"
    override val blurb = "Unread app icons"
    override val width = 164
    override val height = 28
    override val defaults: Settings = Common.base(0xFFCFCFCF).with(Common.ALIGN, "center")

    override fun fields(settings: Settings) = listOf(
        ChoiceField("Style", STYLE, listOf("plain" to "Icons", "pill" to "Pill", "dots" to "Dots")),
        SliderField("Icons shown", MAX, 3..8),
        ChoiceField("When empty", EMPTY, listOf("nothing" to "Nothing", "text" to "All caught up")),
        ToggleField("Show +N when there are more", MORE),
        Common.alignField,
        Common.colourField,
        Common.brightnessField,
    )

    override fun note(settings: Settings) = "Needs notification access (Home › Permissions)."

    @Composable
    override fun Content(settings: Settings) {
        val state by LocalFeeds.current.notifications.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            when (val s = state) {
                NotificationState.NoAccess -> EditorHint("Allow notification access", h * 0.42f)
                is NotificationState.Apps -> {
                    val shown = s.apps.take(settings[MAX])
                    val extra = s.apps.size - shown.size
                    val colour = Color(settings[Common.COLOUR])
                    val style = settings[STYLE]
                    val icon = h * 0.62f
                    val arrangement = arrangementOf(settings[Common.ALIGN])
                    if (shown.isEmpty()) {
                        if (settings[EMPTY] == "text") {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
                                Text("All caught up", color = Muted, fontSize = pxToSp(h * 0.42f), maxLines = 1, softWrap = false)
                            }
                        } else {
                            EditorHint("No notifications", h * 0.42f)
                        }
                        return@BoxWithConstraints
                    }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
                        val capsule = if (style == "pill") Modifier.background(Color(0xFF1C1C1C), RoundedCornerShape(50)).padding(horizontal = pxToDp(icon * 0.1f), vertical = pxToDp(h * 0.08f)) else Modifier
                        Row(capsule, verticalAlignment = Alignment.CenterVertically) {
                            shown.forEach { app ->
                                val bitmap = app.icon
                                if (bitmap != null && style != "dots") {
                                    Image(bitmap, contentDescription = null, colorFilter = ColorFilter.tint(colour), modifier = Modifier.padding(horizontal = pxToDp(icon * 0.32f)).size(pxToDp(icon)))
                                } else {
                                    Canvas(Modifier.padding(horizontal = pxToDp(icon * 0.32f)).size(pxToDp(icon))) { drawCircle(colour, size.minDimension * 0.3f) }
                                }
                            }
                            if (settings[MORE] && extra > 0) Text("+$extra", color = Muted, fontSize = pxToSp(h * 0.46f), modifier = Modifier.padding(end = pxToDp(icon * 0.2f)))
                        }
                    }
                }
            }
        }
    }
}
