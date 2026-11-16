package com.am.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.net.Uri
import android.Manifest
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

class VoiceService : Service() {
    companion object {
        const val ACTION_START = "com.am.voice.START"
        const val ACTION_EVENT = "com.am.voice.EVENT"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "am_voice_channel"
        private const val NOTIFICATION_ID = 7001
        private const val SAMPLE_RATE = 16000
    }

    @Volatile private var running = false
    @Volatile private var wakeUntil = 0L
    private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private var lastPartialAt = 0L
    private var lastLevelLogAt = 0L
    private var lastWakePartialAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingContactMatches: List<Pair<String, String>> = emptyList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting AM Voice"))
        try { wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AMVoice::Mic").apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { }
        tts = android.speech.tts.TextToSpeech(this) { result ->
            val ready = result == android.speech.tts.TextToSpeech.SUCCESS
            emit("debug", "TTS initialization: " + if (ready) "ready" else "failed")
            if (ready) {
                val language = tts?.setLanguage(Locale.US)
                emit("debug", "TTS English language result=$language (speech output is optional)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) startListening()
        return START_STICKY
    }

    private fun startListening() {
        running = true
        emit("state", "running")
        emit("status", "Loading English offline model...")
        emit("debug", "SERVICE: startListening()")
        worker = thread(name = "AMVoiceAudio") {
            try {
                val modelDir = File(filesDir, "vosk-model-en")
                val marker = File(modelDir, "am/final.mdl")
                if (!marker.exists()) {
                    emit("debug", "MODEL: copying assets to ${modelDir.absolutePath}")
                    copyAssets("vosk-model", modelDir)
                }
                if (!marker.exists()) {
                    throw IllegalStateException("English model missing: am/final.mdl not found in assets/vosk-model")
                }
                emit("debug", "MODEL: files found; creating Vosk model")
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                emit("debug", "MODEL: Vosk recognizer ready (en-US, 16 kHz)")

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("AudioRecord minBufferSize error=$minBuffer")
                val bufferSize = maxOf(minBuffer * 2, 8192)
                emit("debug", "MIC: minBuffer=$minBuffer, buffer=$bufferSize")
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                recorder = audio
                emit("debug", "MIC: AudioRecord state=${audio.state}")
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed (state=${audio.state})")
                }
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Microphone did not enter RECORDSTATE_RECORDING")
                }
                emit("status", "Listening. Say: AM AM")
                emit("debug", "MIC: recording started successfully")
                updateNotification("Listening for: AM AM")
                val buffer = ByteArray(4096)

                while (running && !Thread.currentThread().isInterrupted) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count < 0) throw IllegalStateException("Microphone read failed: AudioRecord error=$count")
                    if (count == 0) continue

                    val now = System.currentTimeMillis()
                    if (wakeUntil > 0L && now > wakeUntil) {
                        wakeUntil = 0L
                        emit("wake", "idle")
                        emit("status", "Listening. Say: AM AM")
                        updateNotification("Listening for: AM AM")
                    }
                    val rms = calculateRms(buffer, count)
                    if (now - lastLevelLogAt > 3000) {
                        emit("debug", "MIC: input active; RMS=${"%.1f".format(Locale.US, rms)}")
                        lastLevelLogAt = now
                    }

                    val activeRecognizer = recognizer ?: throw IllegalStateException("Recognizer unexpectedly null")
                    if (activeRecognizer.acceptWaveForm(buffer, count)) {
                        val text = JSONObject(activeRecognizer.result).optString("text").trim().lowercase(Locale.US)
                        emit("debug", if (text.isBlank()) "VOSK FINAL: (empty)" else "VOSK FINAL: $text")
                        if (text.isNotBlank()) processRecognized(text)
                    } else if (now - lastPartialAt > 450) {
                        val partial = JSONObject(activeRecognizer.partialResult).optString("partial").trim()
                        if (partial.isNotBlank()) {
                            emit("heard", "Recognizing: $partial")
                            emit("debug", "VOSK PARTIAL: $partial")
                            lastPartialAt = now
                            val p = normalizeForMatch(partial)
                            if ((p.contains("am am") || p.contains("i am am") || p.contains("a m a m")) && now - lastWakePartialAt > 1200) {
                                lastWakePartialAt = now
                                if (wakeUntil < now) {
                                    wakeUntil = now + 18000
                                    emit("wake", "awake")
                                    emit("status", "AM IS AWAKE — SPEAK NOW")
                                    emit("debug", "WAKE: early partial match; 18s command window")
                                    updateNotification("AM awake - waiting for command")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                emit("status", "ERROR: ${e.message ?: e.javaClass.simpleName}")
                emit("debug", "EXCEPTION: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(5).forEach { emit("debug", " at $it") }
                running = false
                emit("state", "stopped")
                stopSelf()
            } finally {
                releaseAudioAndVosk()
            }
        }
    }

    private fun calculateRms(bytes: ByteArray, count: Int): Double {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < count) {
            val value = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sum += value.toDouble() * value.toDouble()
            samples++
            i += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun processRecognized(text: String) {
        val now = System.currentTimeMillis()
        val normalizedWakeText = normalizeForMatch(text)
        val wakePhrases = listOf(
            "am am", "a m a m",
            "hey am", "hey a m",
            "hello am", "hello a m",
            "hi am", "hi a m"
        )
        if (wakePhrases.any { normalizedWakeText.contains(normalizeForMatch(it)) }) {
            wakeUntil = now + 15000
            emit("wake", "awake")
            emit("status", "AM is awake — speak now")
            emit("heard", "Wake word detected: $text")
            emit("debug", "WAKE: accepted; command window=15s; visual awake indicator enabled; silent wake")
            updateNotification("Awake - waiting for command")
            // Do not speak here: the live microphone would recognize the assistant's own voice.
            return
        }

        if (now > wakeUntil) {
            emit("debug", "IGNORED: outside wake window: $text")
            emit("heard", "Heard (not activated): $text")
            return
        }

        wakeUntil = 0L
        emit("wake", "idle")
        emit("heard", "Command: $text")
        emit("debug", "COMMAND: dispatching '$text'")
        updateNotification("Command: $text")
        executeCommand(text)
    }

    private fun executeCommand(text: String) {
        val choice = normalizeForMatch(text)
        if (pendingContactMatches.isNotEmpty()) {
            val selected = when (choice) { "first", "the first", "one", "number one" -> 0; "second", "the second", "two", "number two" -> 1; else -> -1 }
            if (selected >= 0 && selected < pendingContactMatches.size) {
                val item = pendingContactMatches[selected]
                pendingContactMatches = emptyList()
                placeContactCall(item.first, item.second)
                return
            }
        }
        val callMatch = Regex("^(?:please\\s+)?(?:call|phone|dial)\\s+(?:to\\s+)?(.+)$").find(text)
        when {
            callMatch != null ->
                callContact(callMatch.groupValues[1].trim())
            text.contains("open chrome") || text.contains("launch chrome") -> openApp("com.android.chrome", "Chrome")
            text.contains("open whatsapp") || text.contains("launch whatsapp") -> openApp("com.whatsapp", "WhatsApp")
            text.contains("open telegram") || text.contains("launch telegram") -> openApp("org.telegram.messenger", "Telegram")
            text.contains("open youtube") || text.contains("launch youtube") -> openAppByName("YouTube")
            text.contains("open instagram") || text.contains("launch instagram") -> openAppByName("Instagram")
            text.contains("open gmail") || text.contains("launch gmail") -> openAppByName("Gmail")
            text.contains("open maps") || text.contains("launch maps") -> openAppByName("Maps")
            text.contains("open spotify") || text.contains("launch spotify") -> openAppByName("Spotify")
            text.contains("open calculator") || text.contains("launch calculator") -> openAppByName("Calculator")
            text.contains("open gallery") || text.contains("launch gallery") || text.contains("open photos") -> openAppByName("Gallery")
            text.startsWith("open app ") || text.startsWith("launch app ") ->
                openAppByName(text.removePrefix("open app ").removePrefix("launch app ").trim())
            text.contains("open camera") || text.contains("launch camera") -> launch(Intent("android.media.action.IMAGE_CAPTURE"), "Camera")
            text.contains("open settings") || text.contains("settings") -> launch(Intent(Settings.ACTION_SETTINGS), "Settings")
            text.contains("wifi") || text.contains("wi-fi") -> launch(Intent(Settings.Panel.ACTION_WIFI), "Wi-Fi panel")
            text.contains("turn on flashlight") || text.contains("flashlight on") -> setTorch(true)
            text.contains("turn off flashlight") || text.contains("flashlight off") -> setTorch(false)
            text.contains("volume up") || text.contains("increase volume") -> changeVolume(true)
            text.contains("volume down") || text.contains("decrease volume") -> changeVolume(false)
            text.contains("what time") || text == "time" || text.contains("tell me the time") -> {
                val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                speak("It is $time")
            }
            text.contains("stop listening") || text == "stop" || text.contains("goodbye") -> {
                speak("Stopping")
                stopSelf()
            }
            text.startsWith("open ") || text.startsWith("launch ") ->
                openAppByName(text.removePrefix("open ").removePrefix("launch ").trim())
            else -> {
                emit("status", "I heard you, but this command is not supported.")
                emit("debug", "COMMAND: no matching rule for '$text'")
                speak("Sorry, I don't know that command yet")
            }
        }
    }

    private fun normalizeForMatch(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun callContact(rawQuery: String) {
        // Speech recognizers sometimes transcribe "to" as "two" in phrases like "call to Dad".
        val query = normalizeForMatch(rawQuery)
            .replace(Regex("^(?:to|two)\\s+"), "")
            .replace(Regex("^(?:my|the)\\s+"), "")
            .trim()
        if (query.isBlank()) { speak("Please say the contact name after call"); return }
        emit("debug", "CONTACT: searching for normalized name '$query'")
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            emit("status", "Contacts permission missing. Enable Contacts permission in app settings.")
            emit("debug", "CONTACT: READ_CONTACTS denied")
            speak("Please allow contacts permission in app settings")
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            emit("status", "Phone permission missing. Enable Phone permission in app settings.")
            emit("debug", "CALL: CALL_PHONE denied")
            speak("Please allow phone permission in app settings")
            return
        }
        val found = mutableListOf<Pair<String, String>>()
        try {
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%" + query + "%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val ni = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(ni).orEmpty()
                    val number = cursor.getString(pi).orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) found.add(name to number)
                }
            }
        } catch (e: Exception) {
            emit("debug", "CONTACT QUERY ERROR: " + e.javaClass.simpleName + ": " + e.message)
            speak("I could not search contacts"); return
        }
        val unique = found.distinctBy { it.second }
        val q = query
        val exact = unique.filter { normalizeForMatch(it.first) == q }
        val matches = if (exact.isNotEmpty()) exact else unique.filter { name ->
            val candidate = normalizeForMatch(name.first)
            candidate.contains(q) || q.split(" ").all { token -> candidate.split(" ").any { it.startsWith(token) } }
        }
        when (matches.size) {
            0 -> {
                emit("status", "No contact found for " + query)
                emit("debug", "CONTACT: no match; raw results=" + found.size)
                speak("I could not find " + query)
            }
            1 -> {
                val (name, number) = matches.first()
                placeContactCall(name, number)
            }
            else -> {
                pendingContactMatches = matches.take(2)
                val first = pendingContactMatches[0].first
                val second = pendingContactMatches.getOrNull(1)?.first ?: "none"
                emit("status", "First: " + first + " • Second: " + second + ". Say first or second.")
                emit("debug", "CONTACT: ambiguous matches=" + matches.size + "; voice choice available")
                speak("First: " + first + ". Second: " + second + ". Say first or second")
            }
        }
    }

    private fun placeContactCall(name: String, number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            emit("status", "Call permission is not granted")
            speak("Please grant phone permission first")
            return
        }
        try {
            val call = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(call)
            emit("status", "Calling " + name)
            speak("Calling " + name)
        } catch (e: Exception) {
            emit("debug", "CALL FAILED: " + e.javaClass.simpleName + ": " + e.message)
            speak("I could not start the call")
        }
    }

    private fun openAppByName(requestedName: String) {
        if (requestedName.isBlank()) { speak("Please say the app name"); return }
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installed = packageManager.queryIntentActivities(intent, 0)
            val apps = installed.mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val pkg = info.activityInfo?.packageName.orEmpty()
                if (label.isNotBlank() && pkg.isNotBlank()) label to pkg else null
            }.distinctBy { it.second }
            val q = normalizeForMatch(requestedName)
            val exact = apps.filter { normalizeForMatch(it.first) == q }
            val matches = if (exact.isNotEmpty()) exact else apps.filter { normalizeForMatch(it.first).contains(q) }
            when (matches.size) {
                0 -> {
                    emit("status", "Installed app not found: " + requestedName)
                    emit("debug", "APP SEARCH: no match for " + requestedName + "; launchable apps=" + apps.size)
                    speak("I could not find the app " + requestedName)
                }
                1 -> {
                    val (label, pkg) = matches.first()
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                    if (launch == null) {
                        emit("debug", "APP SEARCH: launch intent missing for " + label)
                        speak("I found " + label + " but cannot open it")
                    } else {
                        emit("debug", "APP SEARCH: matched " + label + " package=" + pkg)
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                        speak("Opening " + label)
                    }
                }
                else -> {
                    emit("status", "Several apps match: " + matches.take(5).joinToString { it.first } + ". Say a more exact name.")
                    emit("debug", "APP SEARCH: ambiguous " + requestedName + "; matches=" + matches.size)
                    speak("Several apps match. Please be more specific")
                }
            }
        } catch (e: Exception) {
            emit("debug", "APP SEARCH ERROR: " + e.javaClass.simpleName + ": " + e.message)
            speak("I could not search installed apps")
        }
    }

