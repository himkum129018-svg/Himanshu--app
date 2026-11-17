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
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

class Brain(private val c: Context, private val say: (String) -> Unit) {
    private class Song(val title: String, val uri: Uri, val dl: Boolean)

    @Volatile private var player: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var idx = 0
    private var torchOn = false
    private var musicVol = 0.6f

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (e: Exception) { false }
    fun release() = stopMusic()
    fun stopNow() = stopMusic()

    /** हिमांशु बोल रहा हो तब गाने की आवाज़ धीमी कर देता है. */
    fun duck(on: Boolean) {
        musicVol = if (on) 0.15f else 0.6f
        try { player?.setVolume(musicVol, musicVol) } catch (e: Exception) {}
    }

    fun isStop(t: String): Boolean = has(t, "बंद", "बन्द", "रोको", "रोक दो", "रुको", "रुक", "चुप", "स्टॉप", "स्टाप", "stop", "पॉज", "बस करो", "बस कर") &&
        !has(t, "टॉर्च", "टार्च", "लाइट", "बत्ती", "आवाज", "वॉल्यूम", "फ्लैश")

    private fun ok(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun go(i: Intent): Boolean = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    private fun has(t: String, vararg w: String) = w.any { t.contains(Hindi.norm(it)) }

    private val STEMS = listOf("बजा", "चला", "खोल", "लगा", "सुना", "मिला", "कीजि", "खोज", "सर्च", "ढूंढ", "search",
        "दिखा", "ओपन", "स्टार्ट", "शुरू", "डाउनलोड", "download", "चालू").map { Hindi.norm(it) }
    private val WORDS = listOf("को", "से", "पर", "में", "का", "की", "के", "है", "हैं", "ही", "तो", "भी", "जरा",
        "प्लीज", "please", "करो", "कर", "दो", "दे", "करना", "करनी", "करें", "एक", "कोई", "मुझे", "मेरा", "मेरे",
        "मेरी", "वाला", "वाले", "वाली", "जी", "हे", "अरे", "ओ", "सुनो", "ऐप", "एप", "एप्प", "app", "open",
        "play", "the", "द", "बात", "नंबर", "ऑन").map { Hindi.norm(it) }.toSet()
    private val CMD = listOf("फोन", "कॉल", "काल", "call", "गाना", "गाने", "गीत", "सॉन्ग", "सांग", "song",
        "म्यूजिक", "music", "यूट्यूब", "यू", "ट्यूब", "यूटयूब", "यूटूब", "youtube", "गूगल", "google",
        "आखिर", "आखरी", "अंतिम", "अंत", "लास्ट", "last", "नाम", "बंद", "पीछे", "अंक").map { Hindi.norm(it) }.toSet()
    private val LAST = listOf("आखिर", "आखरी", "अंतिम", "अंत", "लास्ट", "last").map { Hindi.norm(it) }
    /** खोजने के वाक्य से सिर्फ़ ये "मंच वाले" शब्द हटते हैं, बाकी शब्द (जैसे "नाम", "अंक") खोज में रहते हैं. */
    private val PLATFORM = listOf("गूगल", "google", "यूट्यूब", "यू", "ट्यूब", "यूटयूब", "यूटूब", "youtube", "इंटरनेट",
        "गाना", "गाने", "गीत", "सॉन्ग", "सांग", "song", "म्यूजिक", "music").map { Hindi.norm(it) }.toSet()

    private fun clean(t: String, cmd: Boolean) = t.split(" ").filter { w ->
        w.isNotBlank() && w !in WORDS && STEMS.none { w.startsWith(it) } && !(cmd && w in CMD)
    }.joinToString(" ")

    private fun cleanSearch(t: String) = t.split(" ").filter { w ->
        w.isNotBlank() && w !in WORDS && STEMS.none { w.startsWith(it) } && w !in PLATFORM
    }.joinToString(" ")

    // ---------- "हाँ / नहीं" पूछना (गलत आदमी को फोन न लग जाए) ----------
    private var pendAct: (() -> Unit)? = null
    private var pendUntil = 0L
    private val YES = listOf("हां", "हाँ", "हा", "जी", "ठीक", "सही", "लगाओ", "करो", "yes", "ओके", "ok").map { Hindi.norm(it) }.toSet()
    private val NO = listOf("नहीं", "नही", "ना", "मत", "न", "no", "रहने", "छोड़ो", "छोड", "cancel").map { Hindi.norm(it) }.toSet()

    private fun askConfirm(q: String, act: () -> Unit) {
        pendAct = act
        pendUntil = System.currentTimeMillis() + 20000
        say(q)
    }

    /** "हाँ/नहीं" का जवाब था तो true (और काम निपटा देता है). */
    fun confirmReply(t: String): Boolean {
        val a = pendAct ?: return false
        if (System.currentTimeMillis() > pendUntil) { pendAct = null; return false }
        val tk = t.split(" ").filter { it.isNotBlank() }
        if (tk.size > 4) return false
        return when {
            tk.any { it in NO } -> { pendAct = null; say("ठीक है, रहने दिया"); true }
            tk.any { it in YES } -> { pendAct = null; a(); true }
            else -> false
        }
    }

    private fun isTime(t: String) = has(t, "कितने बजे", "क्या बजा", "समय") || (has(t, "टाइम") && !has(t, "टाइमर", "टाईमर"))

    /** छोटी जानकारी वाले सवाल (समय, तारीख, बैटरी, जोड़-घटाना, नोट, बातचीत): फोन में ही, तुरंत. */
    fun handleInfo(t: String): Boolean {
        val tokens = t.split(" ")
        return when {
            has(t, "क्या याद", "नोट सुनाओ", "मेरे नोट") -> { recall(); true }
            calc(t, tokens) -> true
            has(t, "बैटरी", "चार्ज", "battery") -> { battery(); true }
            isTime(t) -> { time(); true }
            has(t, "कौन सा दिन", "कौनसा दिन", "कौन सा वार", "कौनसा वार") -> { day(); true }
            has(t, "तारीख", "तारिख", "डेट", "तिथि", "दिनांक") -> { date(); true }
            chat(t) -> true
            else -> false
        }
    }

    private fun chat(t: String): Boolean = when {
        has(t, "नमस्ते", "नमस्कार", "हैलो", "हेलो", "hello", "प्रणाम") -> { say("नमस्ते! बोलिए"); true }
        has(t, "किसने बनाया", "किसने तुम्हें", "तुम्हें किसने", "तुम्हे किसने", "आपको किसने", "बनाया किसने", "बनाने वाला", "कौन बनाया", "who made", "who created") -> { say("मुझे हिमांशु ने बनाया है, अपने निजी इस्तेमाल के लिए"); true }
        has(t, "नाम क्या", "कौन हो", "तुम्हारा नाम", "आपका नाम") -> { say("मैं हिमांशु हूँ, हिमांशु का निजी असिस्टेंट"); true }
        has(t, "कैसे हो", "कैसी हो", "कैसे हैं") -> { say("मैं बढ़िया हूँ, आप बताइए"); true }
        has(t, "क्या कर सकते", "क्या कर सकती", "क्या क्या कर", "मदद", "help") -> { help(); true }
        else -> false
    }

    private fun help() {
        say("मैं गाना बजाना, फोन लगाना, ऐप खोलना, टॉर्च जलाना, अलार्म और टाइमर लगाना, याद रखना, जोड़ घटाना, यूट्यूब और गूगल पर खोजना कर सकता हूँ। बाकी सवालों के जवाब AI से लेता हूँ")
    }

    /** t = सामान्य किया हुआ वाक्य (नाम हटा हुआ). काम समझ आया तो true. */
    fun handle(t: String): Boolean {
        val tokens = t.split(" ")
        return when {
            has(t, "क्या याद", "नोट सुनाओ", "मेरे नोट") -> { recall(); true }
            has(t, "सब भूल जाओ", "नोट मिटाओ") -> { saveNotes(emptyList()); say("सब मिटा दिया"); true }
            has(t, "याद रखो", "याद रख") -> { remember(t); true }
            has(t, "अलार्म", "alarm") -> alarm(t, tokens)
            has(t, "टॉर्च", "टार्च", "torch", "फ्लैश", "फ्लेश", "बत्ती", "लाइट", "light", "रोशनी", "प्रकाश") -> { torch(t); true }
            has(t, "टाइमर", "टाईमर", "timer") -> timer(t, tokens)
            has(t, "कैमरा", "कैमेरा", "camera", "सेल्फी", "फोटो खींच", "फोटो ले", "फोटो निकाल", "वीडियो बना") -> { camera(t); true }
            has(t, "डायलर", "कॉलिंग", "डायल पैड") -> { go(Intent(Intent.ACTION_DIAL)); say("डायलर खोल रहा हूँ"); true }
            has(t, "आवाज", "वॉल्यूम", "volume", "साउंड", "ध्वनि") -> { volume(t); true }
            calc(t, tokens) -> true
            has(t, "बैटरी", "चार्ज", "battery") -> { battery(); true }
            isTime(t) -> { time(); true }
            has(t, "कौन सा दिन", "कौनसा दिन", "कौन सा वार", "कौनसा वार") -> { day(); true }
            has(t, "तारीख", "तारिख", "डेट", "तिथि", "दिनांक") -> { date(); true }
            chat(t) -> true
            has(t, "वाईफाई", "वाई फाई", "वायफाई", "wifi", "wi fi") -> { go(Intent(Settings.ACTION_WIFI_SETTINGS)); say("वाईफाई की सेटिंग खोल रहा हूँ"); true }
            has(t, "ब्लूटूथ", "bluetooth") -> { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); say("ब्लूटूथ की सेटिंग खोल रहा हूँ"); true }
            has(t, "सेटिंग", "settings") -> { go(Intent(Settings.ACTION_SETTINGS)); say("सेटिंग खोल रहा हूँ"); true }
            openKnown(t) -> true
            isCall(t) -> call(t, tokens)
            has(t, "रोको", "रुको", "बंद करो", "बंद कर", "चुप", "स्टॉप", "stop", "पॉज") -> {
                if (isPlaying()) { stopMusic(); say("गाना रोक दिया") } else say("अभी कुछ बज नहीं रहा")
                true
            }
            has(t, "पिछला", "पीछे वाला", "previous", "प्रीवियस") -> { prev(); true }
            has(t, "अगला", "नेक्स्ट", "next", "दूसरा गाना", "बदलो") -> { next(); true }
            has(t, "यूट्यूब", "यू ट्यूब", "यूटयूब", "यूटूब", "youtube") && youtubeSearch(t) -> true
            has(t, "गूगल", "google") && has(t, "खोज", "सर्च", "search", "ढूंढ") -> { webSearch(cleanSearch(t)); true }
            has(t, "खोज", "सर्च", "search", "ढूंढ") && cleanSearch(t).isNotBlank() -> { webSearch(cleanSearch(t)); true }
            has(t, "गाना", "गाने", "गीत", "सॉन्ग", "सांग", "सोंग", "सॉंग", "song", "म्यूजिक", "music", "संगीत", "बजाओ", "बजा दो", "प्ले", "play") -> { play(t); true }
            has(t, "खोल", "ओपन", "open", "चालू", "चला", "स्टार्ट", "शुरू", "लगाओ") -> openApp(t)
            else -> false
        }
    }

