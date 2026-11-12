package com.himanshu.assistant

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import java.util.Calendar

class Brain(private val c: Context, private val say: (String) -> Unit) {
    private class Song(val title: String, val uri: Uri, val dl: Boolean)

    private var player: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var idx = 0
    private var torchOn = false

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (e: Exception) { false }
    fun release() = stopMusic()

    private fun ok(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun go(i: Intent): Boolean = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    private fun has(t: String, vararg w: String) = w.any { t.contains(Hindi.norm(it)) }

    private val STEMS = listOf("बजा", "चला", "खोल", "लगा", "सुना", "मिला", "कीजि", "खोज", "सर्च", "ढूंढ",
        "दिखा", "ओपन", "स्टार्ट", "शुरू", "डाउनलोड", "चालू").map { Hindi.norm(it) }
    private val WORDS = listOf("को", "से", "पर", "में", "का", "की", "के", "है", "हैं", "ही", "तो", "भी", "जरा",
        "प्लीज", "please", "करो", "कर", "दो", "दे", "करना", "करनी", "करें", "एक", "कोई", "मुझे", "मेरा", "मेरे",
        "मेरी", "वाला", "वाले", "वाली", "जी", "हे", "अरे", "ओ", "सुनो", "ऐप", "एप", "एप्प", "app", "open",
        "play", "बात", "नंबर", "ऑन").map { Hindi.norm(it) }.toSet()
    private val CMD = listOf("फोन", "कॉल", "काल", "call", "गाना", "गाने", "गीत", "सॉन्ग", "सांग", "song",
        "म्यूजिक", "music", "यूट्यूब", "यू", "ट्यूब", "यूटयूब", "यूटूब", "youtube", "गूगल", "google",
        "आखिर", "आखरी", "अंतिम", "अंत", "लास्ट", "last", "नाम", "बंद", "पीछे", "अंक").map { Hindi.norm(it) }.toSet()
    private val LAST = listOf("आखिर", "आखरी", "अंतिम", "अंत", "लास्ट", "last").map { Hindi.norm(it) }

    private fun clean(t: String, cmd: Boolean) = t.split(" ").filter { w ->
        w.isNotBlank() && w !in WORDS && STEMS.none { w.startsWith(it) } && !(cmd && w in CMD)
    }.joinToString(" ")

    /** t = सामान्य किया हुआ वाक्य (नाम हटा हुआ). काम समझ आया तो true. */
    fun handle(t: String): Boolean {
        val tokens = t.split(" ")
        return when {
            has(t, "टॉर्च", "टार्च", "torch", "फ्लैश", "फ्लेश", "बत्ती", "लाइट", "light", "रोशनी", "प्रकाश") -> { torch(t); true }
            has(t, "टाइमर", "टाईमर", "timer") -> timer(t, tokens)
            has(t, "कैमरा", "कैमेरा", "camera", "सेल्फी", "फोटो खींच", "फोटो ले", "फोटो निकाल", "वीडियो बना") -> { camera(t); true }
            has(t, "डायलर", "कॉलिंग", "डायल पैड") -> { go(Intent(Intent.ACTION_DIAL)); say("डायलर खोल रहा हूँ"); true }
            has(t, "आवाज", "वॉल्यूम", "volume", "साउंड", "ध्वनि") -> { volume(t); true }
            has(t, "बैटरी", "चार्ज", "battery") -> { battery(); true }
            has(t, "कितने बजे", "क्या बजा", "समय", "टाइम") -> { time(); true }
            has(t, "कौन सा दिन", "कौनसा दिन", "कौन सा वार", "कौनसा वार") -> { day(); true }
            has(t, "तारीख", "तारिख", "डेट", "तिथि", "दिनांक") -> { date(); true }
            has(t, "नमस्ते", "नमस्कार", "हैलो", "हेलो", "hello", "प्रणाम") -> { say("नमस्ते! बोलिए"); true }
            has(t, "नाम क्या", "कौन हो", "तुम्हारा नाम", "आपका नाम") -> { say("मेरा नाम हिमांशु है"); true }
            has(t, "कैसे हो", "कैसी हो", "कैसे हैं") -> { say("मैं बढ़िया हूँ, आप बताइए"); true }
            isCall(t) -> call(t, tokens)
            has(t, "रोको", "रुको", "बंद करो", "बंद कर", "चुप", "स्टॉप", "stop", "पॉज") -> { stopMusic(); true }
            has(t, "अगला", "नेक्स्ट", "next", "दूसरा गाना", "बदलो") -> { next(); true }
            has(t, "यूट्यूब", "यू ट्यूब", "यूटयूब", "यूटूब", "youtube") && youtubeSearch(t) -> true
            has(t, "गूगल", "google") && has(t, "खोज", "सर्च", "search", "ढूंढ") -> { googleSearch(t); true }
            has(t, "गाना", "गाने", "गीत", "सॉन्ग", "सांग", "song", "म्यूजिक", "music", "संगीत", "बजाओ", "बजा दो", "प्ले") -> { play(t); true }
            has(t, "खोल", "ओपन", "open", "चालू", "चला", "स्टार्ट", "शुरू", "लगाओ") -> openApp(t)
            else -> false
        }
    }

    // ---------- फोन ----------
    private fun isCall(t: String) = has(t, "फोन", "कॉल", "काल", "call", "डायल", "dial") &&
        has(t, "लगा", "करो", "कर दो", "मिला", "बात", "डायल", "dial", "कीजि", "करना", "करनी")

    private fun call(t: String, tokens: List<String>): Boolean {
        val m = tokens.indexOfFirst { tk -> LAST.any { tk.startsWith(it) } }
        if (m >= 0) {
            val d = Hindi.digitsFrom(tokens.drop(m + 1))
            if (d.isNotEmpty()) { byDigits(d); return true }
        }
        val full = Hindi.digitsFrom(tokens, 6)
        if (full.length >= 7) { dial(full, full, true); return true }
        val q = clean(t, true)
        if (q.isBlank()) { go(Intent(Intent.ACTION_DIAL)); say("डायलर खोल रहा हूँ"); return true }
        byName(q)
        return true
    }

    private fun dial(num: String, name: String, direct: Boolean) {
        say(if (direct) "$name को फोन लगा रहा हूँ" else "$name का नंबर डायलर में रख दिया")
        val act = if (direct && ok(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        go(Intent(act, Uri.parse("tel:" + Uri.encode(num))))
    }

    private fun contacts(): List<Pair<String, String>> {
        if (!ok(Manifest.permission.READ_CONTACTS)) { say("पहले संपर्कों की अनुमति दीजिए"); return emptyList() }
        val out = ArrayList<Pair<String, String>>()
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        c.contentResolver.query(p, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cur ->
            while (cur.moveToNext()) out.add((cur.getString(0) ?: "") to (cur.getString(1) ?: ""))
        }
        return out
    }

    private fun callLog(): List<Pair<String, String>> {
        if (!ok(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
            null, null, CallLog.Calls.DATE + " DESC")?.use { cur ->
            var n = 0
            while (cur.moveToNext() && n++ < 300) out.add((cur.getString(0) ?: "") to (cur.getString(1) ?: ""))
        }
        return out
    }

    private fun byName(q: String) {
        val list = contacts()
        val hit = Hindi.best(q, list) { it.first } ?: run { say("$q नाम का नंबर नहीं मिला"); return }
        dial(hit.second, hit.first, true)
    }

    private fun byDigits(d: String) {
        val all = (contacts() + callLog()).filter { it.second.filter { ch -> ch.isDigit() }.endsWith(d) }
            .distinctBy { it.second.filter { ch -> ch.isDigit() } }
        when {
            all.isEmpty() -> say("ऐसा कोई नंबर नहीं मिला")
            all.size == 1 -> dial(all[0].second, all[0].first.ifBlank { all[0].second }, true)
            else -> { say("${all.size} नंबर मिले, पहला डायलर में रख रहा हूँ"); dial(all[0].second, all[0].first.ifBlank { all[0].second }, false) }
        }
    }

    // ---------- गाने ----------
    private fun loadSongs(): List<Song> {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!ok(perm)) return emptyList()
        val out = ArrayList<Song>()
        c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA),
            MediaStore.Audio.Media.DURATION + " > 60000", null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(2) ?: ""
                if (path.contains("WhatsApp") || path.contains("Recordings")) continue
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cur.getLong(0).toString())
                out.add(Song(cur.getString(1) ?: path.substringAfterLast('/'), uri, path.contains("/Download", true)))
            }
        }
        return out
    }

    private fun play(t: String) {
        val q = clean(t, true)
        val songs = loadSongs()
        if (songs.isEmpty()) { say("फोन में गाने नहीं मिले, या अनुमति दीजिए"); return }
        val pick = if (q.isNotBlank()) Hindi.best(q, songs) { it.title } else null
        if (q.isNotBlank() && pick == null) say("वह गाना नहीं मिला, दूसरा बजाता हूँ") else say("ठीक है, गाना बजा रहा हूँ")
        queue = if (pick != null) listOf(pick) + songs.filter { it !== pick }.shuffled()
        else songs.filter { it.dl }.ifEmpty { songs }.shuffled()
        idx = 0
        playIdx()
    }

    private fun playIdx() {
        if (queue.isEmpty()) return
        idx = ((idx % queue.size) + queue.size) % queue.size
        try {
            player?.release()
            player = MediaPlayer().apply {
                setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(c, queue[idx].uri)
                setOnCompletionListener { idx++; playIdx() }
                prepare(); start()
            }
        } catch (e: Exception) { say("गाना नहीं बज पाया") }
    }

    private fun next() { if (queue.isEmpty()) say("अभी कोई गाना नहीं चल रहा") else { idx++; playIdx() } }
    private fun stopMusic() { try { player?.release() } catch (e: Exception) {}; player = null; queue = emptyList() }

    // ---------- ऐप और खोज ----------
    private fun youtubeSearch(t: String): Boolean {
        val q = clean(t, true)
        if (q.isBlank()) return false
        say("यूट्यूब पर खोज रहा हूँ")
        go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        return true
    }

    private fun googleSearch(t: String) {
        say("गूगल पर खोज रहा हूँ")
        go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, clean(t, true)))
    }

    private fun openApp(t: String): Boolean {
        val q = clean(t, false)
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val a = Hindi.best(q, apps) { it.loadLabel(pm).toString() } ?: return false
        val i = pm.getLaunchIntentForPackage(a.activityInfo.packageName) ?: return false
        say("${a.loadLabel(pm)} खोल रहा हूँ")
        go(i)
        return true
    }

    private fun camera(t: String) {
        say("कैमरा खोल रहा हूँ")
        go(Intent(if (has(t, "वीडियो")) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE))
    }

    // ---------- फोन की चीज़ें ----------
    private fun torch(t: String) {
        try {
            val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run { say("टॉर्च नहीं मिली"); return }
            torchOn = when {
                has(t, "बंद", "ऑफ", "बुझा") -> false
                has(t, "जला", "चालू", "ऑन", "खोल") -> true
                else -> !torchOn
            }
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "टॉर्च जल गई" else "टॉर्च बंद कर दी")
        } catch (e: Exception) { say("टॉर्च नहीं चली") }
    }

    private fun volume(t: String) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 5)
        val v = when {
            has(t, "फुल", "पूरी", "मैक्स", "सबसे") -> max
            has(t, "बढ", "ज्यादा", "तेज", "ऊपर") -> cur + step
            has(t, "कम", "घटा", "धीमा", "धीरे", "नीचे") -> cur - step
            has(t, "म्यूट", "बंद") -> 0
            else -> { say("आवाज़ बढ़ानी है या कम करनी है?"); return }
        }.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        say("ठीक है")
    }

    private fun battery() {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        say("बैटरी ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} प्रतिशत है")
    }

    private fun timer(t: String, tokens: List<String>): Boolean {
        val n = Hindi.digitsFrom(tokens, 6).toIntOrNull() ?: run { say("कितने मिनट का टाइमर?"); return true }
        val sec = when { has(t, "घंटे", "घंटा") -> n * 3600; has(t, "सेकंड", "सेकेंड") -> n; else -> n * 60 }
        go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("टाइमर लगा दिया")
        return true
    }

    private fun time() {
        val cal = Calendar.getInstance()
        var h = cal.get(Calendar.HOUR); if (h == 0) h = 12
        val m = cal.get(Calendar.MINUTE)
        say(if (m == 0) "अभी $h बजे हैं" else "अभी $h बजकर $m मिनट हुए हैं")
    }

    private val DAYS = listOf("रविवार", "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार")
    private val MONTHS = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")
    private fun day() = say("आज " + DAYS[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1] + " है")
    private fun date() {
        val cal = Calendar.getInstance()
        say("आज ${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)} है, " +
            DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1])
    }
}
