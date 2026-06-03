package org.autojs.autojs.external.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.autojs.autojs.external.foreground.EdgeJoinForegroundService

/**
 * Starts EdgeJoin foreground service automatically after boot/user unlock and
 * after app updates, but only when EdgeJoin has stored configuration.
 */
class EdgeJoinAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val shouldHandle = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!shouldHandle) {
            return
        }
        val started = EdgeJoinForegroundService.startIfConfigured(context.applicationContext)
        Log.i(TAG, "auto-start receiver action=$action started=$started")
    }

    companion object {
        private const val TAG = "EdgeJoin"
    }
}
