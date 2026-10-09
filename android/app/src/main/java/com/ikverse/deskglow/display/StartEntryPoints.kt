package com.ikverse.deskglow.display

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
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

/**
 * The home-screen widget: a button that starts the display. It shows nothing that changes, so it
 * never updates by itself and costs no battery while it sits on the launcher.
 */
class StartWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.start_widget).apply {
            setOnClickPendingIntent(R.id.start_root, startDisplayPress(context))
        }
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
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
