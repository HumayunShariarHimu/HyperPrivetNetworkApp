package com.hpn.app

import android.net.VpnService
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * Exposes a loopback SOCKS5 endpoint to the native TUN engine. Each CONNECT is
 * carried over an authenticated WebSocket to the Render relay, so the Android
 * app no longer depends on scraped public proxy lists.
 */
class LocalSocksBridge(private val vpn: VpnService) {
    private var server: ServerSocket? = null
    private var executor = java.util.concurrent.Executors.newCachedThreadPool()

    fun start(): Int {
        if (server?.isClosed == false) return server!!.localPort
        if (executor.isShutdown) executor = java.util.concurrent.Executors.newCachedThreadPool()
        val opened = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = opened
        executor.execute {
            while (!opened.isClosed) {
                runCatching { opened.accept() }.onSuccess { socket -> executor.execute { handle(socket) } }
            }
        }
        return opened.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        executor.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 20000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            if (input.read() != 5) return
            val methodCount = input.read()
            if (methodCount !in 1..255) return
            val methods = input.readExactly(methodCount) ?: return
            if (!methods.contains(0.toByte())) {
                output.write(byteArrayOf(5.toByte(), 255.toByte())); output.flush(); return
            }
            output.write(byteArrayOf(5, 0)); output.flush()

            val request = input.readExactly(4) ?: return
            if (request[0].toInt() != 5 || request[1].toInt() != 1) return
            val host = when (request[3].toInt() and 0xff) {
                1 -> input.readExactly(4)?.joinToString(".") { (it.toInt() and 0xff).toString() }
                3 -> {
                    val length = input.read()
                    if (length !in 1..255) return
                    input.readExactly(length)?.toString(Charsets.UTF_8)
                }
                4 -> input.readExactly(16)?.let { InetAddress.getByAddress(it).hostAddress }
                else -> null
            } ?: return
            val portBytes = input.readExactly(2) ?: return
            val targetPort = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
            if (targetPort !in 1..65535) return

            val connected = CountDownLatch(1)
            val relayClosed = CountDownLatch(1)
            val connectedOk = AtomicBoolean(false)
            val client = OkHttpClient.Builder()
                .socketFactory(ProtectedSocketFactory(vpn))
                .retryOnConnectionFailure(false)
                .build()
            val relay = client.newWebSocket(
                Request.Builder().url(RELAY_URL).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val hello = JSONObject().put("token", RELAY_TOKEN).put("host", host).put("port", targetPort)
                        webSocket.send(hello.toString())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"connected\"")) {
                            connectedOk.set(true)
                            connected.countDown()
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        runCatching { synchronized(output) { output.write(bytes.toByteArray()); output.flush() } }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected.countDown(); relayClosed.countDown()
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        relayClosed.countDown()
                    }
                }
            )
            if (!connected.await(15, TimeUnit.SECONDS) || !connectedOk.get()) {
                relay.cancel(); return
            }
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!relay.send(ByteString.of(*buffer.copyOf(count)))) break
            }
            relay.close(1000, "client closed")
            relayClosed.await(2, TimeUnit.SECONDS)
        }
    }

    private class ProtectedSocketFactory(private val vpn: VpnService) : SocketFactory() {
        private fun fresh(): Socket = Socket().also { socket ->
            if (!vpn.protect(socket)) { socket.close(); throw IOException("VpnService.protect failed") }
        }
        override fun createSocket(): Socket = fresh()
        override fun createSocket(host: String, port: Int): Socket = fresh().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = fresh().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = fresh().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = fresh().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
    }

    private fun java.io.InputStream.readExactly(count: Int): ByteArray? {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(out, offset, count - offset)
            if (read < 0) return null
            offset += read
        }
        return out
    }

    companion object {
        private const val RELAY_URL = "wss://hpn-relay.onrender.com/tunnel"
        private const val RELAY_TOKEN = "hpn-2026-relay"
    }
}
