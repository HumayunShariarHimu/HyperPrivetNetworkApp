package com.hpn.app

import android.content.Context
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/** Fetches and validates public SOCKS candidates in memory; nothing is persisted. */
object ProxyRepository {
    private val SOURCES = listOf(
        Source("Proxifly SOCKS5", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt", "socks5"),
        Source("Proxifly SOCKS4", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks4/data.txt", "socks4"),
        Source("ProxyScrape SOCKS5", "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/protocols/socks5/data.txt", "socks5"),
        Source("SOCKSList.us", "https://sockslist.us/", "socks5"),
        Source("SPYS.ONE SOCKS", "https://spys.one/en/socks-proxy-list/", "socks5"),
        Source("Scrappey SOCKS5", "https://scrappey.com/tools/free-proxy-lists/socks5", "socks5"),
        Source("FreeProxy.World SOCKS5", "https://www.freeproxy.world/?type=socks5", "socks5"),
        Source("GitHub SOCKS5 topic", "https://github.com/topics/socks5-proxy?o=desc&s=updated", "socks5")
    )

    data class Endpoint(val host: String, val port: Int, val protocol: String, val source: String) {
        fun key() = "$protocol://$host:$port"
    }
    data class Snapshot(val endpoints: List<Endpoint>, val sourcesOk: Int, val sourcesTotal: Int, val fetchedAt: Long, val error: String? = null)
    private data class Source(val label: String, val url: String, val protocol: String)

    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val sourceExecutor = Executors.newFixedThreadPool(8)
    private val probeExecutor = Executors.newFixedThreadPool(16)
    private val refreshing = AtomicBoolean(false)
    private val waiting = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    @Volatile private var snapshot = Snapshot(emptyList(), 0, SOURCES.size, 0)

    fun current(): Snapshot = snapshot
    fun sourceLabels(): List<String> = SOURCES.map { it.label }

    /** Re-check the full SOCKS5 greeting, CONNECT and IP-echo path immediately before handover. */
    fun verify(endpoint: Endpoint): Boolean = endpoint.protocol == "socks5" && probeSocks5(endpoint)

    /** Preflight the endpoint selected by the VPN service immediately before handover. */
    fun verify(host: String, port: Int): Boolean =
        port in 1..65535 && probeSocks5(Endpoint(host, port, "socks5", "rotation preflight"))

    fun refresh(context: Context, callback: (Snapshot) -> Unit) {
        waiting.add(callback)
        if (!refreshing.compareAndSet(false, true)) return
        refreshExecutor.execute {
            try {
                val jobs = SOURCES.map { source -> sourceExecutor.submit<List<Endpoint>> { runCatching { fetch(source) }.getOrDefault(emptyList()) } }
                val results = jobs.map { it.get() }
                val raw = results.flatten().distinctBy { it.key() }.shuffled()
                // Public lists are frequently stale. Keep the scan bounded so the UI never
                // appears to hang while hundreds of dead endpoints are probed.
                val candidates = raw.filter { it.protocol == "socks5" }.take(96)
                val verified = candidates.map { candidate -> probeExecutor.submit<Endpoint?> { if (probeSocks5(candidate)) candidate else null } }
                    .mapNotNull { runCatching { it.get(4, TimeUnit.SECONDS) }.getOrNull() }
                    .distinctBy { it.key() }
                    .take(40)
                val ok = results.count { it.isNotEmpty() }
                snapshot = Snapshot(verified, ok, SOURCES.size, System.currentTimeMillis(), if (verified.isEmpty()) "No SOCKS5 handshake succeeded" else null)
            } catch (error: Throwable) {
                snapshot = Snapshot(emptyList(), 0, SOURCES.size, System.currentTimeMillis(), error.javaClass.simpleName)
            } finally {
                refreshing.set(false)
                val callbacks = waiting.toList(); waiting.clear()
                android.os.Handler(android.os.Looper.getMainLooper()).post { callbacks.forEach { it(snapshot) } }
            }
        }
    }

    private fun fetch(source: Source): List<Endpoint> {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500; readTimeout = 4500; requestMethod = "GET"
            setRequestProperty("User-Agent", "HPN-Android-Live-Source/1.0")
            setRequestProperty("Accept", "text/plain,text/html,application/json,*/*")
        }
        return try {
            if (connection.responseCode !in 200..399) return emptyList()
            parse(source, connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun probeSocks5(endpoint: Endpoint): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 1200)
                socket.soTimeout = 1800
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                // Greeting + NO AUTH.
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                if (input.read() != 0x05 || input.read() != 0x00) return false

                // CONNECT a small, stable HTTP endpoint through the SOCKS5 relay.
                // A successful CONNECT is the important requirement for the native tunnel;
                // requiring a specific IP-echo body rejected otherwise usable proxies.
                val host = "example.com".toByteArray(Charsets.US_ASCII)
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
                output.write(host)
                output.write(byteArrayOf(0x00, 0x50))
                output.flush()

                val version = input.read()
                val reply = input.read()
                if (version != 0x05 || reply != 0x00) return false

                // Consume the SOCKS5 bound-address response.
                when (input.read()) {
                    0x01 -> repeat(4) { if (input.read() < 0) return false }
                    0x03 -> {
                        val len = input.read()
                        if (len < 0) return false
                        repeat(len) { if (input.read() < 0) return false }
                    }
                    0x04 -> repeat(16) { if (input.read() < 0) return false }
                    else -> return false
                }
                if (input.read() < 0 || input.read() < 0) return false

                val request = "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()
                val response = ByteArray(64)
                val count = input.read(response)
                count > 0 && String(response, 0, count, Charsets.US_ASCII).contains("HTTP/")
            }
        } catch (_: Exception) { false }
    }

    private fun parse(source: Source, body: String): List<Endpoint> {
        val pattern = Pattern.compile("(?<![\\d.])((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{2,5})(?!\\d)")
        val result = LinkedHashMap<String, Endpoint>()
        val matcher = pattern.matcher(body)
        while (matcher.find() && result.size < 2500) {
            val host = matcher.group(1) ?: continue
            val port = (matcher.group(2) ?: continue).toIntOrNull() ?: continue
            if (host.split('.').all { it.toIntOrNull() in 0..255 } && port in 1..65535) {
                val endpoint = Endpoint(host, port, source.protocol, source.label)
                result.putIfAbsent(endpoint.key(), endpoint)
            }
        }
        return result.values.toList()
    }
}
