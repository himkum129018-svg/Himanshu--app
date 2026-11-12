package com.himanshu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

class HimanshuService : Service() {
    companion object {
        var onHeard: ((String) -> Unit)? = null
        const val ARM = "arm"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending = 0
    private var armedUntil = 0L
    private lateinit var brain: Brain

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("him", "हिमांशु", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "him").setContentTitle("हिमांशु सुन रहा है")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        brain = Brain(this) { say(it) }
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) { tts?.language = Locale("hi", "IN"); ttsReady = true }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done() }
            @Deprecated("old") override fun onError(id: String?) { done() }
        })

        onHeard?.invoke("आवाज़ वाला मॉडल तैयार हो रहा है (पहली बार 1-2 मिनट लगते हैं)...")
        StorageService.unpack(this, "model", "model",
            { m -> model = m; startListening() },
            { e -> onHeard?.invoke("⚠ मॉडल नहीं खुला: ${e.message}") })
    }

    private fun startListening() {
        try {
            val rec = Recognizer(model!!, 16000.0f)
            speech = SpeechService(rec, 16000.0f)
            speech?.startListening(listener)
            say("हिमांशु तैयार है")
        } catch (e: Exception) { onHeard?.invoke("⚠ माइक नहीं चला: ${e.message}") }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(h: String?) {}
        override fun onFinalResult(h: String?) {}
        override fun onTimeout() {}
        override fun onError(e: Exception?) { onHeard?.invoke("⚠ ${e?.message}") }
        override fun onResult(h: String?) {
            val txt = try { JSONObject(h ?: return).optString("text") } catch (e: Exception) { return }
            if (txt.isNotBlank()) handler.post { onSpeech(txt) }
        }
    }

    private fun onSpeech(raw: String) {
        val t = Hindi.norm(raw)
        if (t.isBlank()) return
        val prefs = getSharedPreferences("him", MODE_PRIVATE)
        val needName = prefs.getBoolean("needName", true) || brain.isPlaying()
        val tokens = t.split(" ")
        val hasName = tokens.any { Hindi.isWake(it) }
        val armed = System.currentTimeMillis() < armedUntil
        onHeard?.invoke("👂 $t")
        if (needName && !hasName && !armed) return
        var cmd = tokens.filter { !Hindi.isWake(it) }.joinToString(" ")
        val now = System.currentTimeMillis()
        if (cmd.isBlank()) { armedUntil = now + 60000; say("हाँ बोलो"); return }
        if (cmd.contains("सो जाओ") || cmd.contains("धन्यवाद") || cmd.contains("शुक्रिया")) {
            armedUntil = 0; say("ठीक है, जब ज़रूरत हो हिमांशु बोलिए"); return
        }
        if (cmd.contains("मतलब")) { learn(cmd, prefs); armedUntil = now + 60000; return }
        for (line in prefs.getStringSet("alias", emptySet())!!) {
            val k = line.substringBefore("=")
            if (k.isNotBlank() && cmd.contains(k)) cmd = cmd.replace(k, line.substringAfter("="))
        }
        val handled = brain.handle(cmd)
        armedUntil = System.currentTimeMillis() + (if (brain.isPlaying()) 15000 else 60000)
        if (!handled) say("मुझे समझ नहीं आया, फिर से बोलिए। नया शब्द सिखाने के लिए बोलिए: लाइट का मतलब टॉर्च")
    }

    private fun learn(cmd: String, prefs: android.content.SharedPreferences) {
        val junk = setOf("सीखो", "सिखाओ", "सीख", "का", "की", "के", "यानी", "है", "होता", "मतलब")
        val left = cmd.substringBefore("मतलब").split(" ").filter { it.isNotBlank() && it !in junk }.joinToString(" ")
        val right = cmd.substringAfter("मतलब").split(" ").filter { it.isNotBlank() && it !in junk }.joinToString(" ")
        if (left.isBlank() || right.isBlank()) { say("ऐसे बोलिए: लाइट का मतलब टॉर्च"); return }
        val set = (prefs.getStringSet("alias", emptySet()) ?: emptySet()).filter { !it.startsWith("$left=") }.toMutableSet()
        set.add("$left=$right")
        prefs.edit().putStringSet("alias", set).apply()
        say("सीख लिया। अब $left बोलने पर मैं $right समझूँगा")
    }

    fun say(text: String) {
        handler.post {
            onHeard?.invoke("🔊 $text")
            if (!ttsReady) return@post
            speech?.setPause(true)
            pending++
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
        }
    }

    private fun done() {
        handler.postDelayed({
            if (--pending <= 0) { pending = 0; speech?.setPause(false) }
        }, 500)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ARM) { armedUntil = System.currentTimeMillis() + 8000; say("हाँ बोलो") }
        return START_STICKY
    }

    override fun onDestroy() {
        try { speech?.stop(); speech?.shutdown(); model?.close(); tts?.shutdown(); brain.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
