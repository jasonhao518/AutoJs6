package org.autojs.autojs.core.edgejoin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.view.accessibility.AccessibilityService
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private const val TAG = "EdgeJoin"
    private const val LOG_SCOPE = "[WDE] "
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val LEGACY_ADB_TCP_PORT = 5555
    private const val LOCAL_PORT_CHECK_TIMEOUT_MS = 600

    private const val SETTLE_AFTER_LAUNCH_MS = 1_200L
    private const val TOGGLE_RETRY_COUNT = 6
    private const val TOGGLE_RETRY_INTERVAL_MS = 700L
    private const val PORT_RESOLVE_ATTEMPTS = 4
    private const val PORT_RESOLVE_TIMEOUT_MS = 5_000L
    private const val POST_TOGGLE_SETTLE_MS = 1_500L
    private const val TREE_LOG_MAX_DEPTH = 3
    private const val TREE_LOG_MAX_CHILDREN = 6

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WirelessDebugEnabler").apply { isDaemon = true }
    }

    /** Guards against concurrent/overlapping enable attempts. */
    private val inProgress = AtomicBoolean(false)

    private fun scoped(msg: String): String = "$LOG_SCOPE$msg"

    /**
     * Dispatches the enable-and-refresh routine on a background thread. Safe to
     * call from the JNI callback thread; returns immediately.
     */
    @JvmStatic
    fun requestEnableAndRefresh(context: Context) {
        val appContext = context.applicationContext
        if (!inProgress.compareAndSet(false, true)) {
            Log.i(TAG, scoped("requestEnableAndRefresh: already in progress, ignoring"))
            return
        }
        Log.i(TAG, scoped("requestEnableAndRefresh: scheduled enable/refresh task"))
        executor.execute {
            try {
                enableAndRefresh(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, scoped("enableAndRefresh failed"), t)
            } finally {
                inProgress.set(false)
            }
        }
    }

    private fun enableAndRefresh(context: Context) {
        Log.i(TAG, scoped("enableAndRefresh: start"))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, scoped("enableAndRefresh: wireless debugging requires Android 11+, skipping"))
            return
        }

        if (isWirelessDebugEnabled(context)) {
            Log.i(TAG, scoped("enableAndRefresh: wireless debug already enabled, refreshing port only"))
            resolveAndPushPort(context)
            return
        }

        val toggled = openSettingsAndToggle(context)
        if (!toggled) {
            Log.w(TAG, scoped("enableAndRefresh: failed to toggle wireless debug via accessibility"))
        } else {
            Log.i(TAG, scoped("enableAndRefresh: wireless debug toggle flow completed"))
        }

        // Give adbd time to (re)advertise its mDNS service after the toggle.
        sleepQuietly(POST_TOGGLE_SETTLE_MS)
        resolveAndPushPort(context)
    }

    private fun isWirelessDebugEnabled(context: Context): Boolean {
        val enabledBySetting = try {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
        } catch (t: Throwable) {
            Log.w(TAG, scoped("isWirelessDebugEnabled: failed to read global setting"), t)
            false
        }
        if (enabledBySetting) {
            return true
        }

        // Some devices still expose local adbd on tcp/5555 while adb_wifi_enabled
        // remains off. Treat this as "wireless debug available" for server mode.
        val enabledByPort = isLocalPortReachable(LEGACY_ADB_TCP_PORT)
        if (enabledByPort) {
            Log.i(TAG, scoped("isWirelessDebugEnabled: treating localhost:$LEGACY_ADB_TCP_PORT as enabled wireless debug"))
        }
        return enabledByPort
    }

    private fun openSettingsAndToggle(context: Context): Boolean {
        if (!launchWirelessDebuggingSettings(context)) {
            return false
        }

        val service = AccessibilityService.instance
        if (service == null) {
            Log.w(TAG, scoped("openSettingsAndToggle: settings launched, but accessibility service is not running; cannot auto-toggle"))
            return false
        }
        Log.i(TAG, scoped("openSettingsAndToggle: accessibility service available, attempting auto-toggle"))

        sleepQuietly(SETTLE_AFTER_LAUNCH_MS)

        repeat(TOGGLE_RETRY_COUNT) { attempt ->
            if (isWirelessDebugEnabled(context)) {
                Log.i(TAG, scoped("openSettingsAndToggle: wireless debug enabled (attempt=$attempt)"))
                return true
            }
            val root = try {
                service.rootInActiveWindow
            } catch (t: Throwable) {
                Log.w(TAG, scoped("openSettingsAndToggle: rootInActiveWindow failed"), t)
                null
            }
            if (root != null) {
                Log.d(
                    TAG,
                    scoped(
                        "openSettingsAndToggle: root snapshot attempt=$attempt " +
                            "pkg=${root.packageName?.toString().orEmpty()} cls=${root.className?.toString().orEmpty()} " +
                            "children=${root.childCount}",
                    ),
                )
                val clicked = try {
                    findAndClickMasterSwitch(root)
                } finally {
                    safeRecycle(root)
                }
                if (clicked) {
                    Log.i(TAG, scoped("openSettingsAndToggle: clicked toggle (attempt=$attempt)"))
                    sleepQuietly(TOGGLE_RETRY_INTERVAL_MS)
                    if (isWirelessDebugEnabled(context)) {
                        return true
                    }
                    Log.w(TAG, scoped("openSettingsAndToggle: click reported success but adb_wifi_enabled still off (attempt=$attempt)"))
                } else {
                    Log.w(TAG, scoped("openSettingsAndToggle: no clickable wireless-debug candidate found (attempt=$attempt)"))
                }
            } else {
                Log.w(TAG, scoped("openSettingsAndToggle: rootInActiveWindow is null (attempt=$attempt)"))
            }
            sleepQuietly(TOGGLE_RETRY_INTERVAL_MS)
        }

        return isWirelessDebugEnabled(context)
    }

    private fun launchWirelessDebuggingSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            // OEM fallback: explicit component under com.android.settings.
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$DevelopmentSettingsActivity",
            ),
            // Last resort: app info/details page (keeps user near settings if dev page is unavailable).
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            },
        )

        for (baseIntent in intents) {
            try {
                val intent = baseIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                val resolved = intent.resolveActivity(context.packageManager)
                if (resolved == null) {
                    Log.i(TAG, scoped("launchWirelessDebuggingSettings: no resolver for action=${intent.action} component=${intent.component}"))
                    continue
                }
                if (startActivityOnMainThread(context, intent)) {
                    Log.i(TAG, scoped("launchWirelessDebuggingSettings: launched action=${intent.action} component=${intent.component}"))
                    return true
                }
                Log.w(TAG, scoped("launchWirelessDebuggingSettings: launch returned false for action=${intent.action} component=${intent.component}"))
            } catch (t: Throwable) {
                Log.w(TAG, scoped("launchWirelessDebuggingSettings: failed to launch action=${baseIntent.action} component=${baseIntent.component}"), t)
            }
        }
        return false
    }

    private fun startActivityOnMainThread(context: Context, intent: Intent): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            context.startActivity(intent)
            return true
        }

        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(intent)
                result.set(true)
            } catch (t: Throwable) {
                Log.w(TAG, scoped("startActivityOnMainThread: startActivity failed for action=${intent.action}"), t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return result.get()
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
        Log.d(TAG, scoped("findAndClickMasterSwitch: discovered switch count=${switches.size}"))
        if (switches.isEmpty()) {
            Log.w(TAG, scoped("findAndClickMasterSwitch: no switch nodes found, dumping compact tree"))
            logCompactTree(root)
            return false
        }

        logSwitchCandidates(switches)

        var target = switches.firstOrNull { node ->
            !node.isChecked && labelMatchesWirelessDebug(node)
        }
        if (target == null) {
            target = switches.firstOrNull { !it.isChecked }
        }
        if (target == null) {
            // Everything already checked.
            Log.i(TAG, scoped("findAndClickMasterSwitch: no unchecked switch found"))
            logCompactTree(root)
            switches.forEach { if (it !== root) safeRecycle(it) }
            return false
        }

        val clicked = clickNodeOrClickableParent(target)
        Log.i(TAG, scoped("findAndClickMasterSwitch: click result=$clicked"))
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
                Log.d(TAG, scoped("clickNodeOrClickableParent: clicking depth=$depth cls=${current.className?.toString().orEmpty()} id=${current.viewIdResourceName.orEmpty()}"))
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
        Log.d(TAG, scoped("clickNodeOrClickableParent: fallback click on original node cls=${node.className?.toString().orEmpty()} id=${node.viewIdResourceName.orEmpty()}"))
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun logSwitchCandidates(switches: List<AccessibilityNodeInfo>) {
        switches.forEachIndexed { idx, n ->
            val label = buildString {
                append(n.text?.toString().orEmpty())
                append(" | ")
                append(n.contentDescription?.toString().orEmpty())
            }.trim()
            Log.d(
                TAG,
                scoped(
                    "switch[$idx]: cls=${n.className?.toString().orEmpty()} id=${n.viewIdResourceName.orEmpty()} " +
                        "checkable=${n.isCheckable} checked=${n.isChecked} clickable=${n.isClickable} " +
                        "enabled=${n.isEnabled} label=\"$label\"",
                ),
            )
        }
    }

    private fun logCompactTree(root: AccessibilityNodeInfo) {
        logCompactTreeNode(root, 0, 0)
    }

    private fun logCompactTreeNode(node: AccessibilityNodeInfo?, depth: Int, siblingIndex: Int) {
        if (node == null || depth > TREE_LOG_MAX_DEPTH) {
            return
        }
        val indent = "  ".repeat(depth)
        val text = node.text?.toString().orEmpty().take(60)
        val desc = node.contentDescription?.toString().orEmpty().take(60)
        Log.d(
            TAG,
            scoped(
                "tree:$indent[$siblingIndex] cls=${node.className?.toString().orEmpty()} id=${node.viewIdResourceName.orEmpty()} " +
                    "chk=${node.isCheckable}/${node.isChecked} clk=${node.isClickable} en=${node.isEnabled} " +
                    "txt=\"$text\" desc=\"$desc\"",
            ),
        )
        val count = minOf(node.childCount, TREE_LOG_MAX_CHILDREN)
        for (i in 0 until count) {
            logCompactTreeNode(node.getChild(i), depth + 1, i)
        }
    }

    private fun resolveAndPushPort(context: Context) {
        Log.i(TAG, scoped("resolveAndPushPort: start"))
        for (attempt in 0 until PORT_RESOLVE_ATTEMPTS) {
            val port = try {
                WirelessDebugPortResolver.resolvePort(context, PORT_RESOLVE_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, scoped("resolveAndPushPort: resolve failed (attempt=$attempt)"), t)
                0
            }
            if (port in 1..65535) {
                try {
                    EdgeJoinBridge.persistAdbProxyEndpoint("127.0.0.1", port)
                    val result = EdgeJoinBridge.setAdbProxyTarget("127.0.0.1", port)
                    Log.i(TAG, scoped("resolveAndPushPort: pushed port=$port result=$result"))
                } catch (t: Throwable) {
                    Log.w(TAG, scoped("resolveAndPushPort: failed to push port=$port"), t)
                }
                return
            }
            Log.i(TAG, scoped("resolveAndPushPort: port not discovered yet (attempt=$attempt)"))
        }

        if (isLocalPortReachable(LEGACY_ADB_TCP_PORT)) {
            try {
                EdgeJoinBridge.persistAdbProxyEndpoint("127.0.0.1", LEGACY_ADB_TCP_PORT)
                val result = EdgeJoinBridge.setAdbProxyTarget("127.0.0.1", LEGACY_ADB_TCP_PORT)
                Log.i(
                    TAG,
                    scoped("resolveAndPushPort: fallback to localhost:$LEGACY_ADB_TCP_PORT result=$result"),
                )
                return
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    scoped("resolveAndPushPort: failed fallback push to localhost:$LEGACY_ADB_TCP_PORT"),
                    t,
                )
            }
        }

        Log.w(TAG, scoped("resolveAndPushPort: gave up resolving wireless debug port"))
    }

    private fun isLocalPortReachable(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", port),
                    LOCAL_PORT_CHECK_TIMEOUT_MS,
                )
                true
            }
        } catch (_: Throwable) {
            false
        }
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
