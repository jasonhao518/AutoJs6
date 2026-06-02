package org.autojs.autojs.core.edgejoin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers the device's own Wireless Debugging (adb over Wi-Fi) TLS connect port
 * via mDNS / NSD.
 *
 * On modern Android devices (especially Samsung), the dynamic wireless-debug port
 * is NOT exposed through system properties such as `service.adb.tls.port` — reading
 * it with `getprop` returns empty even from a privileged shell. The only reliable
 * source is the `_adb-tls-connect._tcp` mDNS service that adbd advertises while
 * wireless debugging is enabled. This is the same mechanism Android Studio uses.
 */
object WirelessDebugPortResolver {

    private const val TAG = "WirelessDebugPort"
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp."
    private const val DEFAULT_TIMEOUT_MS = 5_000L

    /**
     * Blocking discovery of the local wireless-debug TLS connect port.
     *
     * Must NOT be called on the main thread.
     *
     * @return the discovered port in 1..65535, or 0 if discovery failed/timed out.
     */
    @JvmStatic
    @JvmOverloads
    fun resolvePort(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int {
        val nsdManager = context.applicationContext
            .getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Log.w(TAG, "resolvePort: NsdManager unavailable")
            return 0
        }

        val resolvedPort = AtomicInteger(0)
        val doneLatch = CountDownLatch(1)
        val resolveInFlight = AtomicBoolean(false)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "onResolveFailed: code=$errorCode service=${serviceInfo?.serviceName}")
                // Allow another found service to be resolved.
                resolveInFlight.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                val port = serviceInfo?.port ?: 0
                Log.d(TAG, "onServiceResolved: ${serviceInfo?.serviceName} port=$port")
                if (port in 1..65535) {
                    resolvedPort.set(port)
                    doneLatch.countDown()
                } else {
                    resolveInFlight.set(false)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "onStartDiscoveryFailed: code=$errorCode")
                doneLatch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed: code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "onDiscoveryStarted: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "onDiscoveryStopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                Log.d(TAG, "onServiceFound: ${serviceInfo.serviceName}")
                // Resolve only one service at a time; NsdManager rejects concurrent resolves.
                if (resolveInFlight.compareAndSet(false, true)) {
                    try {
                        nsdManager.resolveService(serviceInfo, resolveListener)
                    } catch (t: Throwable) {
                        Log.w(TAG, "resolveService failed", t)
                        resolveInFlight.set(false)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "onServiceLost: ${serviceInfo?.serviceName}")
            }
        }

        var discoveryStarted = false
        return try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discoveryStarted = true
            if (!doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "resolvePort: timed out after ${timeoutMs}ms")
            }
            resolvedPort.get()
        } catch (t: Throwable) {
            Log.w(TAG, "resolvePort: discovery error", t)
            0
        } finally {
            if (discoveryStarted) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (t: Throwable) {
                    Log.w(TAG, "stopServiceDiscovery failed", t)
                }
            }
        }
    }
}
