package org.autojs.autojs.core.deviceadmin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs6.R

object DeviceOwnerProvisioningGuard {

    private const val TAG = "DeviceOwnerGuard"
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val PROMPT_MIN_INTERVAL_MS = 30_000L

    @Volatile
    private var sLastPromptUptimeMs = 0L

    fun isDeviceOwnerApp(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return dpm?.isDeviceOwnerApp(context.packageName) == true
    }

    fun isWirelessDebugEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
        } catch (t: Throwable) {
            Log.w(TAG, "isWirelessDebugEnabled: failed to read global setting", t)
            false
        }
    }

    fun shouldPromptEnableWirelessDebug(context: Context): Boolean {
        if (isDeviceOwnerApp(context)) {
            return false
        }
        return !isWirelessDebugEnabled(context)
    }

    fun maybeShowWirelessDebugDialog(activity: Activity) {
        if (!shouldPromptEnableWirelessDebug(activity)) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        MaterialDialog.Builder(activity)
            .title(R.string.text_wireless_debug_required_title)
            .content(R.string.text_wireless_debug_required_content)
            .positiveText(R.string.text_open_developer_options)
            .onPositive { _, _ -> IntentUtils.launchDeveloperOptionsOrSettings(activity) }
            .negativeText(R.string.dialog_button_cancel)
            .show()
    }

    fun maybePromptFromForegroundService(context: Context) {
        if (!shouldPromptEnableWirelessDebug(context)) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - sLastPromptUptimeMs < PROMPT_MIN_INTERVAL_MS) {
            return
        }
        sLastPromptUptimeMs = now

        val intent = MainActivity.getIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "maybePromptFromForegroundService: unable to launch MainActivity", t)
        }
    }
}
