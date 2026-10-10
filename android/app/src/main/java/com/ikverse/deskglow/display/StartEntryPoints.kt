package com.ikverse.deskglow.display

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.ikverse.deskglow.R

/** The intent that starts the display from outside the app: the widget and the tile both use it. */
private fun startDisplayIntent(context: Context) =
    Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun startDisplayPress(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, startDisplayIntent(context),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** The widget's three looks, and the smallest size (dp) at which each one fits. */
private enum class WidgetSize(val layout: Int, val minWidth: Float, val minHeight: Float) {
    Small(R.layout.start_widget_small, 40f, 40f),
    Wide(R.layout.start_widget, 110f, 50f),
    Large(R.layout.start_widget_large, 130f, 130f),
}

private fun widgetSizeFor(widthDp: Float, heightDp: Float): WidgetSize =
    WidgetSize.entries.last { widthDp >= it.minWidth && heightDp >= it.minHeight }

/**
 * The home-screen widget: a branded button that starts the display, resizable up to 2x2 and
 * beyond. It shows nothing that changes, so it never updates by itself and costs no battery while
 * it sits on the launcher; it only picks the layout that fits its current size.
 */
class StartWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        update(context, manager, appWidgetId)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val press = startDisplayPress(context)
        fun viewsFor(size: WidgetSize) = RemoteViews(context.packageName, size.layout).apply {
            setOnClickPendingIntent(R.id.start_root, press)
        }

        val views = if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: hand the launcher every look and let it choose as the widget is resized.
            RemoteViews(WidgetSize.entries.associate { SizeF(it.minWidth, it.minHeight) to viewsFor(it) })
        } else {
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).toFloat()
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).toFloat()
            // Before the launcher reports a size, assume the default 2x2.
            viewsFor(if (width == 0f || height == 0f) WidgetSize.Large else widgetSizeFor(width, height))
        }
        manager.updateAppWidget(id, views)
    }
}

/** A Quick Settings tile that starts the display with one tap from the notification shade. */
class StartTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) {
            // From Android 14 the shade has to be handed a PendingIntent rather than an Intent.
            startActivityAndCollapse(startDisplayPress(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(startDisplayIntent(this))
        }
    }
}
