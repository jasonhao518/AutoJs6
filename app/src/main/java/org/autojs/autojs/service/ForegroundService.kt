package org.autojs.autojs.service

import android.content.Context
import android.content.Intent
import android.os.Build
import org.autojs.autojs.permission.IgnoreBatteryOptimizationsPermission
import org.autojs.autojs.external.foreground.AppForegroundService
import org.autojs.autojs.ui.main.drawer.ServiceItemHelper
import org.autojs.autojs.util.ForegroundServiceUtils

class ForegroundService(override val context: Context) : ServiceItemHelper {

    private val mClassName = AppForegroundService::class.java

    override val isRunning
        get() = ForegroundServiceUtils.isRunning(context, mClassName)

    override fun start(): Boolean {
        requestIgnoreBatteryOptimizationsIfNeeded()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                context.startForegroundService(Intent(context, mClassName)) != null
            }
            else -> context.startService(Intent(context, mClassName)) != null
        }
    }

    override fun stop() = context.stopService(Intent(context, mClassName))

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        kotlin.runCatching {
            val permission = IgnoreBatteryOptimizationsPermission(context)
            if (!permission.has()) {
                permission.request()
            }
        }
    }

}