package com.hpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class HpnVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.hpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.hpn.app.DISCONNECT"
        const val ACTION_ROTATE = "com.hpn.app.ROTATE"
        const val ACTION_STATE = "com.hpn.app.STATE"
        private const val CHANNEL = "hpn-vpn"
        private const val NOTIFICATION_ID = 701

        init { System.loadLibrary("hev-socks5-tunnel") }
    }

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean

    /** Keeps upstream SOCKS sockets outside the TUN route and prevents a VPN loop. */
    fun protectSocket(fd: Int): Boolean = protect(fd)

    private var tunFd: ParcelFileDescriptor? = null
    private var activeEndpoint: SocksEndpoint? = null
    private val rotationHandler = Handler(Looper.getMainLooper())
    private val rotationTask = object : Runnable {
        override fun run() {
            if (tunFd == null) return
            ProxyRepository.refresh(this@HpnVpnService) { snapshot ->
                if (snapshot.endpoints.any { it.protocol == "socks5" }) {
                    rotateTunnel()
                } else {
                    Log.w("HPN", "Rotation skipped: live SOCKS5 refresh returned no verified endpoint")
                }
                scheduleRotation()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_DISCONNECT -> stopTunnel()
                ACTION_ROTATE -> rotateTunnel()
                else -> startTunnel()
            }
        } catch (error: Throwable) {
            Log.e("HPN", "VPN service start failed", error)
            fail("Native VPN startup failed: ${error.javaClass.simpleName}")
        }
        return START_STICKY
    }

    private fun startTunnel(endpointOverride: SocksEndpoint? = null, fatalOnFailure: Boolean = true): Boolean {
        if (tunFd != null) { publishState("CONNECTED", activeEndpoint); return true }
        val prefs = getSharedPreferences("hpn", MODE_PRIVATE)
        val endpoint = endpointOverride ?: nextEndpoint(prefs)
            ?: run { if (fatalOnFailure) fail("No live SOCKS5 endpoint loaded — tap UPDATE LIVE PROXIES") else Log.e("HPN", "No live SOCKS5 endpoint available for rotation"); return false }
        val vpn = Builder()
            .setBlocking(false)
            .setSession("HPN Hyper Privet Network")
            .setMtu(1500)
            .addAddress("198.18.0.1", 15)
            .addRoute("0.0.0.0", 0)
            .addAddress("fc00::1", 7)
            .addRoute("::", 0)
            .addDnsServer("198.18.0.2")
            .addDisallowedApplication(packageName)
        val fd = vpn.establish() ?: run { if (fatalOnFailure) fail("Android refused the VPN interface") else Log.e("HPN", "Android refused the replacement VPN interface"); return false }
        val config = File(filesDir, "hpn-tunnel.yml")
        config.writeText(endpoint.toYaml())
        createChannel()
        val notice = notification("Routing device traffic through ${endpoint.host}:${endpoint.port}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
        if (!TProxyStartService(config.absolutePath, fd.fd)) {
            fd.close()
            if (fatalOnFailure) fail("Native TUN-to-SOCKS5 backend failed to start") else Log.e("HPN", "Native TUN-to-SOCKS5 replacement failed")
            return false
        }
        tunFd = fd
        activeEndpoint = endpoint
        prefs.edit().putBoolean("running", true).remove("last_error").apply()
        publishState("CONNECTED", endpoint)
        rotationHandler.postDelayed({
            if (tunFd != null && !TProxyIsRunning()) {
                tearDown()
                fail("SOCKS5 tunnel exited after startup; endpoint rejected")
            }
        }, 3000L)
        scheduleRotation()
        return true
    }

    private fun rotateTunnel() {
        if (tunFd == null) return
        val prefs = getSharedPreferences("hpn", MODE_PRIVATE)
        val old = activeEndpoint
        val next = nextEndpoint(prefs)
        if (next == null) {
            Log.w("HPN", "Rotation skipped: no verified SOCKS5 endpoint")
            return
        }
        if (old != null && next.host == old.host && next.port == old.port) {
            Log.w("HPN", "Rotation skipped: pool returned the active endpoint only")
            return
        }
        // Make-before-break preflight: never tear down the active tunnel for a
        // candidate that no longer completes SOCKS5 CONNECT + external IP echo.
        if (!ProxyRepository.verify(next.host, next.port)) {
            Log.w("HPN", "Rotation skipped: replacement endpoint failed the live SOCKS5 preflight")
            return
        }
        publishState("SWITCHING", old)
        tearDown()
        if (startTunnel(next, fatalOnFailure = false)) return
        if (old != null) {
            Log.w("HPN", "New endpoint failed; restoring previous verified endpoint")
            startTunnel(old, fatalOnFailure = false)
        }
    }

    private fun stopTunnel() {
        tearDown()
        activeEndpoint = null
        publishState("DISCONNECTED", null)
        rotationHandler.removeCallbacks(rotationTask)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun tearDown() {
        if (tunFd == null) return
        if (TProxyIsRunning()) TProxyStopService()
        tunFd?.close()
        tunFd = null
        getSharedPreferences("hpn", MODE_PRIVATE).edit().putBoolean("running", false).apply()
    }

    private fun scheduleRotation() {
        rotationHandler.removeCallbacks(rotationTask)
        val seconds = getSharedPreferences("hpn", MODE_PRIVATE).getInt("interval_seconds", 15).coerceIn(5, 3600)
        rotationHandler.postDelayed(rotationTask, seconds * 1000L)
    }

    private fun fail(message: String) {
        Log.e("HPN", message)
        getSharedPreferences("hpn", MODE_PRIVATE).edit().putString("last_error", message).putBoolean("running", false).apply()
        publishState("ERROR: $message", activeEndpoint)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(state: String, endpoint: SocksEndpoint?) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra("state", state)
            .putExtra("host", endpoint?.host.orEmpty())
            .putExtra("port", endpoint?.port ?: 0))
    }

    private fun nextEndpoint(prefs: android.content.SharedPreferences): SocksEndpoint? {
        val rows = ProxyRepository.current().endpoints.filter { it.protocol == "socks5" }
        if (rows.isEmpty()) return null
        val index = prefs.getInt("proxy_index", -1)
        var next = (index + 1) % rows.size
        if (activeEndpoint != null && rows.size > 1) {
            var attempts = 0
            while (attempts < rows.size && rows[next].host == activeEndpoint?.host && rows[next].port == activeEndpoint?.port) {
                next = (next + 1) % rows.size
                attempts++
            }
        }
        prefs.edit().putInt("proxy_index", next).apply()
        val selected = rows[next]
        return SocksEndpoint(selected.host, selected.port)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "HPN VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("HPN — Hyper Privet Network")
        .setContentText(text)
        .setSmallIcon(R.drawable.hpn_icon)
        .setOngoing(true)
        .build()

    override fun onDestroy() { stopTunnel(); super.onDestroy() }
    override fun onRevoke() { stopTunnel(); super.onRevoke() }

    data class SocksEndpoint(val host: String, val port: Int, val username: String = "", val password: String = "") {
        fun toYaml(): String = """
            tunnel:
              name: tun0
              mtu: 1500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              icmp: 'reply'
            socks5:
              address: '${yaml(host)}'
              port: $port
              udp: 'tcp'
              username: '${yaml(username)}'
              password: '${yaml(password)}'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 240.0.0.0
              netmask: 240.0.0.0
              cache-size: 10000
        """.trimIndent() + "\n"
        companion object {
            fun parse(raw: String): SocksEndpoint? {
                return try {
                    val value = if (raw.contains("://")) raw else "socks5://$raw"
                    val uri = java.net.URI(value)
                    val host = uri.host ?: return null
                    val port = uri.port.takeIf { it in 1..65535 } ?: return null
                    SocksEndpoint(host, port, uri.userInfo?.substringBefore(':').orEmpty(), uri.userInfo?.substringAfter(':', "").orEmpty())
                } catch (_: Exception) { null }
            }
        }
        private fun yaml(value: String) = value.replace("'", "''")
    }
}
