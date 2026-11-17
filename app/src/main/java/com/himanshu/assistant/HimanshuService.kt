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
import android.os.PowerManager
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
        private val log = ArrayDeque<String>()
        fun emit(s: String) { synchronized(log) { log.addFirst(s); while (log.size > 12) log.removeLast() }; onHeard?.invoke(s) }
        fun snapshot(): List<String> = synchronized(log) { log.toList() }
        const val ARM = "arm"
        const val CMD = "cmd"
        const val RESUME = "resume"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending = 0
    private var armedUntil = 0L
    private lateinit var brain: Brain
    private var wl: PowerManager.WakeLock? = null
    private val hist = ArrayList<Pair<String, String>>()

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

        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "himanshu:listen")
        try { wl?.acquire() } catch (e: Exception) {}
        brain = Brain(this) { say(it) }
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("hi", "IN"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.getDefault())
                tts?.setSpeechRate(1.12f)
                ttsReady = true
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                    emit("⚠ फोन में हिंदी आवाज़ नहीं है। Settings > Text-to-speech > Google > Install voice data > हिंदी")
            } else emit("⚠ बोलने वाला (Text-to-speech) चालू नहीं हो पाया")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done() }
            @Deprecated("old") override fun onError(id: String?) { emit("⚠ आवाज़ बोल नहीं पाई"); done() }
        })

        emit("आवाज़ वाला मॉडल तैयार हो रहा है (पहली बार 1-2 मिनट लगते हैं)...")
        StorageService.unpack(this, "model", "model",
            { m -> model = m; startListening() },
            { e -> emit("⚠ मॉडल नहीं खुला: ${e.message}") })
    }

    private var greeted = false
    private var micTries = 0

    private fun startListening() {
        try {
            try { speech?.stop(); speech?.shutdown() } catch (e: Exception) {}
            val rec = Recognizer(model!!, 16000.0f)
            speech = SpeechService(rec, 16000.0f)
            speech?.startListening(listener)
            micTries = 0
            emit("🎧 सुन रहा हूँ")
            if (!greeted) { greeted = true; say("हिमांशु तैयार है") }
        } catch (e: Exception) {
            emit("⚠ माइक नहीं चला: ${e.message}")
            if (++micTries <= 10) handler.postDelayed({ startListening() }, 3000)
        }
    }

    private var buf = ""
    private val flushBuf = Runnable { val s = buf.trim(); buf = ""; if (s.isNotBlank()) onSpeech(s) }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(h: String?) {
            if (!brain.isPlaying()) return
            val p = try { JSONObject(h ?: return).optString("partial") } catch (e: Exception) { return }
            if (p.isNotBlank() && brain.isStop(Hindi.norm(p))) handler.post { if (brain.isPlaying()) { brain.stopNow(); emit("⏹ गाना रोक दिया") } }
        }
        override fun onFinalResult(h: String?) {}
        override fun onTimeout() {}
        override fun onError(e: Exception?) { emit("⚠ माइक रुका (${e?.message}), फिर चालू कर रहा हूँ"); handler.postDelayed({ startListening() }, 1500) }
        override fun onResult(h: String?) {
            val txt = try { JSONObject(h ?: return).optString("text") } catch (e: Exception) { return }
            if (txt.isNotBlank()) handler.post {
                buf = (buf + " " + txt).trim()
                handler.removeCallbacks(flushBuf)
                handler.postDelayed(flushBuf, 450)
            }
        }
    }

    private fun onSpeech(raw: String) {
        val t = Hindi.norm(raw)
        if (t.isBlank()) return
        val prefs = getSharedPreferences("him", MODE_PRIVATE)
        val needName = prefs.getBoolean("needName", false) || brain.isPlaying()
        val tokens = t.split(" ")
        val hasName = tokens.any { Hindi.isWake(it) }
        val armed = System.currentTimeMillis() < armedUntil
        emit("👂 $t")
        if (brain.isPlaying() && brain.isStop(t)) { brain.stopNow(); return }
        if (needName && !hasName && !armed) return
        var cmd = tokens.filter { !Hindi.isWake(it) }.joinToString(" ")
        val now = System.currentTimeMillis()
        if (cmd.isBlank()) { armedUntil = now + 180000; say("हाँ बोलो"); return }
        if (cmd.contains("सो जाओ") || cmd.contains("धन्यवाद") || cmd.contains("शुक्रिया")) {
            armedUntil = 0; say("ठीक है, जब ज़रूरत हो हिमांशु बोलिए"); return
        }
        runCommand(cmd, prefs)
    }

    private var lastFail = 0L
    private val QUESTION = listOf("क्यों", "कैसे", "क्या", "कौन", "कब", "कहाँ", "कहां", "समझाओ", "समझा", "मतलब", "अर्थ",
        "बताओ", "बता", "why", "how", "what", "who", "explain", "tell").map { Hindi.norm(it) }.toSet()

    /** छोटा सीधा आदेश हो तो फोन में ही तुरंत करो; लंबी या सवाल वाली बात AI को दो. */
    private fun isSimple(cmd: String): Boolean {
        if (cmd.contains(Hindi.norm("याद"))) return true
        val tk = cmd.split(" ").filter { it.isNotBlank() }
        return tk.size <= 10 && tk.none { it in QUESTION }
    }

    private fun runCommand(c0: String, prefs: android.content.SharedPreferences) {
        var cmd = c0
        for (line in prefs.getStringSet("alias", emptySet())!!) {
            val k = line.substringBefore("=")
            if (k.isNotBlank() && cmd.contains(k)) cmd = cmd.replace(k, line.substringAfter("="))
        }
        armedUntil = System.currentTimeMillis() + (if (brain.isPlaying()) 40000 else 180000)
        val n = System.currentTimeMillis()
        val priv = isPrivate(c0)
        val simple = isSimple(cmd)
        if ((simple || priv) && brain.handle(cmd)) return
        if (priv) { say("यह मैं फोन में ही करता हूँ, बाहर नहीं भेजता। ठीक से बोलिए, जैसे: मम्मी को फोन लगाओ"); return }
        val key = prefs.getString("apikey", "") ?: ""
        if (key.isBlank()) {
            if (!simple && brain.handle(cmd)) return
            if (n - lastFail > 10000) { lastFail = n; say("समझ नहीं आया") }
            return
        }
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 120)
            handler.postDelayed({ tg.release() }, 500)
        } catch (e: Exception) {}
        val snapshot = ArrayList(hist)
        Thread {
            val t0 = System.currentTimeMillis()
            val out = try { Ai.ask(key, c0, snapshot, false) } catch (e: Exception) { null }
            val ms = System.currentTimeMillis() - t0
            handler.post {
                emit("⏱ AI: " + (ms / 100) / 10.0 + " सेकंड")
                if (out == null) {
                    emit("⚠ AI: " + Ai.lastError)
                    if (!simple && brain.handle(cmd)) return@post
                    sayAiFail(); return@post
                }
                val reply = out.optString("reply")
                hist.add(c0 to out.toString()); while (hist.size > 6) hist.removeAt(0)
                val cmd2 = Ai.toCommand(out)
                if (cmd2.isNotBlank()) { if (!brain.handle(Hindi.norm(cmd2)) && reply.isNotBlank()) say(reply) }
                else say(if (reply.isNotBlank()) reply else "समझ नहीं आया")
            }
        }.start()
    }

    private val PRIVATE = listOf("फोन", "कॉल", "call", "नंबर", "number", "इंस्टाग्राम", "instagram", "पासवर्ड", "password",
        "ओटीपी", "otp", "बैंक", "bank", "पिन", "व्हाट्सएप", "whatsapp", "मैसेज", "message", "खाता", "कार्ड").map { Hindi.norm(it) }
    private fun isPrivate(t: String): Boolean { val n = Hindi.norm(t); return PRIVATE.any { n.contains(it) } }

    private var lastAiMsg = ""
    private var lastAiMsgAt = 0L

    private fun aiFailMessage(): String {
        val e = Ai.lastError
        return when {
            e.contains("HTTP 401") || e.contains("API key not valid") || e.contains("API_KEY_INVALID") -> "चाबी सही नहीं है"
            e.contains("HTTP 403") -> "इस चाबी को अभी इजाज़त नहीं मिली"
            e.contains("429") -> "अभी AI थोड़ा व्यस्त है"
            e.contains("HTTP 404") || e.contains("HTTP 400") || e.contains("empty") -> "AI से जवाब नहीं आया, फिर बोलिए"
            else -> "इंटरनेट नहीं चल रहा लगता है"
        }
    }

    /** वही एरर 30 सेकंड में दोबारा नहीं बोलता. */
    private fun sayAiFail() {
        val m = aiFailMessage(); val now = System.currentTimeMillis()
        if (m == lastAiMsg && now - lastAiMsgAt < 30000) return
        lastAiMsg = m; lastAiMsgAt = now; say(m)
    }

    private var sayGen = 0

    fun say(text: String) {
        handler.post {
            emit("🔊 $text")
            if (!ttsReady) return@post
            speech?.setPause(true)
            pending++
            val gen = ++sayGen
            val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
            if (r != TextToSpeech.SUCCESS) { pending--; emit("⚠ बोलने में दिक्कत आई") }
            // भरोसेमंद वापसी: आवाज़ ख़त्म हो न हो, माइक थोड़ी देर बाद फिर चालू हो जाएगा
            handler.postDelayed({
                if (gen == sayGen) { pending = 0; speech?.setPause(false); emit("🎧 सुन रहा हूँ") }
            }, 900L + text.length * 110L)
        }
    }

    private fun done() {
        handler.postDelayed({
            if (--pending <= 0) { pending = 0; speech?.setPause(false) }
        }, 250)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == RESUME) {
            pending = 0; speech?.setPause(false); emit("🎧 माइक चालू कर दिया")
        }
        if (i?.action == CMD) {
            val txt = i?.getStringExtra("text") ?: ""
            if (txt.isNotBlank()) runCommand(Hindi.norm(txt), getSharedPreferences("him", MODE_PRIVATE))
        }
        if (i?.action == ARM) { armedUntil = System.currentTimeMillis() + 8000; say("हाँ बोलो") }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wl?.release(); speech?.stop(); speech?.shutdown(); model?.close(); tts?.shutdown(); brain.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
