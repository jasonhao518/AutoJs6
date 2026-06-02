package org.autojs.autojs.core.deviceadmin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import org.autojs.autojs.core.edgejoin.WirelessDebugPortResolver
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs6.R
import org.json.JSONObject

/**
 * Handles inline pairing code replies from the EdgeJoin pairing notification
 * (Shizuku-style RemoteInput action).
 */
class EdgeJoinPairingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == DeviceOwnerProvisioningGuard.ACTION_OPEN_PAIR_PAGE) {
            openDeveloperOptionsExternally(context.applicationContext)
            return
        }

        if (action != DeviceOwnerProvisioningGuard.ACTION_SUBMIT_PAIR_CODE) {
            return
        }

        val appContext = context.applicationContext
        val resultBundle = RemoteInput.getResultsFromIntent(intent)
        val pairCode = resultBundle
            ?.getCharSequence(DeviceOwnerProvisioningGuard.REMOTE_INPUT_KEY_PAIR_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (pairCode.length < 6) {
            postResultNotification(appContext, appContext.getString(R.string.error_invalid_pair_code), true)
            return
        }

        postResultNotification(appContext, appContext.getString(R.string.text_adb_pair_endpoint_resolving), false)

        Thread {
            runCatching {
                val pairEndpoint = resolvePairingEndpointWithRetry(appContext)
                    ?: throw IllegalStateException(appContext.getString(R.string.error_adb_pair_endpoint_not_found))

                val debugEndpoint = resolveDebugEndpoint(appContext)
                    ?: throw IllegalStateException(appContext.getString(R.string.error_adb_debug_endpoint_not_found))

                val pairResponse = EdgeJoinBridge.pairWirelessAndProvision(
                    "${pairEndpoint.host}:${pairEndpoint.port}",
                    pairCode,
                    debugEndpoint,
                )

                val result = runCatching { JSONObject(pairResponse) }.getOrNull()
                val ok = result?.optBoolean("ok", false) == true
                if (!ok) {
                    val error = result?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.error_adb_pair_failed)
                    throw IllegalStateException(error)
                }

                val state = result.optString("state", "")
                val message = when (state) {
                    "paired_device_owner" -> appContext.getString(R.string.text_adb_pair_device_owner_ready)
                    "paired_no_device_owner" -> appContext.getString(R.string.text_adb_pair_only_success)
                    else -> appContext.getString(R.string.text_adb_pair_success)
                }
                postResultNotification(appContext, message, false)
            }.onFailure { t ->
                Log.w(TAG, "Inline notification pairing failed", t)
                postResultNotification(appContext, t.message ?: appContext.getString(R.string.error_adb_pair_failed), true)
            }
        }.start()
    }

    private fun openDeveloperOptionsExternally(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        val pm = context.packageManager
        intents.firstOrNull { it.resolveActivity(pm) != null }?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }?.let {
            runCatching { context.startActivity(it) }
                .onFailure { e -> Log.w(TAG, "Failed to open Developer Options from notification", e) }
        }
    }

    private fun resolvePairingEndpointWithRetry(context: Context): WirelessDebugPortResolver.Endpoint? {
        repeat(3) { attempt ->
            val endpoint = WirelessDebugPortResolver.resolvePairingEndpoint(context, 2_000L)
            if (endpoint != null) {
                if (attempt > 0) {
                    Log.i(TAG, "resolvePairingEndpointWithRetry: resolved on attempt=${attempt + 1}")
                }
                return endpoint
            }
        }
        return null
    }

    private fun resolveDebugEndpoint(context: Context): String? {
        // The wireless-debug CONNECT port is dynamic and changes on every toggle
        // or reboot, so a stored value goes stale (connection refused). Always
        // discover the live `_adb-tls-connect._tcp` port first; fall back to the
        // persisted endpoint only if mDNS discovery fails.
        val discovered = WirelessDebugPortResolver.resolvePort(context)
        if (discovered in 1..65535) {
            val changed = EdgeJoinBridge.persistAdbProxyEndpoint("127.0.0.1", discovered)
            Log.i(TAG, "resolveDebugEndpoint: discovered connect port=$discovered (changed=$changed)")
            return "127.0.0.1:$discovered"
        }
        val prefs = context.getSharedPreferences(PREF_EDGEJOIN, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_ADB_PROXY_HOST, "")?.trim().orEmpty()
        val port = prefs.getInt(KEY_ADB_PROXY_PORT, 0)
        return if (host.isNotBlank() && port in 1..65535) {
            Log.i(TAG, "resolveDebugEndpoint: connect mDNS failed, using stored $host:$port")
            "$host:$port"
        } else {
            null
        }
    }

    private fun postResultNotification(context: Context, message: String, isError: Boolean) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "postResultNotification: skip, notifications disabled or permission missing")
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.text_adb_wireless_pair),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(ch)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0xE746,
            MainActivity.getIntent(context).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ali_notification)
            .setContentTitle(context.getString(R.string.text_adb_pair_notification_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onFailure { t -> Log.w(TAG, "postResultNotification: notify failed", t) }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    companion object {
        private const val TAG = "EdgeJoinPairAction"
        private const val PREF_EDGEJOIN = "edgejoin"
        private const val KEY_ADB_PROXY_HOST = "adb_proxy_host"
        private const val KEY_ADB_PROXY_PORT = "adb_proxy_port"
        private const val CHANNEL_ID = "edgejoin_pairing"
        private const val NOTIFICATION_ID = 0xE743
    }
}
