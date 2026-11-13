package com.himanshu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** जो आदेश नियमों से न समझ आए, उसे Google के Gemini AI से समझवाता है. */
object Ai {
    private const val SYS = """तुम "हिमांशु" हो, हिमांशु (असली इंसान) के फ़ोन के निजी असिस्टेंट। तुम्हें हिमांशु ने खुद, अपने निजी इस्तेमाल के लिए बनाया है। कोई पूछे कि तुम्हें किसने बनाया, तो कहो: "मुझे हिमांशु ने बनाया है, अपने निजी इस्तेमाल के लिए।" खुद को कभी भाषा मॉडल या किसी कंपनी का AI मत बताओ।
बोलने का ढंग: एक समझदार दोस्त की तरह, सहज बोलचाल की हिंदी, छोटे वाक्य (एक या दो)। हर बार "माफ़ कीजिए" या "क्या मैं और मदद कर सकता हूँ" मत कहो।
उपयोगकर्ता हिंदी, अंग्रेज़ी या दोनों मिलाकर बोलता है। बात आवाज़ से लिखी गई है, इसलिए शब्द बिगड़े हो सकते हैं (अंग्रेज़ी शब्द देवनागरी में भी); मतलब का सही अंदाज़ा लगाओ। पिछली बातों को ध्यान में रखो।
जो जानकारी तुम्हारे पास नहीं है (जैसे उसके इंस्टाग्राम के फॉलोअर्स, आज का ताज़ा मौसम, ताज़ा ख़बरें), उसे बनाकर मत बताओ। सीधे कहो कि यह तुम्हें अभी दिख नहीं रहा, और जहाँ हो सके वहाँ ऐप खोलने या गूगल/यूट्यूब पर खोजने वाला action दो।
सिर्फ़ एक JSON दो, और कुछ नहीं: {"action":"...","arg":"...","reply":"..."}
action इनमें से एक: torch_on, torch_off, play_music (arg = गाने का नाम, या खाली), stop_music, next_song, youtube_search (arg), google_search (arg), open_app (arg = ऐप का नाम), call_name (arg = नाम), call_digits (arg = नंबर के आख़िरी अंक), volume_up, volume_down, volume_max, time, date, battery, camera, timer (arg = मिनट की संख्या), none.
reply = बोलने के लिए छोटा हिंदी वाक्य। साधारण सवाल या बातचीत हो तो action none रखो और जवाब reply में दो।"""

    @Volatile var lastError = ""
    private val FAST = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
    private val SMART = listOf("gemini-pro-latest", "gemini-flash-latest", "gemini-flash-lite-latest")
    private val HARD = listOf("समझाओ", "समझा", "क्यों", "कैसे", "फर्क", "अंतर", "कारण", "विस्तार", "सलाह",
        "explain", "why", "how", "difference").map { Hindi.norm(it) }

    private fun isHard(t: String): Boolean { val n = Hindi.norm(t); return HARD.any { n.contains(it) } }

    /** निजी सुरक्षा: 4+ अंक या बोले हुए 4+ अंक (फोन नंबर जैसे) Google को नहीं जाते. */
    fun scrub(t: String): String {
        val toks = Hindi.norm(t).split(" ")
        val out = ArrayList<String>()
        var run = ArrayList<String>()
        fun isNum(w: String) = w.isNotEmpty() && (w.all { it.isDigit() } || Hindi.NUMS.contains(w))
        fun flush() { if (run.size >= 4 || run.joinToString("").length >= 4 && run.all { it.all { c -> c.isDigit() } }) out.add("…") else out.addAll(run); run = ArrayList() }
        for (w in toks) { if (isNum(w)) run.add(w) else { flush(); out.add(w) } }
        flush()
        return out.joinToString(" ")
    }

    fun ask(key: String, text0: String, history0: List<Pair<String, String>>, alwaysPro: Boolean = false): JSONObject? {
        val text = scrub(text0)
        val history = history0.map { scrub(it.first) to it.second }
        val models = if (alwaysPro || isHard(text)) SMART else FAST
        for (m in models) {
            val r = try { call(key, m, text, history, true) } catch (e: Exception) { lastError = m + ": " + (e.message ?: "error"); null }
            if (r != null) return r
            if (lastError.contains("HTTP 400")) {
                val r2 = try { call(key, m, text, history, false) } catch (e: Exception) { null }
                if (r2 != null) return r2
            }
        }
        return null
    }

    private fun part(role: String, t: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun call(key: String, model: String, text: String, history: List<Pair<String, String>>, noThink: Boolean): JSONObject? {
        val pro = model.contains("pro")
        val contents = JSONArray()
        for ((u, a) in history) { contents.put(part("user", u)); contents.put(part("model", a)) }
        contents.put(part("user", text))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYS))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", if (pro) 1500 else 400).put("responseMimeType", "application/json")
                .also { if (noThink && !pro) it.put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) })
        val con = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 3000
        con.readTimeout = if (pro) 9000 else 5000
        con.doOutput = true
        con.setRequestProperty("x-goog-api-key", key)
        con.setRequestProperty("content-type", "application/json")
        con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (con.responseCode != 200) {
            lastError = model + ": HTTP " + con.responseCode + " " + (con.errorStream?.bufferedReader()?.readText()?.take(140) ?: "")
            return null
        }
        val resp = con.inputStream.bufferedReader().readText()
        val t = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e < s) return null
        return JSONObject(t.substring(s, e + 1))
    }

    fun toCommand(o: JSONObject): String {
        val g = o.optString("arg")
        return when (o.optString("action")) {
            "torch_on" -> "टॉर्च जलाओ"
            "torch_off" -> "टॉर्च बंद करो"
            "play_music" -> "गाना बजाओ $g"
            "stop_music" -> "गाना रोको"
            "next_song" -> "अगला गाना"
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
            else -> ""
        }
    }
}
