package org.autojs.autojs.external.foreground

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.SystemClock
import android.os.IBinder
import android.util.Log
import org.autojs.autojs.permission.IgnoreBatteryOptimizationsPermission
import org.autojs.autojs.external.receiver.EdgeJoinRestartReceiver
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import org.autojs.autojs.tool.ForegroundServiceCreator
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autojs.util.ForegroundServiceUtils.FOREGROUND_SERVICE_TYPE_UNKNOWN
import org.autojs.autojs6.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.autojs.autojs.inrt.LogActivity as LogActivityInrt
import org.autojs.autojs.AbstractAutoJs.Companion.isInrt

class EdgeJoinForegroundService : Service() {

    private lateinit var foregroundServiceCreator: ForegroundServiceCreator
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var stopRequested = false

    private val foregroundServiceType = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        else -> FOREGROUND_SERVICE_TYPE_UNKNOWN
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val label = packageManager.getApplicationLabel(applicationInfo).toString()
        val intentClass = if (!isInrt) MainActivity::class.java else LogActivityInrt::class.java

        foregroundServiceCreator = ForegroundServiceCreator.Builder(this)
            .setClassName(EdgeJoinForegroundService::class.java)
            .setIntent(Intent(this, intentClass))
            .setNotificationId(NOTIFICATION_ID)
            .setServiceName(getString(R.string.foreground_notification_channel_name, label))
            .setServiceDescription(getString(R.string.foreground_notification_channel_name, label))
            .setNotificationTitle(getString(R.string.foreground_notification_title, label))
            .setNotificationContent(getString(R.string.foreground_notification_text, label))
            .create()
            .apply { startForeground(foregroundServiceType) }

        startEdgeJoinClientAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                stopRequested = false
                startEdgeJoinClientAsync()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestartIfConfigured()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            foregroundServiceCreator.stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop foreground state", t)
        }

        ioExecutor.execute {
            try {
                EdgeJoinBridge.stopClient()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to stop edgejoin client", t)
            }
        }
        ioExecutor.shutdown()

        if (stopRequested || sSuppressNextRestart) {
            sSuppressNextRestart = false
            cancelRestart(applicationContext)
        } else {
            scheduleRestartIfConfigured()
        }

        super.onDestroy()
    }

    private fun scheduleRestartIfConfigured() {
        try {
            val configJson = EdgeJoinBridge.loadStoredConfig()
            if (configJson.isBlank()) {
                cancelRestart(applicationContext)
                return
            }
            scheduleRestart(applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule edgejoin restart", t)
        }
    }

    private fun startEdgeJoinClientAsync() {
        ioExecutor.execute {
            try {
                val configJson = EdgeJoinBridge.loadStoredConfig()
                if (configJson.isBlank()) {
                    Log.i(TAG, "No stored edgejoin config, stopping service")
                    stopSelf()
                    return@execute
                }
                val result = EdgeJoinBridge.startClientFromStoredConfig()
                Log.d(TAG, "edgejoin start result=$result")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start edgejoin client from foreground service", t)
            }
        }
    }

    companion object {
        private const val TAG = "EdgeJoinFgs"
        private const val NOTIFICATION_ID = 0xE71
        private const val ACTION_START = "org.autojs.autojs.action.edgejoin.START"
        private const val ACTION_STOP = "org.autojs.autojs.action.edgejoin.STOP"
        private const val RESTART_DELAY_MS = 8_000L
        private const val RESTART_REQUEST_CODE = 0xE72

        @Volatile
        private var sSuppressNextRestart: Boolean = false

        fun startIfConfigured(context: Context): Boolean {
            return try {
                val configJson = EdgeJoinBridge.loadStoredConfig()
                if (configJson.isBlank()) {
                    false
                } else {
                    start(context)
                    true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "startIfConfigured failed", t)
                false
            }
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            sSuppressNextRestart = false
            cancelRestart(appContext)
            requestIgnoreBatteryOptimizationsIfNeeded(appContext)
            val intent = Intent(appContext, EdgeJoinForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        private fun requestIgnoreBatteryOptimizationsIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return
            }
            try {
                val permission = IgnoreBatteryOptimizationsPermission(context)
                if (!permission.has()) {
                    permission.request()
                    Log.i(TAG, "Requested ignore battery optimizations for EdgeJoin foreground service")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to request ignore battery optimizations", t)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            sSuppressNextRestart = true
            cancelRestart(appContext)
            appContext.stopService(Intent(appContext, EdgeJoinForegroundService::class.java))
        }

        private fun scheduleRestart(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = restartPendingIntent(context)
            val triggerAtMillis = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
            am.cancel(pi)
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pi)
            Log.i(TAG, "Scheduled edgejoin restart in ${RESTART_DELAY_MS}ms")
        }

        private fun cancelRestart(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = restartPendingIntent(context)
            am.cancel(pi)
            pi.cancel()
        }

        private fun restartPendingIntent(context: Context): PendingIntent {
            val intent = Intent().apply {
                component = ComponentName(context, EdgeJoinRestartReceiver::class.java)
                action = EdgeJoinRestartReceiver.ACTION_EDGEJOIN_RESTART
            }
            return PendingIntent.getBroadcast(
                context,
                RESTART_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
