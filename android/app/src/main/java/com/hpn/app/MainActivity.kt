package com.hpn.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(3, 5, 7)
    private val panel = Color.rgb(12, 16, 20)
    private val panelBorder = Color.rgb(31, 43, 48)
    private val green = Color.rgb(0, 255, 138)
    private val dimGreen = Color.rgb(91, 174, 137)
    private val amber = Color.rgb(255, 190, 70)
    private val red = Color.rgb(255, 92, 92)
    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var hpnButton: TextView
    private lateinit var hpnCaption: TextView
    private lateinit var poolValue: TextView
    private lateinit var sourceValue: TextView
    private lateinit var rotationValue: TextView
    private lateinit var exitValue: TextView
    private lateinit var footer: TextView
    private var intervalSeconds = 15
    private var connected = false
    private var currentState = "IDLE"

    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != HpnVpnService.ACTION_STATE) return
            val state = intent.getStringExtra("state") ?: "UNKNOWN"
            val host = intent.getStringExtra("host").orEmpty()
            val port = intent.getIntExtra("port", 0)
            connected = state == "CONNECTED" || state == "SWITCHING"
            currentState = state
            renderState(state, host, port)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        intervalSeconds = prefs().getInt("interval_seconds", 15).coerceIn(5, 3600)
        buildDashboard()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(HpnVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        updatePool(ProxyRepository.current())
        if (ProxyRepository.current().endpoints.isEmpty()) refreshSources()
    }

    private fun buildDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(14), dp(20), dp(12))
        }
        root.addView(header(), LinearLayout.LayoutParams(-1, dp(58)))

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scrollContent.addView(label("SECURE ROUTING CONTROL", 11f, dimGreen), lp(-1, 32))
        scrollContent.addView(buildStatusPanel(), lp(-1, 62))
        scrollContent.addView(hpnControl(), lp(-1, 250))
        scrollContent.addView(statsPanel(), lp(-1, 142))
        scrollContent.addView(actionRow(), lp(-1, 62))
        scrollContent.addView(label("\nLIVE FEED", 11f, dimGreen), lp(-1, 30))
        scrollContent.addView(footerInfo(), lp(-1, 100))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(scrollContent)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("HPN  •  NATIVE SOCKS5 TUNNEL  •  v0.2.0", 9f, Color.rgb(70, 95, 87)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(28)))
        setContentView(root)
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("HPN", 25f, green), lp(-1, 30))
        title.addView(label("HYPER PRIVET NETWORK", 9f, dimGreen), lp(-1, 20))
        row.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(dp(10), 0, 0, 0)
            contentDescription = "Open HPN settings"
            setOnClickListener { showSettings() }
        }
        row.addView(settings, lp(54, -1))
        return row
    }

    private fun buildStatusPanel(): View {
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(panel, panelBorder, 1, 12)
        }
        statusDot = label("●", 15f, amber)
        box.addView(statusDot, lp(28, -1))
        statusText = label("READY • CONNECT TO START", 12f, amber)
        box.addView(statusText, lp(0, -1, 1f))
        val live = label("LIVE", 9f, dimGreen)
        live.gravity = Gravity.CENTER
        box.addView(live, lp(42, -1))
        return box
    }

    private fun hpnControl(): View {
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        hpnButton = TextView(this).apply {
            text = "HPN\nOFF"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 20f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(green)
            background = rounded(bg, green, 2, 200)
            elevation = dp(8).toFloat()
            contentDescription = "Turn HPN VPN on or off"
            setOnClickListener { toggleVpn() }
        }
        area.addView(hpnButton, lp(176, 176))
        hpnCaption = label("TAP TO START FULL-DEVICE VPN", 10f, dimGreen).apply { gravity = Gravity.CENTER }
        area.addView(hpnCaption, lp(-1, 38))
        return area
    }

    private fun statsPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(panel, panelBorder, 1, 14)
        }
        box.addView(label("CONNECTION STATS", 10f, dimGreen), lp(-1, 24))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        poolValue = statCell(row, "POOL", "0")
        sourceValue = statCell(row, "SOURCES", "—")
        rotationValue = statCell(row, "ROTATION", formatInterval(intervalSeconds))
        exitValue = statCell(row, "EXIT", "OFF")
        box.addView(row, lp(-1, 90))
        return box
    }

    private fun statCell(row: LinearLayout, name: String, value: String): TextView {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        cell.addView(label(name, 9f, dimGreen).apply { gravity = Gravity.CENTER }, lp(-1, 26))
        val valueView = label(value, 15f, green).apply { gravity = Gravity.CENTER }
        cell.addView(valueView, lp(-1, 36))
        row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        return valueView
    }

    private fun actionRow(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(smallButton("UPDATE LIVE PROXIES") { refreshSources() }, lp(0, 48, 1f))
        row.addView(smallButton("↻  ROTATE") { rotateNow() }, lp(0, 48, 1f))
        row.addView(smallButton("▣  STATS") { showStats() }, lp(0, 48, 1f))
        return row
    }

    private fun footerInfo(): View {
        footer = label("SOURCE FEED // waiting for live update\nPROXY MODE // verified SOCKS5 only\nNETWORK // Internet connection required", 10f, dimGreen)
        footer.setPadding(dp(14), dp(12), dp(14), dp(12))
        footer.background = rounded(panel, panelBorder, 1, 14)
        return footer
    }

    private fun smallButton(text: String, listener: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(green)
        isAllCaps = false
        background = rounded(panel, panelBorder, 1, 12)
        setOnClickListener { listener() }
        stateListAnimator = null
    }

    private fun label(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rounded(fill: Int, stroke: Int, width: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(width, stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(if (width == 0) 0 else if (width == -1) -1 else dp(width), if (height == -1) -1 else dp(height), weight).apply { setMargins(0, dp(4), 0, dp(4)) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("hpn", MODE_PRIVATE)
    private fun formatInterval(seconds: Int): String = if (seconds >= 60) "${seconds / 60}M" else "${seconds}S"

    private fun renderState(state: String, host: String, port: Int) {
        val isError = state.startsWith("ERROR:")
        val color = when { isError -> red; connected -> green; state == "DISCONNECTED" -> amber; else -> amber }
        statusDot.setTextColor(color)
        statusText.setTextColor(color)
        statusText.text = when {
            state == "CONNECTED" -> "CONNECTED • SOCKS5 $host:$port"
            state == "SWITCHING" -> "SWITCHING • VERIFYING NEW EXIT"
            isError -> "ERROR • ${state.removePrefix("ERROR: ")}"
            state == "DISCONNECTED" -> "OFFLINE • VPN DISCONNECTED"
            else -> "${state.uppercase(Locale.US)} • READY"
        }
        hpnButton.text = if (connected) "HPN\nON" else "HPN\nOFF"
        hpnButton.setTextColor(if (connected) green else amber)
        hpnButton.background = rounded(bg, if (connected) green else amber, 3, 200)
        hpnCaption.text = if (connected) "TAP TO STOP FULL-DEVICE VPN" else "TAP TO START FULL-DEVICE VPN"
        exitValue.text = if (connected && host.isNotBlank()) "$host:$port" else "OFF"
    }

    private fun updatePool(snapshot: ProxyRepository.Snapshot) {
        poolValue.text = snapshot.endpoints.size.toString()
        sourceValue.text = "${snapshot.sourcesOk}/${snapshot.sourcesTotal}"
        rotationValue.text = formatInterval(intervalSeconds)
        footer.text = "SOURCE FEED // ${snapshot.sourcesOk}/${snapshot.sourcesTotal} ONLINE\nPROXY MODE // ${snapshot.endpoints.size} VERIFIED SOCKS5\nROTATION // ${formatInterval(intervalSeconds)}\nNETWORK // Internet connection required"
    }

    private fun refreshSources(startAfter: Boolean = false) {
        statusText.text = "SCANNING • FETCHING LIVE PROXIES..."
        statusText.setTextColor(amber)
        ProxyRepository.refresh(this) { snapshot ->
            updatePool(snapshot)
            if (snapshot.endpoints.isNotEmpty()) {
                statusText.text = if (startAfter) "READY • STARTING VPN..." else "READY • VERIFIED PROXY POOL LOADED"
                if (startAfter) requestVpnPermissionAndStart()
            } else {
                statusText.text = "BLOCKED • NO VERIFIED SOCKS5 PROXY"
                statusText.setTextColor(red)
            }
        }
    }

    private fun toggleVpn() {
        if (connected) sendService(HpnVpnService.ACTION_DISCONNECT)
        else if (ProxyRepository.current().endpoints.any { it.protocol == "socks5" }) requestVpnPermissionAndStart()
        else refreshSources(startAfter = true)
    }

    private fun requestVpnPermissionAndStart() {
        runCatching {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, 100) else sendService(HpnVpnService.ACTION_CONNECT)
        }.onFailure { statusText.text = "ERROR • VPN START FAILED" }
    }

    private fun rotateNow() {
        statusText.text = "SCANNING • REFRESHING BEFORE ROTATION..."
        ProxyRepository.refresh(this) { snapshot ->
            updatePool(snapshot)
            if (snapshot.endpoints.isNotEmpty()) sendService(HpnVpnService.ACTION_ROTATE)
            else { statusText.text = "BLOCKED • NO VERIFIED SOCKS5 PROXY"; statusText.setTextColor(red) }
        }
    }

    private fun showStats() {
        val snapshot = ProxyRepository.current()
        AlertDialog.Builder(this).setTitle("HPN CONNECTION STATS")
            .setMessage("VPN state: $currentState\nVerified SOCKS5 pool: ${snapshot.endpoints.size}\nSources online: ${snapshot.sourcesOk}/${snapshot.sourcesTotal}\nRotation interval: ${formatInterval(intervalSeconds)}\n\nOnly verified proxy endpoints are used. Direct fallback is disabled.")
            .setPositiveButton("CLOSE", null).show()
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val value = label("Rotation interval: ${formatInterval(intervalSeconds)}", 14f, green)
        layout.addView(value, lp(-1, 42))
        val seek = SeekBar(this).apply {
            max = 3595
            progress = intervalSeconds - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    intervalSeconds = progress + 5
                    prefs().edit().putInt("interval_seconds", intervalSeconds).apply()
                    value.text = "Rotation interval: ${formatInterval(intervalSeconds)}"
                    rotationValue.text = formatInterval(intervalSeconds)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        layout.addView(seek, lp(-1, 48))
        layout.addView(label("Choose 5 seconds to 60 minutes. Changes apply to the next rotation.", 11f, dimGreen), lp(-1, 48))
        AlertDialog.Builder(this).setTitle("⚙  HPN SETTINGS").setView(layout).setPositiveButton("DONE", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) sendService(HpnVpnService.ACTION_CONNECT)
        else if (requestCode == 100) { statusText.text = "CANCELLED • VPN PERMISSION NOT GRANTED"; statusText.setTextColor(amber) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    private fun sendService(action: String) {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, HpnVpnService::class.java).setAction(action))
            statusText.text = if (action == HpnVpnService.ACTION_DISCONNECT) "STOPPING • CLOSING VPN..." else "STARTING • WAITING FOR VPN SERVICE..."
            statusText.setTextColor(amber)
        }.onFailure { statusText.text = "ERROR • VPN SERVICE START FAILED" }
    }
}
