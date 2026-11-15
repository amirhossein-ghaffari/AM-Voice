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
import android.view.View
import android.animation.ObjectAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var debug: TextView
    private lateinit var start: TextView
    private lateinit var wakeOrb: TextView
    private lateinit var wakeLabel: TextView
    private var wakeAnimator: ObjectAnimator? = null
    private var listening = false

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VoiceService.ACTION_EVENT) return
            val type = intent.getStringExtra(VoiceService.EXTRA_TYPE).orEmpty()
            val message = intent.getStringExtra(VoiceService.EXTRA_MESSAGE).orEmpty()
            when (type) {
                "status" -> status.text = message
                "heard" -> heard.text = message
                "wake" -> renderWakeState(message == "awake")
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
        wakeOrb = findViewById(R.id.wakeOrb)
        wakeLabel = findViewById(R.id.wakeLabel)
        renderWakeState(false)
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
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.CAMERA)
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
        renderWakeState(false)
        appendDebug("UI: stop requested")
    }

    private fun renderButton() {
        start.text = if (listening) "STOP LISTENING" else "START LISTENING"
    }

    private fun renderWakeState(awake: Boolean) {
        wakeAnimator?.cancel()
        wakeAnimator = null
        if (awake) {
            wakeOrb.text = "●"
            wakeOrb.setTextColor(android.graphics.Color.rgb(0, 255, 190))
            wakeLabel.text = "AM IS AWAKE • SPEAK NOW"
            wakeLabel.setTextColor(android.graphics.Color.rgb(0, 255, 190))
            wakeOrb.scaleX = 1f
            wakeOrb.scaleY = 1f
            wakeOrb.alpha = 1f
            wakeAnimator = ObjectAnimator.ofFloat(wakeOrb, View.SCALE_X, 1f, 1.28f).apply {
                duration = 650
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(wakeOrb, View.SCALE_Y, 1f, 1.28f).apply {
                duration = 650
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            wakeOrb.animate().alpha(0.72f).setDuration(650).withEndAction {
                if (wakeLabel.text.toString().contains("AWAKE")) wakeOrb.animate().alpha(1f).setDuration(650).start()
            }.start()
        } else {
            wakeOrb.text = "◉"
            wakeOrb.setTextColor(android.graphics.Color.rgb(0, 217, 255))
            wakeLabel.text = "SAY “AM AM” TO WAKE"
            wakeLabel.setTextColor(android.graphics.Color.rgb(170, 183, 204))
            wakeOrb.scaleX = 1f
            wakeOrb.scaleY = 1f
            wakeOrb.alpha = 0.75f
        }
    }

    private fun appendDebug(line: String) {
        val old = debug.text?.toString().orEmpty().lines().filter { it.isNotBlank() }
        debug.text = (old + line).takeLast(18).joinToString("\n")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            appendDebug("PERMISSIONS: contacts=" + (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) + ", calls=" + (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) + ", camera=" + (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
            if (micGranted) startAssistant() else {
                status.text = "Microphone permission is required"
                appendDebug("PERMISSION: microphone denied")
            }
        }
    }
}
