package org.autojs.autojs.core.deviceadmin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.autojs.autojs.core.edgejoin.WirelessDebugPortResolver
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs6.R
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wireless ADB pairing service.
 *
 * This is a faithful port of Shizuku's `AdbPairingService` flow:
 *  - Starts as a foreground service and continuously searches for the device's
 *    own `_adb-tls-pairing._tcp` mDNS service while "Pair device with pairing
 *    code" is open in Developer options.
 *  - When the pairing endpoint is found, posts a notification with an inline
 *    RemoteInput action so the user can type the 6-digit pairing code directly
 *    in the notification (no dialog).
 *  - On reply, performs the native pairing + device-owner provisioning and shows
 *    a success / failure notification.
 *
 * zh-CN: 无线 ADB 配对服务, 移植自 Shizuku 的 AdbPairingService 流程.
 */
class WirelessAdbPairingService : Service() {

    private val searching = AtomicBoolean(false)
    private var searchThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> onStart()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (host.isNotBlank() && port in 1..65535) {
                    onInput(code, host, port)
                } else {
                    onStart()
                }
            }
            ACTION_STOP -> {
                stopSearch()
                stopForegroundCompat()
                stopSelf()
                null
            }
            else -> return START_NOT_STICKY
        }

        if (notification != null) {
            startForegroundCompat(notification)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onStart(): Notification {
        startSearch()
        return buildSearchingNotification()
    }

    private fun startSearch() {
        if (!searching.compareAndSet(false, true)) return
        searchThread = Thread {
            try {
                while (searching.get() && !Thread.currentThread().isInterrupted) {
                    val endpoint = WirelessDebugPortResolver.resolvePairingEndpoint(this, 2_000L)
                    if (endpoint != null) {
                        Log.i(TAG, "startSearch: pairing endpoint found ${endpoint.host}:${endpoint.port}")
                        notifyServiceFound(endpoint)
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "startSearch: discovery error", t)
            } finally {
                searching.set(false)
            }
        }.also { it.start() }
    }

    private fun stopSearch() {
        searching.set(false)
        searchThread?.interrupt()
        searchThread = null
    }

    private fun notifyServiceFound(endpoint: WirelessDebugPortResolver.Endpoint) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildInputNotification(endpoint.host, endpoint.port))
        }.onFailure { Log.w(TAG, "notifyServiceFound: notify failed", it) }
    }

    private fun onInput(code: String, host: String, port: Int): Notification {
        if (code.length < 6) {
            postResult(getString(R.string.error_invalid_pair_code), isError = true)
            return buildSearchingNotification()
        }

        Thread {
            runCatching {
                val debugEndpoint = resolveDebugEndpoint()
                    ?: throw IllegalStateException(getString(R.string.error_adb_debug_endpoint_not_found))

                val response = EdgeJoinBridge.pairWirelessAndProvision("$host:$port", code, debugEndpoint)
                val json = runCatching { JSONObject(response) }.getOrNull()
                val ok = json?.optBoolean("ok", false) == true
                if (!ok) {
                    val error = json?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.error_adb_pair_failed)
                    throw IllegalStateException(error)
                }

                var state = json.optString("state", "")
                // Pairing succeeded but the device-owner step did not complete.
                // The connect channel is often not ready in the same instant
                // pairing finishes, so retry the `dpm set-device-owner` step a
                // few times over the now-trusted wireless-debug channel.
                if (state == "paired_no_device_owner") {
                    state = retryProvisionDeviceOwner(debugEndpoint) ?: state
                }

                val message = when (state) {
                    "paired_device_owner" -> getString(R.string.text_adb_pair_device_owner_ready)
                    "paired_no_device_owner" -> getString(R.string.text_adb_pair_only_success)
                    else -> getString(R.string.text_adb_pair_success)
                }
                postResult(message, isError = false)
            }.onFailure { t ->
                Log.w(TAG, "onInput: pairing failed", t)
                postResult(t.message ?: getString(R.string.error_adb_pair_failed), isError = true)
            }
            stopSelf()
        }.start()

        return buildWorkingNotification()
    }

    private fun resolveDebugEndpoint(): String? {
        // The wireless-debug CONNECT port is dynamic: it changes whenever
        // wireless debugging is toggled or the device reboots, and it differs
        // from the ephemeral pairing port. A stored value therefore goes stale
        // quickly (symptom: `dial adbd 127.0.0.1:<oldPort>: connection refused`).
        // Always prefer a fresh mDNS discovery of `_adb-tls-connect._tcp` and
        // only fall back to the persisted endpoint if discovery fails.
        val discovered = WirelessDebugPortResolver.resolvePort(this)
        if (discovered in 1..65535) {
            EdgeJoinBridge.persistAdbProxyEndpoint("127.0.0.1", discovered)
            return "127.0.0.1:$discovered"
        }
        val prefs = getSharedPreferences(PREF_EDGEJOIN, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_ADB_PROXY_HOST, "")?.trim().orEmpty()
        val port = prefs.getInt(KEY_ADB_PROXY_PORT, 0)
        if (host.isNotBlank() && port in 1..65535) {
            Log.i(TAG, "resolveDebugEndpoint: connect mDNS failed, using stored $host:$port")
            return "$host:$port"
        }
        return null
    }

    /**
     * Retry the standalone `dpm set-device-owner` step over the trusted
     * wireless-debug channel. Returns the resulting state, or null if every
     * attempt failed (caller keeps the original state).
     */
    private fun retryProvisionDeviceOwner(debugEndpoint: String): String? {
        repeat(PROVISION_RETRY_COUNT) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(PROVISION_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            val response = EdgeJoinBridge.provisionDeviceOwner(debugEndpoint)
            val json = runCatching { JSONObject(response) }.getOrNull()
            if (json?.optBoolean("ok", false) == true) {
                Log.i(TAG, "retryProvisionDeviceOwner: succeeded on attempt ${attempt + 1}")
                return json.optString("state", "paired_device_owner")
            }
            Log.w(TAG, "retryProvisionDeviceOwner: attempt ${attempt + 1} failed: $response")
        }
        return null
    }

    private fun postResult(message: String, isError: Boolean) {
        stopSearch()
        stopForegroundCompat()
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.autojs6_status_bar_icon)
            .setContentTitle(
                getString(
                    if (isError) R.string.error_adb_pair_failed else R.string.text_adb_pair_success,
                ),
            )
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(appContentIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "postResult: notify failed", it) }
    }

    private fun buildSearchingNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.autojs6_status_bar_icon)
            .setContentTitle(getString(R.string.text_adb_pairing_searching_title))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(stopAction())
            .build()
    }

    private fun buildWorkingNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.autojs6_status_bar_icon)
            .setContentTitle(getString(R.string.text_adb_wireless_connecting))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun buildInputNotification(host: String, port: Int): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.autojs6_status_bar_icon)
            .setContentTitle(getString(R.string.text_adb_pairing_service_found_title))
            .setContentText(getString(R.string.text_adb_pair_notification_content))
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(getString(R.string.text_adb_pair_notification_content)),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(replyAction(host, port))
            .addAction(stopAction())
            .build()
    }

    private fun replyAction(host: String, port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.text_pair_code))
            .build()

        val replyIntent = Intent(this, WirelessAdbPairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            REQUEST_REPLY,
            replyIntent,
            mutablePendingIntentFlags(),
        )

        return Notification.Action.Builder(
            null,
            getString(R.string.text_adb_pair_notification_action_input),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun stopAction(): Notification.Action {
        val pendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_STOP),
            immutablePendingIntentFlags(),
        )
        return Notification.Action.Builder(
            null,
            getString(R.string.text_adb_pairing_stop),
            pendingIntent,
        ).build()
    }

    private fun appContentIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            MainActivity.getIntent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            immutablePendingIntentFlags(),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.text_adb_wireless_pair),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "startForegroundCompat: failed, fallback to plain notify", it)
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(false)
            }
        }
    }

    private fun mutablePendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun immutablePendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    companion object {

        private const val TAG = "EdgeJoin"

        const val NOTIFICATION_CHANNEL = "adb_pairing"
        private const val NOTIFICATION_ID = 0xE743

        private const val ACTION_START = "org.autojs.autojs.action.adb_pairing.START"
        private const val ACTION_STOP = "org.autojs.autojs.action.adb_pairing.STOP"
        private const val ACTION_REPLY = "org.autojs.autojs.action.adb_pairing.REPLY"

        private const val REMOTE_INPUT_KEY = "edgejoin_pair_code"
        private const val EXTRA_HOST = "pairing_host"
        private const val EXTRA_PORT = "pairing_port"

        private const val REQUEST_REPLY = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_CONTENT = 3

        private const val PREF_EDGEJOIN = "edgejoin"
        private const val KEY_ADB_PROXY_HOST = "adb_proxy_host"
        private const val KEY_ADB_PROXY_PORT = "adb_proxy_port"

        private const val PROVISION_RETRY_COUNT = 4
        private const val PROVISION_RETRY_DELAY_MS = 1_500L

        @JvmStatic
        fun startIntent(context: Context): Intent {
            return Intent(context, WirelessAdbPairingService::class.java).setAction(ACTION_START)
        }

        /**
         * Start the pairing service in foreground. Mirrors Shizuku's
         * `AdbPairingTutorialActivity.startPairingService`.
         */
        @JvmStatic
        fun start(context: Context) {
            val intent = startIntent(context)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.e(TAG, "start: unable to start pairing service", it)
                runCatching { context.startService(intent) }
            }
        }
    }
}
