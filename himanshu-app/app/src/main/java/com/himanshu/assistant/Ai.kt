package com.himanshu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** जो आदेश नियमों से न समझ आए, उसे Google के Gemini AI से समझवाता है. */
object Ai {
    private const val SYS = """तुम "हिमांशु" हो, हिमांशु (असली इंसान) के फ़ोन के निजी असिस्टेंट। तुम्हें हिमांशु ने खुद, अपने निजी इस्तेमाल के लिए बनाया है। कोई पूछे कि तुम्हें किसने बनाया, तो कहो: "मुझे हिमांशु ने बनाया है, अपने निजी इस्तेमाल के लिए।" खुद को कभी भाषा मॉडल या किसी कंपनी का AI मत बताओ।
बोलने का ढंग: एक समझदार दोस्त की तरह, सहज बोलचाल की हिंदी, छोटे वाक्य (एक या दो), क्योंकि जवाब आवाज़ में सुनाया जाएगा। इसलिए जवाब में कोई चिह्न, तारा, लिस्ट या इमोजी मत रखो, सिर्फ़ बोलने लायक साफ़ शब्द। हर बार "माफ़ कीजिए" या "क्या मैं और मदद कर सकता हूँ" मत कहो।
उपयोगकर्ता हिंदी, अंग्रेज़ी या दोनों मिलाकर बोलता है। बात आवाज़ से लिखी गई है, इसलिए शब्द बिगड़े हो सकते हैं (अंग्रेज़ी शब्द देवनागरी में भी); मतलब का सही अंदाज़ा लगाओ। पिछली बातों को ध्यान में रखो।
जो जानकारी तुम्हारे पास नहीं है (जैसे उसके इंस्टाग्राम के फॉलोअर्स, आज का ताज़ा मौसम, ताज़ा ख़बरें, आज का स्कोर), उसे बनाकर मत बताओ। सीधे कहो कि यह तुम्हें अभी दिख नहीं रहा, और google_search action दो ताकि गूगल खुल जाए।
ध्यान से सुनो और जो बोला गया वही करो: "डाउनलोड" बोला तो play_download, "यूट्यूब" बोला तो play_youtube, "दूसरा/अगला/बदलो" बोला तो next_song, "पिछला" बोला तो prev_song, "रोको/बंद" बोला तो stop_music। अपनी तरफ़ से कोई दूसरा विकल्प मत चुनो। इंसान की तरह पूरी बात का मतलब समझो, सिर्फ़ एक-दो शब्द पकड़कर मत चलो।
सिर्फ़ एक JSON दो, और कुछ नहीं: {"action":"...","arg":"...","reply":"..."}
action इनमें से एक: torch_on, torch_off, play_music (फोन में रखा गाना; arg = नाम या खाली), play_download (सिर्फ़ Download फोल्डर वाला गाना; arg = नाम या खाली), play_youtube (यूट्यूब से गाना; arg = नाम या खाली), stop_music, next_song (दूसरा/अगला गाना), prev_song, youtube_search (arg), google_search (arg), open_app (arg = ऐप का नाम), call_name (arg = नाम), call_digits (arg = नंबर के आख़िरी अंक), volume_up, volume_down, volume_max, time, date, battery, camera, timer (arg = मिनट की संख्या), alarm (arg = 24 घंटे वाला समय HH:MM, जैसे 07:30), note_save (arg = जो याद रखना है), wifi, settings, none.
reply = बोलने के लिए छोटा हिंदी वाक्य। साधारण सवाल या बातचीत हो तो action none रखो और जवाब reply में दो।"""

    @Volatile var lastError = ""
    private val MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-flash-lite-latest")
    private const val TOTAL_MS = 22000L

