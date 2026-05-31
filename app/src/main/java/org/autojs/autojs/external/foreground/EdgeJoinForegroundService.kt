package org.autojs.autojs.external.foreground

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.IBinder
import android.util.Log
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
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startEdgeJoinClientAsync()
            }
        }
        return START_STICKY
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

        super.onDestroy()
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
            val intent = Intent(appContext, EdgeJoinForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, EdgeJoinForegroundService::class.java))
        }
    }
}
