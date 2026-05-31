package org.autojs.autojs.external.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.autojs.autojs.external.foreground.EdgeJoinForegroundService

class EdgeJoinRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_EDGEJOIN_RESTART) {
            return
        }
        val started = EdgeJoinForegroundService.startIfConfigured(context.applicationContext)
        Log.i(TAG, "restart receiver action=$action started=$started")
    }

    companion object {
        private const val TAG = "EdgeJoinRestartRcvr"
        const val ACTION_EDGEJOIN_RESTART = "org.autojs.autojs.action.edgejoin.RESTART"
    }
}
