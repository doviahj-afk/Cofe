package com.joshua.screenrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class RecordingTileService : TileService() {

    companion object {
        // NOTE: tile refresh actually happens via the LocalBroadcastManager
        // ACTION_STATE_CHANGED broadcast (see stateReceiver below), sent from
        // RecordingService.broadcastState(). A previous version of this class
        // exposed requestTileUpdate(), which sent a *regular* (non-local)
        // broadcast that nothing was ever registered to receive -- dead code,
        // now removed.
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(RecordingService.ACTION_STATE_CHANGED))
        refresh()
    }

    override fun onStopListening() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        } catch (e: Exception) {}
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (RecordingService.isRunning) {
            val stopIntent = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
            startService(stopIntent)
            refresh()
        } else {
            val activityIntent = Intent(this, CaptureConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = android.app.PendingIntent.getActivity(
                    this, 0, activityIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(activityIntent)
            }
        }
    }

    private fun refresh() {
        val tile: Tile = qsTile ?: return
        if (RecordingService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Recording…"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Screen Recorder"
        }
        tile.updateTile()
    }
}
