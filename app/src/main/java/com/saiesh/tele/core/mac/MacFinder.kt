package com.saiesh.tele.core.mac

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A computer that announces Windows/macOS file sharing (SMB) on the local network. */
data class MacService(
    val name: String,
    val host: String,
)

/**
 * Finds computers with File Sharing turned on through Bonjour (`_smb._tcp`). Android 11 resolves one
 * service at a time, so found services wait in a queue. Callbacks arrive on the main thread.
 */
class MacFinder(
    context: Context,
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var onFound: ((MacService) -> Unit)? = null

    fun start(onFound: (MacService) -> Unit) {
        stop()
        this.onFound = onFound
        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        pending.add(info)
                        resolveNext()
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit
            }
        listener = discovery
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        onFound = null
        pending.clear()
    }

    private fun resolveNext() {
        if (resolving) return
        val next = pending.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host?.hostAddress
                    main.post {
                        resolving = false
                        // IPv4 only: link-local IPv6 addresses need a scope the SMB client can't use.
                        if (address != null && !address.contains(':')) onFound?.invoke(MacService(info.serviceName, address))
                        resolveNext()
                    }
                }

                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }
            },
        )
    }

    companion object {
        private const val SERVICE_TYPE = "_smb._tcp."

        /** Looks for the Mac called [name] for up to [timeoutMs] and returns its address. Call off the main thread. */
        fun findHost(
            context: Context,
            name: String,
            timeoutMs: Long,
        ): String? {
            if (name.isBlank()) return null
            val finder = MacFinder(context)
            val latch = CountDownLatch(1)
            var host: String? = null
            Handler(Looper.getMainLooper()).post {
                finder.start { service ->
                    if (service.name == name && host == null) {
                        host = service.host
                        latch.countDown()
                    }
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            Handler(Looper.getMainLooper()).post { finder.stop() }
            return host
        }
    }
}