    private fun sys(): String {
        val now = try { SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("hi", "IN")).format(Date()) } catch (e: Exception) { "" }
        return if (now.isBlank()) SYS else SYS + "\nअभी की तारीख़ और समय: " + now + "।"
    }

    /** निजी सुरक्षा: 4+ अंक या बोले हुए 4+ अंक (फोन नंबर जैसे) Google को नहीं जाते. */
    fun scrub(t: String): String {
        val toks = Hindi.norm(t).split(" ")
        val out = ArrayList<String>()
        var run = ArrayList<String>()
        fun isNum(w: String) = w.isNotEmpty() && (w.all { it.isDigit() } || Hindi.isNumWord(w))
        fun flush() {
            val digitsOnly = run.isNotEmpty() && run.all { w -> w.all { c -> c.isDigit() } }
            if (run.size >= 4 || (digitsOnly && run.joinToString("").length >= 4)) out.add("…") else out.addAll(run)
            run = ArrayList()
        }
        for (w in toks) { if (isNum(w)) run.add(w) else { flush(); out.add(w) } }
        flush()
        return out.joinToString(" ")
    }

    private fun isAuthError(e: String) = e.contains("HTTP 401") || e.contains("API key not valid") || e.contains("API_KEY_INVALID")

    fun ask(key0: String, text0: String, history0: List<Pair<String, String>>): JSONObject? {
        lastError = ""
        val key = key0.filter { !it.isWhitespace() }
        val text = scrub(text0)
        val history = history0.map { scrub(it.first) to it.second }
        val deadline = System.currentTimeMillis() + TOTAL_MS
        for (m in MODELS) {
            // पहले सोच बंद करके (तेज़), अगर मॉडल इसे न माने (HTTP 400) तो सामान्य तरीके से
            for (fast in listOf(true, false)) {
                if (System.currentTimeMillis() > deadline) { if (lastError.isBlank()) lastError = "AI ने समय पर जवाब नहीं दिया"; return null }
                val r = try { call(key, m, text, history, fast) } catch (e: Exception) { lastError = m + ": " + (e.message ?: "net error"); null }
                if (r != null) return r
                if (isAuthError(lastError)) return null
                if (!lastError.contains("HTTP 400") && !lastError.contains("empty")) break
            }
        }
        return null
    }

    private fun part(role: String, t: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun call(key: String, model: String, text: String, history: List<Pair<String, String>>, fast: Boolean): JSONObject? {
        val contents = JSONArray()
        for ((u, a) in history) { contents.put(part("user", u)); contents.put(part("model", a)) }
        contents.put(part("user", text))
        val gen = JSONObject().put("maxOutputTokens", if (fast) 500 else 1500).put("responseMimeType", "application/json")
        if (fast) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys()))))
            .put("contents", contents)
            .put("generationConfig", gen)
        val con = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 6000
            con.readTimeout = if (fast) 8000 else 14000
            con.doOutput = true
            con.setRequestProperty("x-goog-api-key", key)
            con.setRequestProperty("content-type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (con.responseCode != 200) {
                lastError = model + ": HTTP " + con.responseCode + " " + (con.errorStream?.bufferedReader()?.readText()?.take(140) ?: "")
                return null
            }
            val resp = con.inputStream.bufferedReader().readText()
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            val t = sb.toString()
            val s = t.indexOf('{')
            val e = t.lastIndexOf('}')
            if (s < 0 || e < s) { lastError = model + ": empty answer"; return null }
            return JSONObject(t.substring(s, e + 1))
        } finally {
            try { con.disconnect() } catch (e: Exception) {}
        }
    }

    /** AI की बताई क्रिया को वही हिंदी आदेश बना देता है जो फोन के नियम समझते हैं. */
    fun toCommand(o: JSONObject): String {
        val g = o.optString("arg")
        return when (o.optString("action")) {
            "torch_on" -> "टॉर्च जलाओ"
            "torch_off" -> "टॉर्च बंद करो"
            "play_music" -> "गाना बजाओ $g"
            "play_download" -> "डाउनलोड वाला गाना बजाओ $g"
            "play_youtube" -> "यूट्यूब पर $g गाना बजाओ"
            "stop_music" -> "गाना रोको"
            "next_song" -> "अगला गाना"
            "prev_song" -> "पिछला गाना"
            "youtube_search" -> "यूट्यूब पर $g खोजो"
            "google_search" -> "गूगल पर $g खोजो"
            "open_app" -> "$g खोलो"
            "call_name" -> "$g को फोन लगाओ"
            "call_digits" -> "फोन लगाओ आखिर में $g"
            "volume_up" -> "आवाज बढ़ाओ"
            "volume_down" -> "आवाज कम करो"
            "volume_max" -> "आवाज फुल करो"
            "time" -> "समय क्या हुआ"
            "date" -> "आज की तारीख"
            "battery" -> "बैटरी कितनी है"
            "camera" -> "कैमरा खोलो"
            "timer" -> "$g मिनट का टाइमर लगाओ"
            "wifi" -> "वाईफाई सेटिंग खोलो"
            "settings" -> "सेटिंग खोलो"
            else -> ""
        }
    }
}
