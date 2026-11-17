package com.himanshu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
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
import org.vosk.android.StorageService
import java.util.Locale

class HimanshuService : Service() {
    companion object {
        var onHeard: ((String) -> Unit)? = null
        @Volatile var running = false
        private val log = ArrayDeque<String>()
        fun emit(s: String) { synchronized(log) { log.addFirst(s); while (log.size > 12) log.removeLast() }; onHeard?.invoke(s) }
        fun snapshot(): List<String> = synchronized(log) { log.toList() }
        const val ARM = "arm"
        const val CMD = "cmd"
        const val RESUME = "resume"
        const val STOP = "stop"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var mic: Mic? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = 0
    private var armedUntil = 0L
    private var brain: Brain? = null
    private var wl: PowerManager.WakeLock? = null
    private val hist = ArrayList<Pair<String, String>>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("him", "हिमांशु", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, HimanshuService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "him").setContentTitle("हिमांशु सुन रहा है")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "बंद करो", stopPi).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            // माइक की अनुमति नहीं है, या फोन ने पीछे से चालू होने नहीं दिया: क्रैश करने के बजाय चुपचाप रुक जाओ
            emit("⚠ सुनना चालू नहीं हो पाया। ऐप खोलकर 'शुरू करो' दबाइए (माइक की अनुमति चाहिए)")
            stopSelf()
            return
        }
        running = true

        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "himanshu:listen")
        try { wl?.acquire() } catch (e: Exception) {}
        brain = Brain(this) { say(it) }
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) setupTts() else emit("⚠ बोलने वाला (Text-to-speech) चालू नहीं हो पाया")
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

    private fun setupTts() {
        val t = tts ?: return
        val r = t.setLanguage(Locale("hi", "IN"))
        val bad = r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED
        if (bad) {
            t.setLanguage(Locale.getDefault())
        } else {
            // सबसे अच्छी हिंदी आवाज़ चुनो (बिना इंटरनेट वाली को थोड़ी तरजीह)
            try {
                val vs = t.voices?.filter { it.locale.language == "hi" && !(it.features?.contains("notInstalled") ?: false) } ?: emptyList()
                val best = vs.maxByOrNull { it.quality * 10 - (if (it.isNetworkConnectionRequired) 3 else 0) }
                if (best != null) t.setVoice(best)
            } catch (e: Exception) {}
        }
        try {
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        } catch (e: Exception) {}
        t.setSpeechRate(1.0f)
        t.setPitch(1.0f)
        ttsReady = true
        if (bad) emit("⚠ फोन में हिंदी आवाज़ नहीं है। ऐप की सेटिंग में 'हिंदी आवाज़ सेट करो' दबाइए")
    }

    private var greeted = false
    private var micTries = 0
    private var errCount = 0

    private fun startListening() {
        val m = model ?: return
        try {
            try { mic?.stop(); mic?.shutdown() } catch (e: Exception) {}
            val rec = Recognizer(m, 16000.0f)
            mic = Mic(rec, listener)
            mic?.start()
            micTries = 0
            emit("🎧 सुन रहा हूँ")
            if (!greeted) { greeted = true; say("हिमांशु तैयार है") }
        } catch (e: Exception) {
            emit("⚠ माइक नहीं चला: ${e.message}")
            if (++micTries <= 10) handler.postDelayed({ if (running) startListening() }, 3000)
        }
    }

    private var buf = ""
    private val flushBuf = Runnable { val s = buf.trim(); buf = ""; if (s.isNotBlank()) onSpeech(s) }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(h: String?) {
            val b = brain ?: return
            if (!b.isPlaying()) return
            val p = try { JSONObject(h ?: return).optString("partial") } catch (e: Exception) { return }
            if (p.isNotBlank() && b.isStop(Hindi.norm(p))) handler.post { if (b.isPlaying()) { b.stopNow(); emit("⏹ गाना रोक दिया") } }
        }
        override fun onFinalResult(h: String?) {}
        override fun onTimeout() {}
        override fun onError(e: Exception?) {
            emit("⚠ माइक रुका (${e?.message}), फिर चालू कर रहा हूँ")
            errCount++
            handler.postDelayed({ if (running) startListening() }, minOf(1500L * errCount, 15000L))
        }
        override fun onResult(h: String?) {
            val txt = try { JSONObject(h ?: return).optString("text") } catch (e: Exception) { return }
            if (txt.isNotBlank()) {
                errCount = 0
                handler.post {
                    buf = (buf + " " + txt).trim()
                    handler.removeCallbacks(flushBuf)
                    handler.postDelayed(flushBuf, 450)
                }
            }
        }
    }

    private fun onSpeech(raw: String) {
        val b = brain ?: return
        val t = Hindi.norm(raw)
        if (t.isBlank()) return
        val prefs = getSharedPreferences("him", MODE_PRIVATE)
        val playing = b.isPlaying()
        val needName = prefs.getBoolean("needName", false) || playing
        val tokens = t.split(" ")
        val hasName = tokens.any { Hindi.isWake(it) }
        val now = System.currentTimeMillis()
        val armed = now < armedUntil
        emit("👂 $t")
        if (b.confirmReply(t)) return
        if (playing && b.isStop(t)) { b.stopNow(); return }
        if (needName && !hasName && !armed) return
        val cmdTokens = tokens.filter { !Hindi.isWake(it) }
        val cmd = cmdTokens.joinToString(" ")
        if (cmd.isBlank()) { armedUntil = now + 180000; say("हाँ बोलो"); return }
        if (cmd.contains("सो जाओ") || cmd.contains("धन्यवाद") || cmd.contains("शुक्रिया")) {
            armedUntil = 0; say("ठीक है, जब ज़रूरत हो हिमांशु बोलिए"); return
        }
        // अकेला एक शब्द, और नाम भी नहीं लिया: शायद शोर है। सिर्फ़ फोन के नियम आज़माओ, AI को मत भेजो
        if (!hasName && !armed && cmdTokens.size < 2) { b.handle(cmd); return }
        runCommand(cmd, prefs)
    }

    private var lastFail = 0L
    private val QUESTION = listOf("क्यों", "कैसे", "क्या", "कौन", "कब", "कहाँ", "कहां", "समझाओ", "समझा", "मतलब", "अर्थ",
        "बताओ", "बता", "why", "how", "what", "who", "explain", "tell").map { Hindi.norm(it) }.toSet()
    private val DEEP = listOf("कैसे", "क्यों", "समझाओ", "समझा", "तरीका", "तरीके", "कारण", "फर्क", "अंतर", "फायदे", "नुकसान",
        "बचाएं", "बचाने", "why", "how", "explain").map { Hindi.norm(it) }.toSet()

    /** छोटा सीधा आदेश हो तो फोन में ही तुरंत करो; लंबी या सवाल वाली बात AI को दो. */
    private fun isSimple(cmd: String): Boolean {
        if (cmd.contains(Hindi.norm("याद"))) return true
        val tk = cmd.split(" ").filter { it.isNotBlank() }
        return tk.size <= 10 && tk.none { it in QUESTION }
    }

    /** "समय क्या हुआ" जैसे छोटे सवाल जिनका जवाब फोन खुद दे सकता है (गहरे "कैसे/क्यों" वाले सवाल नहीं). */
    private fun isQuickInfo(cmd: String): Boolean {
        val c2 = cmd.replace("कैसे हो", "").replace("कैसे हैं", "").replace("कैसी हो", "")
        val tk = c2.split(" ").filter { it.isNotBlank() }
        return cmd.split(" ").size <= 7 && tk.none { it in DEEP }
    }

    private fun runCommand(c0: String, prefs: SharedPreferences) {
        val b = brain ?: return
        var cmd = c0
        for (line in prefs.getStringSet("alias", emptySet())!!) {
            val k = line.substringBefore("=")
            if (k.isNotBlank() && cmd.contains(k)) cmd = cmd.replace(k, line.substringAfter("="))
        }
        armedUntil = System.currentTimeMillis() + (if (b.isPlaying()) 40000 else 180000)
        val n = System.currentTimeMillis()
        val priv = isPrivate(c0)
        val simple = isSimple(cmd)
        if ((simple || priv) && b.handle(cmd)) return
        if (priv) { say("यह मैं फोन में ही करता हूँ, बाहर नहीं भेजता। ठीक से बोलिए, जैसे: मम्मी को फोन लगाओ"); return }
        if (!simple && isQuickInfo(cmd) && b.handleInfo(cmd)) return
        val key = prefs.getString("apikey", "") ?: ""
        if (key.isBlank()) {
            if (!simple && b.handle(cmd)) return
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
            val out = try { Ai.ask(key, c0, snapshot) } catch (e: Exception) { null }
            val ms = System.currentTimeMillis() - t0
            handler.post {
                emit("⏱ AI: " + (ms / 100) / 10.0 + " सेकंड")
                if (out == null) {
                    emit("⚠ AI: " + Ai.lastError)
                    if (!simple && b.handle(cmd)) return@post
                    sayAiFail(); return@post
                }
                val reply = out.optString("reply")
                val act = out.optString("action")
                val arg = out.optString("arg")
                hist.add(c0 to out.toString()); while (hist.size > 6) hist.removeAt(0)
                when (act) {
                    "alarm" -> {
                        val p = arg.split(":")
                        val h = p.getOrNull(0)?.trim()?.toIntOrNull()
                        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                        if (h != null && h in 0..23 && m in 0..59) b.alarmAt(h, m)
                        else say(if (reply.isNotBlank()) reply else "समय समझ नहीं आया")
                    }
                    "note_save" -> b.saveNote(arg)
                    else -> {
                        val cmd2 = Ai.toCommand(out)
                        if (cmd2.isNotBlank() && b.handle(Hindi.norm(cmd2))) return@post
                        say(if (reply.isNotBlank()) reply else "समझ नहीं आया")
                    }
                }
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
            e.contains("समय पर") || e.contains("timed out") || e.contains("Timeout", true) -> "AI ने देर कर दी, फिर बोलिए"
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
            val spoken = text.replace(Regex("[*#`_~]"), " ").take(500)
            mic?.setPause(true)
            brain?.duck(true)
            speaking++
            val gen = ++sayGen
            val r = tts?.speak(spoken, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
            if (r != TextToSpeech.SUCCESS) { speaking = maxOf(0, speaking - 1); emit("⚠ बोलने में दिक्कत आई") }
            // भरोसेमंद वापसी: आवाज़ ख़त्म हो न हो, माइक थोड़ी देर बाद फिर चालू हो जाएगा
            handler.postDelayed({ if (gen == sayGen) resumeMic() }, 1500L + spoken.length * 130L)
        }
    }

    private fun resumeMic() {
        speaking = 0
        mic?.setPause(false)
        brain?.duck(false)
    }

    private fun done() {
        handler.postDelayed({
            speaking = maxOf(0, speaking - 1)
            if (speaking <= 0) resumeMic()
        }, 350)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (!running) return START_NOT_STICKY
        when (i?.action) {
            STOP -> { stopSelf(); return START_NOT_STICKY }
            RESUME -> { resumeMic(); emit("🎧 माइक चालू कर दिया") }
            CMD -> {
                val txt = i?.getStringExtra("text") ?: ""
                if (txt.isNotBlank()) {
                    emit("⌨ $txt")
                    runCommand(Hindi.norm(txt), getSharedPreferences("him", MODE_PRIVATE))
                }
            }
            ARM -> { armedUntil = System.currentTimeMillis() + 8000; say("हाँ बोलो") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            try { wl?.release() } catch (e: Exception) {}
            try { mic?.stop(); mic?.shutdown() } catch (e: Exception) {}
            try { model?.close() } catch (e: Exception) {}
            try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
            try { brain?.release() } catch (e: Exception) {}
            emit("⏹ बंद हो गया")
        }
        super.onDestroy()
    }
}
