package org.autojs.autojs.core.edgejoin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    private const val TAG = "EdgeJoin"
    private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
    private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
    private const val DEFAULT_TIMEOUT_MS = 5_000L

    data class Endpoint(
        val host: String,
        val port: Int,
    )

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
        return resolveEndpoint(context, CONNECT_SERVICE_TYPE, timeoutMs)?.port ?: 0
    }

    /**
     * Blocking discovery of the local wireless-debug pairing endpoint via
     * `_adb-tls-pairing._tcp` mDNS service.
     *
     * Must NOT be called on the main thread.
     *
     * @return endpoint host+port, or null if discovery failed/timed out.
     */
    @JvmStatic
    @JvmOverloads
    fun resolvePairingEndpoint(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Endpoint? {
        return resolveEndpoint(context, PAIRING_SERVICE_TYPE, timeoutMs)
    }

    private fun resolveEndpoint(context: Context, serviceType: String, timeoutMs: Long): Endpoint? {
        val nsdManager = context.applicationContext
            .getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Log.w(TAG, "resolveEndpoint: NsdManager unavailable serviceType=$serviceType")
            return null
        }

        val resolvedPort = AtomicInteger(0)
        val resolvedHost = AtomicReference<String>("")
        val doneLatch = CountDownLatch(1)
        val resolveInFlight = AtomicBoolean(false)

        fun normalizeHost(host: InetAddress?): String {
            val raw = host?.hostAddress?.trim().orEmpty()
            if (raw.isEmpty()) return ""
            // Drop IPv6 zone suffix (e.g. fe80::1%wlan0)
            val zoneIdx = raw.indexOf('%')
            return if (zoneIdx > 0) raw.substring(0, zoneIdx) else raw
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "onResolveFailed: code=$errorCode service=${serviceInfo?.serviceName}")
                // Allow another found service to be resolved.
                resolveInFlight.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                val port = serviceInfo?.port ?: 0
                val host = normalizeHost(serviceInfo?.host)
                Log.d(TAG, "onServiceResolved: ${serviceInfo?.serviceName} host=$host port=$port type=$serviceType")
                if (port in 1..65535) {
                    resolvedPort.set(port)
                    resolvedHost.set(host)
                    doneLatch.countDown()
                } else {
                    resolveInFlight.set(false)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "onStartDiscoveryFailed: code=$errorCode serviceType=$serviceType")
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
                Log.d(TAG, "onServiceFound: ${serviceInfo.serviceName} type=$serviceType")
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
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discoveryStarted = true
            if (!doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "resolveEndpoint: timed out after ${timeoutMs}ms serviceType=$serviceType")
            }
            val port = resolvedPort.get()
            if (port !in 1..65535) {
                null
            } else {
                Endpoint(
                    host = resolvedHost.get().takeIf { it.isNotBlank() } ?: "127.0.0.1",
                    port = port,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolveEndpoint: discovery error serviceType=$serviceType", t)
            null
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