    // ---------- जोड़-घटाना-गुणा-भाग (फोन में ही) ----------
    private val PLUS = listOf("जमा", "प्लस", "plus", "जोड़ो", "जोड़").map { Hindi.norm(it) }.toSet()
    private val MINUS = listOf("घटा", "घटाओ", "माइनस", "minus", "ऋण").map { Hindi.norm(it) }.toSet()
    private val TIMES = listOf("गुणा", "गुना", "गुणे", "टाइम्स", "times", "into", "इनटू").map { Hindi.norm(it) }.toSet()
    private val DIV = listOf("भाग", "बटा", "divided", "by").map { Hindi.norm(it) }.toSet()
    private val AND = Hindi.norm("और")

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

    private fun calc(t: String, tokens: List<String>): Boolean {
        val nums = ArrayList<Double>()
        var op = ' '
        for (tk in tokens) {
            val n: Double? = when {
                tk.isNotEmpty() && tk.length <= 9 && tk.all { it.isDigit() } -> tk.toDouble()
                Hindi.numWord(tk) >= 0 -> Hindi.numWord(tk).toDouble()
                else -> null
            }
            if (n != null) { nums.add(n); continue }
            val o = when {
                tk in PLUS -> '+'
                tk in MINUS -> '-'
                tk in TIMES -> '*'
                tk in DIV -> '/'
                tk == AND -> '&'
                else -> ' '
            }
            if (o != ' ' && nums.size == 1 && op == ' ') op = o
        }
        if (nums.size != 2 || op == ' ') return false
        if (op == '&') {
            if (!has(t, "कितने", "कितना", "होते", "होता", "बराबर", "योग")) return false
            op = '+'
        }
        val a = nums[0]
        val b = nums[1]
        val word = when (op) { '+' -> "जमा"; '-' -> "घटा"; '*' -> "गुणा"; else -> "भाग" }
        val r = when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            else -> { if (b == 0.0) { say("शून्य से भाग नहीं हो सकता"); return true }; a / b }
        }
        say(fmt(a) + " " + word + " " + fmt(b) + " बराबर " + fmt(r))
        return true
    }

    // ---------- निजी नोट (सिर्फ़ फोन में, कहीं नहीं जाते) ----------
    private val FILLER = listOf("कि", "ये", "यह").map { Hindi.norm(it) }.toSet()
    private val LIGHT = listOf("लो", "ले", "लेना", "लीजिए", "लीजिये").map { Hindi.norm(it) }.toSet()
    private fun notes(): MutableList<String> =
        (c.getSharedPreferences("him", Context.MODE_PRIVATE).getString("notes", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
    private fun saveNotes(l: List<String>) { c.getSharedPreferences("him", Context.MODE_PRIVATE).edit().putString("notes", l.joinToString("\n")).apply() }

    fun saveNote(body: String) {
        val b = body.trim()
        if (b.isBlank()) { say("क्या याद रखूँ?"); return }
        val l = notes(); l.add(b); saveNotes(l)
        say("ठीक है, याद रख लिया")
    }

    private fun remember(t: String) {
        val tk = t.split(" ").filter { it.isNotBlank() }
        val i = tk.indexOfFirst { it.startsWith("याद") }
        var rest = if (i >= 0) tk.drop(i + 1) else tk
        rest = rest.dropWhile { it.startsWith("रख") || it in LIGHT }.dropWhile { it in FILLER }
        saveNote(rest.joinToString(" "))
    }

    private fun recall() {
        val l = notes()
        if (l.isEmpty()) say("अभी तुमने मुझे कुछ याद रखने को नहीं कहा") else say("तुमने ये बताया है। " + l.joinToString("। "))
    }

    // ---------- अलार्म / टाइमर / समय ----------
    private fun partOfDay(h: Int) = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }

    private fun spoken(h24: Int, m: Int): String {
        var h = h24 % 12
        if (h == 0) h = 12
        return partOfDay(h24) + " " + h + (if (m == 0) " बजे" else " बजकर $m मिनट")
    }

    private fun numAt(tokens: List<String>, i: Int): Int? {
        if (i !in tokens.indices) return null
        val tk = tokens[i]
        if (tk.isNotEmpty() && tk.length <= 3 && tk.all { it.isDigit() }) return tk.toInt()
        val n = Hindi.numWord(tk)
        return if (n >= 0) n else null
    }

    fun alarmAt(h: Int, m: Int) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (go(i)) say("अलार्म लगा दिया, " + spoken(h, m)) else say("अलार्म वाला ऐप नहीं खुल पाया")
    }

    private fun alarm(t: String, tokens: List<String>): Boolean {
        val bi = tokens.indexOfFirst { it.startsWith("बज") }
        var h: Int? = if (bi > 0) numAt(tokens, bi - 1) else null
        if (h == null) h = tokens.indices.firstNotNullOfOrNull { numAt(tokens, it) }
        if (h == null) { say("कितने बजे का अलार्म?"); return true }
        var m = 0
        val mi = tokens.indexOfFirst { it.startsWith("मिनट") }
        if (bi >= 0 && mi > bi) numAt(tokens, mi - 1)?.let { m = it }
        if (has(t, "साढ़े", "साढे")) m = 30
        if (has(t, "सवा")) m = 15
        if (has(t, "पौने")) { m = 45; h = if (h == 1) 12 else h - 1 }
        val night = has(t, "रात")
        val pm = has(t, "शाम", "दोपहर") || night
        val am = has(t, "सुबह", "सवेरे", "भोर")
        var hh: Int = h
        if (hh > 23 || m > 59) { say("समय समझ नहीं आया, फिर से बोलिए"); return true }
        if (hh in 1..11) {
            if (pm) hh += 12
            else if (!am) {
                // सुबह/शाम न बोला हो तो वह समय चुनो जो अभी से सबसे पहले आए
                val now = Calendar.getInstance()
                val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                if (hh * 60 + m <= nowMin && (hh + 12) * 60 + m > nowMin) hh += 12
            }
        } else if (hh == 12 && (am || night)) hh = 0
        alarmAt(hh, m)
        return true
    }

    private fun timer(t: String, tokens: List<String>): Boolean {
        val hour = has(t, "घंटे", "घंटा")
        val secWord = has(t, "सेकंड", "सेकेंड")
        val (sec, label) = when {
            has(t, "डेढ़", "डेढ") -> if (hour) Pair(5400, "डेढ़ घंटे") else Pair(90, "डेढ़ मिनट")
            has(t, "आधे", "आधा") -> if (hour) Pair(1800, "आधे घंटे") else Pair(30, "आधे मिनट")
            else -> {
                val n = Hindi.digitsFrom(tokens, 6).toIntOrNull() ?: run { say("कितने मिनट का टाइमर?"); return true }
                when {
                    hour -> Pair(n * 3600, "$n घंटे")
                    secWord -> Pair(n, "$n सेकंड")
                    else -> Pair(n * 60, "$n मिनट")
                }
            }
        }
        if (sec <= 0 || sec > 86400) { say("टाइमर का समय समझ नहीं आया"); return true }
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (go(i)) say("$label का टाइमर लगा दिया") else say("टाइमर वाला ऐप नहीं खुल पाया")
        return true
    }

    private fun time() {
        val cal = Calendar.getInstance()
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        var h = h24 % 12
        if (h == 0) h = 12
        say(if (m == 0) "अभी " + partOfDay(h24) + " के $h बजे हैं" else "अभी " + partOfDay(h24) + " के $h बजकर $m मिनट हुए हैं")
    }

    private val DAYS = listOf("रविवार", "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार")
    private val MONTHS = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")
    private fun day() = say("आज " + DAYS[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1] + " है")
    private fun date() {
        val cal = Calendar.getInstance()
        say("आज ${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)} है, " +
            DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1])
    }

    // ---------- फोन ----------
    private fun isCall(t: String) = has(t, "कॉल", "काल", "call", "डायल", "dial") ||
        (has(t, "फोन") && has(t, "लगा", "करो", "कर दो", "मिला", "बात", "कीजि", "करना", "करनी"))

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
        val act = if (direct && ok(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        say(if (act == Intent.ACTION_CALL) "$name को फोन लगा रहा हूँ" else "$name का नंबर डायलर में रख दिया")
        go(Intent(act, Uri.parse("tel:" + Uri.encode(num))))
    }

    private fun contacts(silent: Boolean = false): List<Pair<String, String>> {
        if (!ok(Manifest.permission.READ_CONTACTS)) { if (!silent) say("पहले संपर्कों की अनुमति दीजिए"); return emptyList() }
        val out = ArrayList<Pair<String, String>>()
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        try {
            c.contentResolver.query(p, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cur ->
                while (cur.moveToNext()) out.add((cur.getString(0) ?: "") to (cur.getString(1) ?: ""))
            }
        } catch (e: Exception) {}
        return out
    }

    private fun callLog(): List<Pair<String, String>> {
        if (!ok(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                null, null, CallLog.Calls.DATE + " DESC")?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n++ < 300) out.add((cur.getString(0) ?: "") to (cur.getString(1) ?: ""))
            }
        } catch (e: Exception) {}
        return out
    }

    private val KIN = listOf(
        listOf("मम्मी", "मॉम", "मां", "मम्मा", "मा", "mom", "mummy", "mother", "maa", "mama", "माता", "mum"),
        listOf("पापा", "पप्पा", "डैड", "dad", "papa", "पिता", "father", "पिताजी", "daddy"))

    private fun byName(q: String) {
        if (!ok(Manifest.permission.READ_CONTACTS)) { say("पहले संपर्कों की अनुमति दीजिए"); return }
        val list = contacts(true)
        val qs = Hindi.skel(q)
        val grp = KIN.firstOrNull { g -> g.any { Hindi.skel(it) == qs } }
        val kin = if (grp != null) {
            val ns = grp.map { Hindi.norm(it) }.toSet()
            list.firstOrNull { cn -> Hindi.norm(cn.first).split(" ").any { it in ns } }
        } else null
        if (kin != null) { dial(kin.second, kin.first, true); return }
        val ranked = Hindi.rank(q, list) { it.first }
        if (ranked.isEmpty()) { say("$q नाम का नंबर नहीं मिला"); return }
        val hit = ranked[0].first
        // एकदम सही मिलान सिर्फ़ एक ही आदमी का हो तो सीधा लगाओ; वरना (कई "अमित", या नाम थोड़ा अलग) पहले पूछो
        val exact = ranked.filter { it.second == 0 }.distinctBy { it.first.second.filter { ch -> ch.isDigit() }.takeLast(10) }
        if (ranked[0].second == 0 && exact.size == 1) dial(hit.second, hit.first, true)
        else askConfirm("क्या " + hit.first + " को फोन लगाऊँ? हाँ या नहीं बोलिए") { dial(hit.second, hit.first, true) }
    }

    private fun byDigits(d: String) {
        val all = (contacts(true) + callLog()).filter { it.second.filter { ch -> ch.isDigit() }.endsWith(d) }
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
        try {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA),
                MediaStore.Audio.Media.DURATION + " > 60000", null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val path = cur.getString(2) ?: ""
                    if (path.contains("WhatsApp", true) || path.contains("Recordings", true) || path.contains("/Ringtones", true) ||
                        path.contains("/Notifications", true) || path.contains("/Alarms", true)) continue
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cur.getLong(0).toString())
                    out.add(Song(cur.getString(1) ?: path.substringAfterLast('/'), uri, path.contains("/Download", true)))
                }
            }
        } catch (e: Exception) {}
        return out
    }

    private fun play(t: String) {
        val q = clean(t, true)
        val all = loadSongs()
        if (all.isEmpty()) { say("फोन में गाने नहीं मिले, या अनुमति दीजिए"); return }
        val wantDl = has(t, "डाउनलोड", "download")
        val dls = all.filter { it.dl }
        val songs = if (wantDl && dls.isNotEmpty()) dls else all
        val pick = if (q.isNotBlank()) Hindi.best(q, songs) { it.title } else null
        if (wantDl && dls.isEmpty()) say("डाउनलोड फोल्डर में गाने नहीं मिले, फोन के बाकी गाने बजाता हूँ")
        else if (q.isNotBlank() && pick == null) say("वह गाना नहीं मिला, दूसरा बजाता हूँ")
        else say(if (wantDl) "डाउनलोड वाला गाना बजा रहा हूँ" else "ठीक है, गाना बजा रहा हूँ")
        queue = if (pick != null) listOf(pick) + songs.filter { it !== pick }.shuffled() else songs.shuffled()
        idx = 0
        playIdx()
    }

    private fun playIdx(tries: Int = 0) {
        if (queue.isEmpty()) return
        idx = ((idx % queue.size) + queue.size) % queue.size
        try {
            player?.release()
            player = MediaPlayer().apply {
                setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(c, queue[idx].uri)
                setVolume(musicVol, musicVol)
                setOnCompletionListener { idx++; playIdx() }
                setOnErrorListener { _, _, _ ->
                    idx++
                    if (tries < 5) playIdx(tries + 1) else { stopMusic(); say("गाना नहीं बज पाया") }
                    true
                }
                prepare(); start()
            }
        } catch (e: Exception) {
            if (tries < 5 && queue.size > 1) { idx++; playIdx(tries + 1) } else { stopMusic(); say("गाना नहीं बज पाया") }
        }
    }

    private fun next() { if (queue.isEmpty()) play("") else { idx++; playIdx() } }
    private fun prev() { if (queue.isEmpty()) play("") else { idx--; playIdx() } }
    private fun stopMusic() { try { player?.release() } catch (e: Exception) {}; player = null; queue = emptyList() }

    // ---------- ऐप और खोज ----------
    private class AppDef(val label: String, val words: List<String>, val pkg: String, val web: String?)
    private val KNOWN = listOf(
        AppDef("यूट्यूब", listOf("यूट्यूब", "यू ट्यूब", "यूटयूब", "यूटूब", "यूतूब", "यूट्यूप", "ऊट्यूब", "ट्यूब", "youtube", "you tube"), "com.google.android.youtube", "https://www.youtube.com"),
        AppDef("गूगल", listOf("गूगल", "गुगल", "google"), "com.google.android.googlequicksearchbox", "https://www.google.com"),
        AppDef("इंस्टाग्राम", listOf("इंस्टाग्राम", "इंस्टा", "instagram", "insta"), "com.instagram.android", "https://www.instagram.com"),
        AppDef("फेसबुक", listOf("फेसबुक", "फेस बुक", "facebook"), "com.facebook.katana", "https://www.facebook.com"),
        AppDef("व्हाट्सएप", listOf("व्हाट्सएप", "व्हाट्सऐप", "वाट्सएप", "वाट्सऐप", "व्हाट्स ऐप", "whatsapp"), "com.whatsapp", null),
        AppDef("क्रोम", listOf("क्रोम", "chrome"), "com.android.chrome", "https://www.google.com"),
        AppDef("स्नैपचैट", listOf("स्नैपचैट", "snapchat"), "com.snapchat.android", null),
        AppDef("टेलीग्राम", listOf("टेलीग्राम", "telegram"), "org.telegram.messenger", null)
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** "यूट्यूब", "यूट्यूब खोलो", "यूट्यूब गूगल इंस्टाग्राम खोलो": जितने ऐप के नाम बोले, बस वही (और सिर्फ़ वही) खुलते हैं. */
    private fun openKnown(t: String): Boolean {
        // खोजना/गाना बजाना हो तो ये सीधा ऐप खोलना नहीं है, आगे के नियम देखेंगे
        if (has(t, "खोज", "सर्च", "search", "ढूंढ", "बजा", "गाना", "गाने", "गीत", "song", "play", "प्ले", "music", "म्यूजिक", "संगीत")) return false
        val hits = KNOWN.mapNotNull { a ->
            val i = a.words.map { t.indexOf(Hindi.norm(it)) }.filter { it >= 0 }.minOrNull()
            if (i == null) null else i to a
        }.sortedBy { it.first }.map { it.second }
        if (hits.isEmpty()) return false
        say(hits.joinToString(", ") { it.label } + " खोल रहा हूँ")
        hits.forEachIndexed { n, a -> mainHandler.postDelayed({ launchApp(a) }, n * 800L) }
        return true
    }

    private fun launchApp(a: AppDef) {
        val li = c.packageManager.getLaunchIntentForPackage(a.pkg)
        if (li != null) { go(li); return }
        if (a.web != null) { go(Intent(Intent.ACTION_VIEW, Uri.parse(a.web))); return }
        say(a.label + " फोन में नहीं मिला")
    }

    private fun youtubeSearch(t: String): Boolean {
        val q = cleanSearch(t)
        if (q.isBlank()) {
            say("यूट्यूब खोल रहा हूँ")
            val yi = c.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (yi != null) go(yi) else go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
            return true
        }
        say("यूट्यूब पर खोज रहा हूँ")
        val inApp = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q)
        if (!go(inApp)) go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        return true
    }

    fun webSearch(q: String) {
        if (q.isBlank()) {
            say("गूगल खोल रहा हूँ")
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            return
        }
        say("गूगल पर खोज रहा हूँ")
        val ok1 = go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        if (!ok1) go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    private fun openApp(t: String): Boolean {
        val q = clean(t, false)
        if (q.isBlank()) return false
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
        say("आवाज़ " + (v * 100 / max) + " प्रतिशत")
    }

    private fun battery() {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        say("बैटरी $p प्रतिशत है" + (if (bm.isCharging) ", और चार्ज हो रही है" else ""))
    }
}
