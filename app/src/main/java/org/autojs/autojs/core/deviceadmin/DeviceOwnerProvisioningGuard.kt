package org.autojs.autojs.core.deviceadmin

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs.util.IntentUtils.startSafely
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R

object DeviceOwnerProvisioningGuard {

    private const val TAG = "DeviceOwnerGuard"
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val PROMPT_MIN_INTERVAL_MS = 30_000L
    private const val PREF_EDGEJOIN = "edgejoin"
    private const val KEY_ADB_PROXY_HOST = "adb_proxy_host"
    private const val KEY_ADB_PROXY_PORT = "adb_proxy_port"
    private const val NOTIFICATION_CHANNEL_ID = "edgejoin_pairing"
    private const val NOTIFICATION_ID = 0xE743
    private const val EXTRA_OPEN_PAIR_INPUT = "extra_open_wireless_pair_input"
    internal const val ACTION_SUBMIT_PAIR_CODE = "org.autojs.autojs.action.edgejoin.SUBMIT_PAIR_CODE"
    internal const val ACTION_OPEN_PAIR_PAGE = "org.autojs.autojs.action.edgejoin.OPEN_PAIR_PAGE"
    internal const val REMOTE_INPUT_KEY_PAIR_CODE = "edgejoin_pair_code"

    @Volatile
    private var sLastPromptUptimeMs = 0L

    fun isWirelessDebugEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
        } catch (t: Throwable) {
            Log.w(TAG, "isWirelessDebugEnabled: failed to read global setting", t)
            false
        }
    }

    private fun isEdgeJoinConfigured(context: Context): Boolean {
        return runCatching { EdgeJoinBridge.loadStoredConfig().isNotBlank() }
            .getOrDefault(false)
    }

    private fun hasPairingEndpoint(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_EDGEJOIN, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_ADB_PROXY_HOST, "")?.trim().orEmpty()
        val port = prefs.getInt(KEY_ADB_PROXY_PORT, 0)
        return host.isNotBlank() && port in 1..65535
    }

    fun consumeOpenPairInputFlag(activity: Activity): Boolean {
        val intent = activity.intent ?: return false
        val open = intent.getBooleanExtra(EXTRA_OPEN_PAIR_INPUT, false)
        if (open) {
            intent.removeExtra(EXTRA_OPEN_PAIR_INPUT)
            activity.intent = intent
        }
        return open
    }

    fun maybeHandleOnAppOpened(activity: Activity) {
        if (!isEdgeJoinConfigured(activity)) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (isWirelessDebugEnabled(activity)) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - sLastPromptUptimeMs < PROMPT_MIN_INTERVAL_MS) {
            return
        }
        sLastPromptUptimeMs = now

        val paired = hasPairingEndpoint(activity)

        MaterialDialog.Builder(activity)
            .title(R.string.text_wireless_debug_required_title)
            .content(
                if (paired) {
                    R.string.text_wireless_debug_required_content
                } else {
                    R.string.text_wireless_pair_required_content
                },
            )
            .positiveText(R.string.text_open_developer_options)
            .onPositive { _, _ ->
                IntentUtils.launchDeveloperOptionsOrSettingsExternally(activity)
                if (!paired) {
                    notifyPairingNotification(activity)
                    ViewUtils.showToast(activity, R.string.text_adb_pair_open_page_hint, true)
                }
            }
            .negativeText(R.string.dialog_button_cancel)
            .show()
    }

    fun notifyPairingNotification(context: Context) {
        val appContext = context.applicationContext
        if (!isPairingNotificationEnabled(appContext)) {
            Log.w(TAG, "notifyPairingNotification: notifications disabled")
            maybeOpenNotificationSettings(context)
            return
        }
        // Shizuku-style: start a foreground service that auto-discovers the
        // pairing endpoint and lets the user enter the pairing code directly
        // from the notification (RemoteInput), instead of a multi-step dialog.
        WirelessAdbPairingService.start(appContext)
    }

    fun maybePromptFromForegroundService(context: Context) {
        if (!isEdgeJoinConfigured(context) || hasPairingEndpoint(context)) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - sLastPromptUptimeMs < PROMPT_MIN_INTERVAL_MS) {
            return
        }
        sLastPromptUptimeMs = now

        notifyPairingNotification(context)

        val intent = MainActivity.getIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Do not auto-open pair input from service startup. It can dismiss the
            // system "Pair device with pairing code" dialog and rotate the code.
            putExtra(EXTRA_OPEN_PAIR_INPUT, false)
        }

        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "maybePromptFromForegroundService: unable to launch MainActivity", t)
        }
    }

    // Match Shizuku's behavior: require app notifications enabled and channel
    // not explicitly blocked before starting notification-driven pairing flow.
    private fun isPairingNotificationEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            val channel = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    private fun maybeOpenNotificationSettings(context: Context) {
        runCatching {
            ViewUtils.showToast(context, R.string.error_no_post_notifications_permission, true)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    data = Uri.parse("package:${context.packageName}")
                }
            }

            intent
                .apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
                .startSafely(context.applicationContext)
        }
    }

}
