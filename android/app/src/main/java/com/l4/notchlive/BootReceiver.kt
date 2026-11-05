package com.l4.notchlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts watching again after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).enabled) LiveService.start(ctx)
    }
}
