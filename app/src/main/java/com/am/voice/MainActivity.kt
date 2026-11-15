package com.am.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var debug: TextView
    private lateinit var start: TextView
    private var listening = false

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VoiceService.ACTION_EVENT) return
            val type = intent.getStringExtra(VoiceService.EXTRA_TYPE).orEmpty()
            val message = intent.getStringExtra(VoiceService.EXTRA_MESSAGE).orEmpty()
            when (type) {
                "status" -> status.text = message
                "heard" -> heard.text = message
                "debug" -> appendDebug(message)
                "state" -> {
                    listening = message == "running"
                    renderButton()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        heard = findViewById(R.id.heardText)
        debug = findViewById(R.id.debugText)
        start = findViewById(R.id.startButton)
        start.setOnClickListener {
            if (listening) stopAssistant()
            else if (hasPermissions()) startAssistant()
            else requestNeededPermissions()
        }
        renderButton()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(VoiceService.ACTION_EVENT)
        ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(events) } catch (_: Exception) { }
        super.onStop()
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 41)
    }

    private fun startAssistant() {
        val intent = Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        listening = true
        renderButton()
        status.text = "Starting English speech engine..."
        heard.text = "Say: Hello AM"
        appendDebug("UI: start requested")
    }

    private fun stopAssistant() {
        stopService(Intent(this, VoiceService::class.java))
        listening = false
        renderButton()
        status.text = "Assistant stopped"
        appendDebug("UI: stop requested")
    }

    private fun renderButton() {
        start.text = if (listening) "STOP LISTENING" else "START LISTENING"
    }

    private fun appendDebug(line: String) {
        val old = debug.text?.toString().orEmpty().lines().filter { it.isNotBlank() }
        debug.text = (old + line).takeLast(18).joinToString("\n")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAssistant()
        } else {
            status.text = "Microphone permission is required"
            appendDebug("PERMISSION: microphone denied")
        }
    }
}
