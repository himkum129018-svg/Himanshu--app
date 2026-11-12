package com.himanshu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** जो आदेश नियमों से न समझ आए, उसे इंटरनेट वाले Claude AI से समझवाता है. */
object Ai {
    private const val SYS = """तुम एक फ़ोन असिस्टेंट हो। उपयोगकर्ता हिंदी, अंग्रेज़ी या दोनों मिलाकर बोलता है। उसकी बात आवाज़ से लिखी गई है, इसलिए शब्द बिगड़े हुए हो सकते हैं (जैसे अंग्रेज़ी शब्द देवनागरी में); मतलब का अंदाज़ा लगाओ।
सिर्फ़ एक JSON दो, और कुछ नहीं: {"action":"...","arg":"...","reply":"..."}
action इनमें से एक: torch_on, torch_off, play_music (arg = गाने का नाम, या खाली), stop_music, next_song, youtube_search (arg), google_search (arg), open_app (arg = ऐप का नाम), call_name (arg = नाम), call_digits (arg = नंबर के आख़िरी अंक), volume_up, volume_down, volume_max, time, date, battery, camera, timer (arg = मिनट की संख्या), none.
reply = बोलने के लिए बहुत छोटा हिंदी वाक्य। साधारण सवाल या बातचीत हो तो action none रखो और जवाब reply में दो।"""

    fun ask(key: String, text: String): JSONObject? {
        val body = JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 200).put("system", SYS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val con = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 6000
        con.readTimeout = 12000
        con.doOutput = true
        con.setRequestProperty("x-api-key", key)
        con.setRequestProperty("anthropic-version", "2023-06-01")
        con.setRequestProperty("content-type", "application/json")
        con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (con.responseCode != 200) return null
        val resp = con.inputStream.bufferedReader().readText()
        val t = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
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
