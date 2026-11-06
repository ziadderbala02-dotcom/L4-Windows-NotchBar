package com.l4.notchlive

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: hides the notch (goals and cards then come as notifications) or shows
 * it again, without opening the app.
 */
class NotchTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        draw()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        prefs.paused = !prefs.paused
        draw()
        if (prefs.enabled) LiveService.start(this, LiveService.ACTION_REFRESH)
    }

    private fun draw() {
        val tile = qsTile ?: return
        val on = !Prefs(this).paused
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Notch Live"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "ظاهرة" else "مستخبية"
        tile.updateTile()
    }
}
