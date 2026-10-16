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
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Minimal black/white HPN control screen. All operational feedback is delivered as Toasts. */
class MainActivity : Activity() {
    private val black = Color.rgb(0, 0, 0)
    private val white = Color.rgb(255, 255, 255)
    private lateinit var powerButton: TextView
    private lateinit var logView: TextView
    private var connected = false
    private var intervalSeconds = 15
    private val logLines = ArrayDeque<String>()

    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != HpnVpnService.ACTION_STATE) return
            val state = intent.getStringExtra("state") ?: "UNKNOWN"
            connected = state == "CONNECTED" || state == "SWITCHING"
            renderPowerState()
            if (state.startsWith("ERROR:")) toast(state.removePrefix("ERROR: ").ifBlank { "VPN failed to start" })
            if (state == "CONNECTED") toast("HPN ON")
            if (state == "DISCONNECTED") toast("HPN OFF")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black
        window.navigationBarColor = black
        intervalSeconds = prefs().getInt("interval_seconds", 15).coerceIn(5, 3600)
        setContentView(buildMinimalScreen())
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(HpnVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        appendLog("RELAY // Render controlled gateway ready")
        appendLog("TUN // local SOCKS5 bridge waiting")
    }

    private fun buildMinimalScreen(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(black)
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(white)
            contentDescription = "Settings"
            setOnClickListener { showTimeSettings() }
        }
        root.addView(settings, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            rightMargin = dp(8)
        })

        powerButton = TextView(this).apply {
            text = "OFF"
            textSize = 25f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(black)
            background = circle(white, white, 3)
            contentDescription = "HPN ON or OFF"
            elevation = dp(10).toFloat()
            setOnClickListener { toggleVpn() }
        }
        root.addView(powerButton, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))

        logView = TextView(this).apply {
            textSize = 9f
            setTextColor(white)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isVerticalScrollBarEnabled = false
        }
        root.addView(logView, FrameLayout.LayoutParams(-1, dp(118), Gravity.BOTTOM).apply {
            bottomMargin = dp(54)
        })

        val footer = TextView(this).apply {
            text = "Humayun Shariar Himu"
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setTextColor(white)
        }
        root.addView(footer, FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM).apply {
            bottomMargin = dp(10)
        })
        return root
    }

    private fun renderPowerState() {
        if (!::powerButton.isInitialized) return
        powerButton.text = if (connected) "ON" else "OFF"
        powerButton.setTextColor(if (connected) white else black)
        powerButton.background = if (connected) circle(black, white, 3) else circle(white, white, 3)
    }

    private fun toggleVpn() {
        if (connected) {
            sendService(HpnVpnService.ACTION_DISCONNECT)
            return
        }
        appendLog("RELAY // opening protected WebSocket tunnel")
        requestVpnPermissionAndStart()
    }

    private fun requestVpnPermissionAndStart() {
        runCatching {
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST)
            else sendService(HpnVpnService.ACTION_CONNECT)
        }.onFailure { toast("VPN permission could not be opened") }
    }

    private fun showTimeSettings() {
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val value = TextView(this).apply {
            text = "${formatInterval(intervalSeconds)}"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(black)
        }
        content.addView(value, android.widget.LinearLayout.LayoutParams(-1, dp(42)))
        val seek = SeekBar(this).apply {
            max = 3595
            progress = intervalSeconds - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        intervalSeconds = progress + 5
                        prefs().edit().putInt("interval_seconds", intervalSeconds).apply()
                        value.text = formatInterval(intervalSeconds)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        content.addView(seek, android.widget.LinearLayout.LayoutParams(-1, dp(50)))
        AlertDialog.Builder(this)
            .setTitle("TIME SETUP")
            .setView(content)
            .setPositiveButton("DONE", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) sendService(HpnVpnService.ACTION_CONNECT)
            else toast("VPN permission is required")
        }
    }

    private fun sendService(action: String) {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, HpnVpnService::class.java).setAction(action))
        }.onFailure { toast("VPN service could not start") }
    }

    private fun circle(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(width, stroke)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun appendLog(message: String) {
        if (!::logView.isInitialized) return
        if (logLines.size >= 9) logLines.removeFirst()
        logLines.addLast(message)
        logView.text = logLines.joinToString("\n")
    }
    private fun prefs() = getSharedPreferences("hpn", MODE_PRIVATE)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatInterval(seconds: Int) = if (seconds >= 60) "${seconds / 60} MIN" else "$seconds SEC"

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 100
    }
}