    private fun copyAssets(source: String, destination: File) {
        val children = assets.list(source) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(source).use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        } else {
            destination.mkdirs()
            children.forEach { child -> copyAssets("$source/$child", File(destination, child)) }
        }
    }

    private fun openApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            emit("debug", "APP: package not installed or not visible: $packageName")
            speak("$label is not installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        speak("Opening $label")
    }

    private fun launch(intent: Intent, label: String) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            speak("Opening $label")
        } catch (e: Exception) {
            emit("debug", "INTENT: $label failed: ${e.javaClass.simpleName}: ${e.message}")
            speak("Cannot open $label")
        }
    }

    private fun setTorch(enabled: Boolean) {
        try {
            val camera = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                speak("No flashlight found")
                return
            }
            camera.setTorchMode(id, enabled)
            speak(if (enabled) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) {
            emit("debug", "TORCH: ${e.javaClass.simpleName}: ${e.message}")
            speak("Flashlight control failed")
        }
    }

    private fun changeVolume(up: Boolean) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        speak(if (up) "Volume up" else "Volume down")
    }

    private fun speak(message: String) {
        emit("status", message)
        val engine = tts ?: return
        engine.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "am-${System.currentTimeMillis()}")
    }

    private fun emit(type: String, message: String) {
        android.util.Log.i("AMVoice", "$type: $message")
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_TYPE, type).putExtra(EXTRA_MESSAGE, message))
        if (type == "status") updateNotification(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "AM Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AM Voice")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun releaseAudioAndVosk() {
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { recognizer?.close() } catch (_: Exception) { }
        recognizer = null
        try { model?.close() } catch (_: Exception) { }
        model = null
    }

    override fun onDestroy() {
        running = false
        emit("state", "stopped")
        try { recorder?.stop() } catch (_: Exception) { }
        worker?.interrupt()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
