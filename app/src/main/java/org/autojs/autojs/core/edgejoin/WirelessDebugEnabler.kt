package org.autojs.autojs.core.edgejoin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.view.accessibility.AccessibilityService
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-enables the device's Wireless Debugging (adb over Wi-Fi) toggle via the
 * AccessibilityService when the native EdgeJoin client reports that the local
 * adbd endpoint is unreachable (typically after a reboot closes the dynamic
 * TLS port).
 *
 * Flow:
 *  1. If wireless debugging is already on, just re-resolve the port over mDNS
 *     and push it back into the running native client.
 *  2. Otherwise open the Wireless Debugging settings page and toggle the master
 *     switch on through accessibility node actions, then resolve + push the port.
 */
object WirelessDebugEnabler {

    private const val TAG = "WirelessDebugEnabler"
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    private const val SETTLE_AFTER_LAUNCH_MS = 1_200L
    private const val TOGGLE_RETRY_COUNT = 6
    private const val TOGGLE_RETRY_INTERVAL_MS = 700L
    private const val PORT_RESOLVE_ATTEMPTS = 4
    private const val PORT_RESOLVE_TIMEOUT_MS = 5_000L
    private const val POST_TOGGLE_SETTLE_MS = 1_500L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WirelessDebugEnabler").apply { isDaemon = true }
    }

    /** Guards against concurrent/overlapping enable attempts. */
    private val inProgress = AtomicBoolean(false)

    /**
     * Dispatches the enable-and-refresh routine on a background thread. Safe to
     * call from the JNI callback thread; returns immediately.
     */
    @JvmStatic
    fun requestEnableAndRefresh(context: Context) {
        val appContext = context.applicationContext
        if (!inProgress.compareAndSet(false, true)) {
            Log.i(TAG, "requestEnableAndRefresh: already in progress, ignoring")
            return
        }
        executor.execute {
            try {
                enableAndRefresh(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "enableAndRefresh failed", t)
            } finally {
                inProgress.set(false)
            }
        }
    }

    private fun enableAndRefresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "enableAndRefresh: wireless debugging requires Android 11+, skipping")
            return
        }

        if (isWirelessDebugEnabled(context)) {
            Log.i(TAG, "enableAndRefresh: wireless debug already enabled, refreshing port only")
            resolveAndPushPort(context)
            return
        }

        val toggled = openSettingsAndToggle(context)
        if (!toggled) {
            Log.w(TAG, "enableAndRefresh: failed to toggle wireless debug via accessibility")
        }

        // Give adbd time to (re)advertise its mDNS service after the toggle.
        sleepQuietly(POST_TOGGLE_SETTLE_MS)
        resolveAndPushPort(context)
    }

    private fun isWirelessDebugEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
        } catch (t: Throwable) {
            Log.w(TAG, "isWirelessDebugEnabled: failed to read global setting", t)
            false
        }
    }

    private fun openSettingsAndToggle(context: Context): Boolean {
        val service = AccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "openSettingsAndToggle: accessibility service not running, cannot toggle")
            return false
        }

        if (!launchWirelessDebuggingSettings(context)) {
            return false
        }

        sleepQuietly(SETTLE_AFTER_LAUNCH_MS)

        repeat(TOGGLE_RETRY_COUNT) { attempt ->
            if (isWirelessDebugEnabled(context)) {
                Log.i(TAG, "openSettingsAndToggle: wireless debug enabled (attempt=$attempt)")
                return true
            }
            val root = try {
                service.rootInActiveWindow
            } catch (t: Throwable) {
                Log.w(TAG, "openSettingsAndToggle: rootInActiveWindow failed", t)
                null
            }
            if (root != null) {
                val clicked = try {
                    findAndClickMasterSwitch(root)
                } finally {
                    safeRecycle(root)
                }
                if (clicked) {
                    Log.i(TAG, "openSettingsAndToggle: clicked toggle (attempt=$attempt)")
                    sleepQuietly(TOGGLE_RETRY_INTERVAL_MS)
                    if (isWirelessDebugEnabled(context)) {
                        return true
                    }
                }
            }
            sleepQuietly(TOGGLE_RETRY_INTERVAL_MS)
        }

        return isWirelessDebugEnabled(context)
    }

    private fun launchWirelessDebuggingSettings(context: Context): Boolean {
        val actions = listOf(
            "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )
        for (action in actions) {
            try {
                val intent = Intent(action).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                context.startActivity(intent)
                Log.i(TAG, "launchWirelessDebuggingSettings: launched $action")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "launchWirelessDebuggingSettings: failed to launch $action", t)
            }
        }
        return false
    }

    /**
     * Finds the master Wireless Debugging switch in the current window and clicks
     * it if it is currently off. Strategy: collect checkable Switch nodes; prefer
     * one whose label matches known wireless-debug text, else fall back to the
     * first unchecked switch (the master toggle sits at the top of this screen).
     */
    private fun findAndClickMasterSwitch(root: AccessibilityNodeInfo): Boolean {
        val switches = ArrayList<AccessibilityNodeInfo>()
        collectSwitchNodes(root, switches)
        if (switches.isEmpty()) {
            return false
        }

        var target = switches.firstOrNull { node ->
            !node.isChecked && labelMatchesWirelessDebug(node)
        }
        if (target == null) {
            target = switches.firstOrNull { !it.isChecked }
        }
        if (target == null) {
            // Everything already checked.
            switches.forEach { if (it !== root) safeRecycle(it) }
            return false
        }

        val clicked = clickNodeOrClickableParent(target)
        switches.forEach { if (it !== root && it !== target) safeRecycle(it) }
        if (target !== root) safeRecycle(target)
        return clicked
    }

    private fun collectSwitchNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val className = node.className?.toString().orEmpty()
        if (node.isCheckable && (className.contains("Switch") || className.contains("Toggle"))) {
            out.add(node)
            // Do not descend into the switch itself.
            return
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            collectSwitchNodes(child, out)
        }
    }

    private fun labelMatchesWirelessDebug(node: AccessibilityNodeInfo): Boolean {
        val haystack = buildString {
            append(node.text?.toString().orEmpty())
            append(' ')
            append(node.contentDescription?.toString().orEmpty())
            append(' ')
            append(node.viewIdResourceName.orEmpty())
        }.lowercase()
        // viewIdResourceName for the AOSP master switch is typically
        // "com.android.settings:id/switch_widget" / "switch_bar"; combined with
        // the screen context this is a strong signal. Text labels vary by locale,
        // so keep English keywords as a soft hint only.
        return haystack.contains("switch_bar") ||
            haystack.contains("switch_widget") ||
            haystack.contains("wireless") ||
            haystack.contains("debug")
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (current !== node) safeRecycle(current)
                return ok
            }
            val parent = current.parent
            if (current !== node) safeRecycle(current)
            current = parent
            depth++
        }
        // Fall back to clicking the switch node itself even if not reported clickable.
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun resolveAndPushPort(context: Context) {
        for (attempt in 0 until PORT_RESOLVE_ATTEMPTS) {
            val port = try {
                WirelessDebugPortResolver.resolvePort(context, PORT_RESOLVE_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "resolveAndPushPort: resolve failed (attempt=$attempt)", t)
                0
            }
            if (port in 1..65535) {
                try {
                    EdgeJoinBridge.persistAdbProxyEndpoint("127.0.0.1", port)
                    val result = EdgeJoinBridge.setAdbProxyTarget("127.0.0.1", port)
                    Log.i(TAG, "resolveAndPushPort: pushed port=$port result=$result")
                } catch (t: Throwable) {
                    Log.w(TAG, "resolveAndPushPort: failed to push port=$port", t)
                }
                return
            }
            Log.i(TAG, "resolveAndPushPort: port not discovered yet (attempt=$attempt)")
        }
        Log.w(TAG, "resolveAndPushPort: gave up resolving wireless debug port")
    }

    private fun safeRecycle(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
