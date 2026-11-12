package com.himanshu.suraj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.provider.ContactsContract
import android.provider.Settings
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.Random

class SurajService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var wl: PowerManager.WakeLock? = null
    private var waitingCmd = 0L
    private var songs: List<Pair<String, Uri>> = emptyList()
    private var songIdx = 0
    private val handler = Handler(Looper.getMainLooper())
    private val names = listOf("हिमांशु", "हिमांशू", "हिमांसु", "हिमाँशु", "हिमान्शु")
    private val nums = mapOf(
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5,
        "छह" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10,
        "पंद्रह" to 15, "बीस" to 20, "तीस" to 30, "साठ" to 60
    )

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("suraj", "हिमांशु", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "suraj")
            .setContentTitle("हिमांशु सुन रहा है")
            .setContentText("\"हिमांशु\" बोलिए")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "suraj:wl")
        wl?.acquire()
        tts = TextToSpeech(this, this)
        Thread { loadModel() }.start()
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("hi", "IN"))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { handler.post { speech?.setPause(false) } }
                override fun onError(id: String?) { handler.post { speech?.setPause(false) } }
            })
            ttsReady = true
        }
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (f in list) copyAssets("$path/$f", File(dest, f))
        }
    }

    private fun loadModel() {
        try {
            val dir = File(filesDir, "model-hi")
            val ok = File(filesDir, "model-ok")
            if (!ok.exists()) {
                dir.deleteRecursively()
                copyAssets("model-hi", dir)
                ok.writeText("1")
            }
            val model = Model(dir.absolutePath)
            val rec = Recognizer(model, 16000f)
            speech = SpeechService(rec, 16000f)
            speech?.startListening(this)
            handler.post { bolo("हिमांशु तैयार है") }
        } catch (e: Exception) {
            handler.post { bolo("आवाज़ वाला मॉडल लोड नहीं हुआ") }
        }
    }

    override fun onResult(h: String?) {
        val t = try { JSONObject(h ?: "").optString("text") } catch (e: Exception) { "" }
        if (t.isBlank()) return
        handler.post { handle(t) }
    }
    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) {}
    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    private fun bolo(t: String) {
        if (!ttsReady) return
        speech?.setPause(true)
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u")
        handler.postDelayed({ speech?.setPause(false) }, 12000)
    }

    private fun handle(t: String) {
        var cmd = t
        var named = false
        for (n in names) if (cmd.contains(n)) { named = true; cmd = cmd.replace(n, " ") }
        cmd = cmd.trim()
        val waiting = System.currentTimeMillis() < waitingCmd
        if (!named && !waiting) return
        if (cmd.isEmpty()) {
            waitingCmd = System.currentTimeMillis() + 10000
            bolo("हाँ बोलो")
            return
        }
        waitingCmd = 0
        act(cmd)
    }

    private fun has(c: String, vararg w: String) = w.any { c.contains(it) }

    private fun act(c: String) {
        when {
            has(c, "टॉर्च", "टोर्च", "टार्च", "तौर्च", "लाइट") ->
                torch(!has(c, "बंद", "बुझा", "ऑफ", "ओफ"))
            has(c, "आवाज़", "आवाज", "वॉल्यूम", "वोल्यूम") -> volume(c)
            has(c, "कैमरा", "कैमेरा") -> {
                open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); bolo("कैमरा खोल रहा हूँ")
            }
            has(c, "टाइमर", "टैमर", "टायमर", "ताइमर") -> timer(c)
            has(c, "डाउनलोड") -> localSong(c)
            has(c, "अगला", "नेक्स्ट") -> nextSong()
            has(c, "गाना", "गाने", "सॉन्ग") && has(c, "बंद", "रोको", "रुको") -> {
                player?.release(); player = null; bolo("ठीक है")
            }
            has(c, "गाना", "गाने", "सॉन्ग", "बजाओ", "चलाओ", "प्ले") -> ytSong(c)
            has(c, "इंस्टा", "व्हाट्स", "वाट्स", "यूट्यूब", "क्रोम", "सेटिंग") -> openApp(c)
            has(c, "कॉल", "फोन", "फ़ोन", "डायल") -> call(c)
            has(c, "बैटरी") -> battery()
            has(c, "दिन", "तारीख", "डेट") -> dateInfo(c)
            has(c, "समय", "टाइम", "बजे") -> timeInfo()
            has(c, "बंद हो जाओ", "बाय", "अलविदा") -> {
                bolo("ठीक है, बाय बाय"); handler.postDelayed({ stopSelf() }, 2500)
            }
            else -> bolo("मुझे समझ नहीं आया")
        }
    }


    private fun volume(c: String) {
        val a = getSystemService(AUDIO_SERVICE) as AudioManager
        val up = has(c, "ज़्यादा", "ज्यादा", "बढ़ाओ", "बढाओ", "तेज", "ऊँची", "फुल")
        val down = has(c, "कम", "घटाओ", "धीमी", "धीरे", "नीची")
        val mute = has(c, "म्यूट", "बंद")
        when {
            mute -> a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            up -> {
                repeat(3) { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                bolo("आवाज़ बढ़ा दी")
            }
            down -> {
                repeat(3) { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                bolo("आवाज़ कम कर दी")
            }
            else -> bolo("आवाज़ बढ़ानी है या कम करनी है?")
        }
    }

    private fun openApp(c: String) {
        val m = mapOf(
            "इंस्टा" to "com.instagram.android", "व्हाट्स" to "com.whatsapp", "वाट्स" to "com.whatsapp",
            "यूट्यूब" to "com.google.android.youtube", "क्रोम" to "com.android.chrome"
        )
        for ((k, pkg) in m) {
            if (c.contains(k)) {
                val i = packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) { open(i); bolo("खोल रहा हूँ") } else bolo("यह ऐप नहीं मिला")
                return
            }
        }
        if (c.contains("सेटिंग")) open(Intent(Settings.ACTION_SETTINGS))
    }

    private val dv = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d",
        'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y",
        'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ृ' to "r", 'ं' to "n", 'ँ' to "n"
    )

    private fun skel(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            val t = dv[ch]
            if (t != null) sb.append(t) else if (ch in 'a'..'z') sb.append(ch)
        }
        return sb.toString().replace('w', 'v').replace('c', 'k').filter { it !in "aeiouy" }
    }

    private fun call(c: String) {
        var q = c
        for (x in listOf("कॉलिंग", "कॉल", "फोन", "फ़ोन", "डायल", "करो", "को", "खोलो", "लगाओ", "मिलाओ")) q = q.replace(x, " ")
        q = q.trim()
        if (q.isEmpty() || checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            open(Intent(Intent.ACTION_DIAL)); bolo("फ़ोन खोल रहा हूँ"); return
        }
        var bestName = ""
        var bestNum = ""
        val qs = skel(q)
        try {
            val ph = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(ph, proj, null, null, null)?.use { cu ->
                while (cu.moveToNext()) {
                    val name = cu.getString(0) ?: continue
                    val sk = skel(name)
                    val hit = name.contains(q, true) || (qs.length >= 2 && (sk.contains(qs) || (sk.length >= 2 && qs.contains(sk))))
                    if (hit) { bestName = name; bestNum = cu.getString(1) ?: ""; break }
                }
            }
        } catch (e: Exception) {}
        if (bestNum.isEmpty()) {
            open(Intent(Intent.ACTION_DIAL)); bolo("$q नाम का कॉन्टैक्ट नहीं मिला"); return
        }
        val uri = Uri.parse("tel:" + Uri.encode(bestNum))
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            open(Intent(Intent.ACTION_CALL, uri)); bolo("$bestName को कॉल कर रहा हूँ")
        } else {
            open(Intent(Intent.ACTION_DIAL, uri)); bolo("$bestName का नंबर खोल रहा हूँ")
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.setTorchMode(id, on)
            bolo(if (on) "टॉर्च चालू" else "टॉर्च बंद")
        } catch (e: Exception) {
            bolo("टॉर्च नहीं चली")
        }
    }

    private fun timer(c: String) {
        var n = 0
        for (x in c.split(" ")) {
            x.toIntOrNull()?.let { n = it }
            nums[x]?.let { n = it }
        }
        if (n == 0) { bolo("कितने मिनट का टाइमर?"); return }
        val sec = has(c, "सेकंड", "सेकेंड")
        val unit = if (sec) "सेकंड" else "मिनट"
        val ms = n * (if (sec) 1000L else 60000L)
        handler.postDelayed({ bolo("$n $unit का टाइमर पूरा हुआ") }, ms)
        bolo("ठीक है, $n $unit का टाइमर लगा दिया")
    }

    private fun timeInfo() {
        val c = Calendar.getInstance()
        var h = c.get(Calendar.HOUR)
        if (h == 0) h = 12
        bolo("अभी $h बजकर ${c.get(Calendar.MINUTE)} मिनट हुए हैं")
    }

    private fun dateInfo(c: String) {
        val cal = Calendar.getInstance()
        val d = listOf("रविवार", "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        if (has(c, "तारीख", "डेट")) {
            bolo("आज ${cal.get(Calendar.DAY_OF_MONTH)} तारीख है, महीना ${cal.get(Calendar.MONTH) + 1}, साल ${cal.get(Calendar.YEAR)}")
        } else {
            bolo("आज $d है")
        }
    }

    private fun battery() {
        val b = getSystemService(BATTERY_SERVICE) as BatteryManager
        bolo("बैटरी ${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} प्रतिशत है")
    }

    private fun open(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            bolo("नहीं खुला")
        }
    }

    private fun ytSong(c: String) {
        var n = c
        for (x in listOf("गाना", "गाने", "सॉन्ग", "बजाओ", "चलाओ", "प्ले", "करो", "एक", "यूट्यूब", "पर")) n = n.replace(x, " ")
        n = n.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) { bolo("गाने का नाम भी बोलो"); return }
        bolo("$n बजा रहा हूँ")
        open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(n))))
    }

    private fun loadSongs() {
        val list = mutableListOf<Pair<String, Uri>>()
        try {
            val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?", arrayOf("Download%"), null
            )?.use { cu ->
                while (cu.moveToNext()) {
                    list.add(Pair(cu.getString(1), Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cu.getLong(0).toString())))
                }
            }
        } catch (e: Exception) {}
        songs = list
    }

    private fun localSong(c: String) {
        loadSongs()
        if (songs.isEmpty()) { bolo("डाउनलोड में कोई गाना नहीं मिला"); return }
        var q = c
        for (x in listOf("डाउनलोडिंग", "डाउनलोड", "गाना", "गाने", "सॉन्ग", "बजाओ", "चलाओ", "प्ले", "करो", "में", "से", "का", "की", "एक")) q = q.replace(x, " ")
        val words = q.trim().split(Regex("\\s+")).filter { it.length > 1 }
        val m = songs.indexOfFirst { s -> words.any { s.first.contains(it, true) } }
        songIdx = if (m >= 0) m else Random().nextInt(songs.size)
        playIdx()
    }

    private fun playIdx() {
        try {
            player?.release()
            val p = MediaPlayer()
            p.setDataSource(this, songs[songIdx].second)
            p.setOnCompletionListener { nextSong() }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            bolo("गाना नहीं चला")
        }
    }

    private fun nextSong() {
        if (songs.isEmpty()) return
        songIdx = (songIdx + 1) % songs.size
        playIdx()
    }

    override fun onDestroy() {
        speech?.stop(); speech?.shutdown()
        tts?.shutdown()
        player?.release()
        wl?.release()
        super.onDestroy()
    }
}
