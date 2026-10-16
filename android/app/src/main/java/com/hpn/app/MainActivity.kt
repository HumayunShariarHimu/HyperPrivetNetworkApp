package com.hpn.app

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val green = Color.rgb(0, 255, 128)
    private val dimGreen = Color.rgb(0, 170, 85)
    private val black = Color.rgb(2, 5, 4)
    private lateinit var status: TextView
    private lateinit var pool: TextView
    private lateinit var sourceState: TextView
    private lateinit var intervalLabel: TextView
    private var intervalSeconds = 15
    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != HpnVpnService.ACTION_STATE) return
            val state = intent.getStringExtra("state") ?: "UNKNOWN"
            val host = intent.getStringExtra("host").orEmpty()
            val port = intent.getIntExtra("port", 0)
            status.text = when {
                state == "CONNECTED" -> "[ CONNECTED ]  SOCKS5 $host:$port"
                state == "SWITCHING" -> "[ SWITCHING ]  VERIFYING NEW EXIT..."
                state.startsWith("ERROR:") -> "[ ERROR ]  ${state.removePrefix("ERROR: ")}"
                else -> "[ $state ]  VPN SERVICE STATE"
            }
        }
    }

    private fun label(value: String, size: Float, color: Int = green) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE; setPadding(18, 10, 18, 10)
    }

    private fun action(value: String, listener: () -> Unit) = Button(this).apply {
        text = value; setTextColor(green); setTextSize(13f); typeface = Typeface.MONOSPACE
        setBackgroundColor(Color.rgb(8, 24, 16)); setOnClickListener { listener() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black; window.navigationBarColor = black
        intervalSeconds = getSharedPreferences("hpn", MODE_PRIVATE).getInt("interval_seconds", 15).coerceIn(5, 3600)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 24, 18, 18); setBackgroundColor(black) }
        content.addView(label("╔══ HPN // HYPER PRIVET NETWORK", 17f))
        content.addView(label("║ LIVE SOCKS5 ROUTER :: NATIVE TUNNEL", 11f, dimGreen))
        content.addView(label("╚══ PUBLIC SOURCE FEED / NO MANUAL PROXY", 11f, dimGreen))
        status = label("[ IDLE ]  SOURCE FEED NOT LOADED", 13f, Color.rgb(255, 190, 60)).apply { setPadding(18, 24, 18, 24) }
        content.addView(status)
        pool = label("POOL // 0 SOCKS5 CANDIDATES", 13f)
        content.addView(pool)
        sourceState = label("SOURCES // waiting for live update", 11f, dimGreen)
        content.addView(sourceState)
        content.addView(action("[ UPDATE LIVE PROXIES ]") { refreshSources() })
        intervalLabel = label("ROTATION // $intervalSeconds SEC", 13f)
        content.addView(intervalLabel)
        content.addView(SeekBar(this).apply {
            max = 3595; progress = intervalSeconds - 5; setPadding(0, 4, 0, 8)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    intervalSeconds = progress + 5; intervalLabel.text = "ROTATION // $intervalSeconds SEC"
                    getSharedPreferences("hpn", MODE_PRIVATE).edit().putInt("interval_seconds", intervalSeconds).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        content.addView(action("[ START FULL-DEVICE VPN ]") { startVpn() })
        content.addView(action("[ ROTATE NOW / REFRESH FEED ]") { rotateNow() })
        content.addView(action("[ STOP VPN ]") { sendService(HpnVpnService.ACTION_DISCONNECT) })
        content.addView(label("\nLIVE SOURCES", 12f))
        content.addView(label(ProxyRepository.sourceLabels().joinToString("\n") { "  + $it" }, 10f, dimGreen))
        content.addView(label("\nWARNING // PUBLIC PROXIES ARE UNTRUSTED\nONLY USE TRAFFIC YOU ARE AUTHORIZED TO ROUTE", 10f, Color.rgb(190, 70, 70)))
        content.addView(label("\nHPN - Humayun Shariar Himu", 10f, Color.rgb(70, 120, 90)).apply { gravity = Gravity.CENTER })
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(HpnVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (ProxyRepository.current().endpoints.isEmpty()) refreshSources()
    }

    private fun refreshSources(startAfter: Boolean = false) {
        status.text = "[ SCANNING ]  FETCHING FRESH SOCKS SOURCES..."
        ProxyRepository.refresh(this) { snapshot ->
            pool.text = "POOL // ${snapshot.endpoints.size} VERIFIED SOCKS5"
            sourceState.text = "SOURCES // ${snapshot.sourcesOk}/${snapshot.sourcesTotal} ONLINE // ${snapshot.endpoints.size} HANDSHAKE-VERIFIED // UPDATED ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(snapshot.fetchedAt))}"
            if (snapshot.endpoints.isNotEmpty()) {
                status.text = if (startAfter) "[ READY ]  STARTING VERIFIED VPN..." else "[ READY ]  VERIFIED SOCKS5 POOL LOADED"
                if (startAfter) requestVpnPermissionAndStart()
            } else status.text = "[ BLOCKED ]  NO SOCKS5 HANDSHAKE SUCCEEDED"
        }
    }

    private fun startVpn() {
        if (!ProxyRepository.current().endpoints.any { it.protocol == "socks5" }) { refreshSources(startAfter = true); return }
        requestVpnPermissionAndStart()
    }

    private fun requestVpnPermissionAndStart() {
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, 100) else sendService(HpnVpnService.ACTION_CONNECT)
        } catch (error: Throwable) {
            status.text = "[ ERROR ]  VPN START FAILED: ${error.javaClass.simpleName}"
        }
    }

    private fun rotateNow() {
        status.text = "[ SCANNING ]  REFRESHING BEFORE ROTATION..."
        ProxyRepository.refresh(this) { snapshot ->
            pool.text = "POOL // ${snapshot.endpoints.size} VERIFIED SOCKS5"
            sourceState.text = "SOURCES // ${snapshot.sourcesOk}/${snapshot.sourcesTotal} ONLINE // ${snapshot.endpoints.size} HANDSHAKE-VERIFIED"
            if (snapshot.endpoints.isNotEmpty()) sendService(HpnVpnService.ACTION_ROTATE)
            else status.text = "[ BLOCKED ]  NO SOCKS5 HANDSHAKE SUCCEEDED"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) sendService(HpnVpnService.ACTION_CONNECT)
        else if (requestCode == 100) status.text = "[ CANCELLED ]  VPN PERMISSION WAS NOT GRANTED"
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    private fun sendService(action: String) {
        try {
            ContextCompat.startForegroundService(this, Intent(this, HpnVpnService::class.java).setAction(action))
            status.text = if (action == HpnVpnService.ACTION_DISCONNECT) "[ STOP REQUESTED ]  CLOSING VPN..." else "[ START REQUESTED ]  WAITING FOR VPN SERVICE..."
        } catch (error: Throwable) {
            status.text = "[ ERROR ]  SERVICE START FAILED: ${error.javaClass.simpleName}"
        }
    }
}
