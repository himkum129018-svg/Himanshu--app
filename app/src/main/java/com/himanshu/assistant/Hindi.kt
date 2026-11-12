package com.himanshu.assistant

import java.util.Locale

/** हिंदी/अंग्रेज़ी शब्दों को एक जैसे "ढांचे" (skeleton) में बदलकर मिलाने के औज़ार. */
object Hindi {
    private val NONWORD = Regex("[^\\p{L}\\p{M}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val FIX = listOf(
        "\u0901" to "\u0902", "\u093C" to "", "\u0903" to "",
        "\u0958" to "क", "\u0959" to "ख", "\u095A" to "ग", "\u095B" to "ज",
        "\u095C" to "ड", "\u095D" to "ढ", "\u095E" to "फ", "\u095F" to "य"
    )
    private val DEV = mapOf(
        'क' to 'k', 'ख' to 'k', 'च' to 'k', 'छ' to 'k',
        'ग' to 'j', 'घ' to 'j', 'ज' to 'j', 'झ' to 'j',
        'ट' to 't', 'ठ' to 't', 'त' to 't', 'थ' to 't',
        'ड' to 'd', 'ढ' to 'd', 'द' to 'd', 'ध' to 'd',
        'न' to 'n', 'ण' to 'n', 'ङ' to 'n', 'ञ' to 'n', 'ं' to 'n',
        'प' to 'p', 'फ' to 'p', 'ब' to 'b', 'भ' to 'b', 'म' to 'm',
        'र' to 'r', 'ल' to 'l', 'श' to 's', 'ष' to 's', 'स' to 's'
    )
    private val LAT = mapOf(
        'c' to 'k', 'q' to 'k', 'k' to 'k', 'x' to 'k', 'g' to 'j', 'j' to 'j', 'z' to 'j',
        'f' to 'p', 'p' to 'p', 'b' to 'b', 'd' to 'd', 't' to 't', 'n' to 'n',
        'm' to 'm', 'r' to 'r', 'l' to 'l', 's' to 's'
    )

    fun norm(s: String): String {
        var r = s.lowercase(Locale.ROOT)
        for ((a, b) in FIX) r = r.replace(a, b)
        return SPACES.replace(NONWORD.replace(r, " "), " ").trim()
    }

    private val NUMS = ("शून्य एक दो तीन चार पांच छह सात आठ नौ " +
        "दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस " +
        "बीस इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस " +
        "तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस " +
        "चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास " +
        "पचास इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ " +
        "साठ इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर " +
        "सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उन्यासी " +
        "अस्सी इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी " +
        "नब्बे इक्यानवे बानवे तिरानवे चौरानवे पचानवे छियानवे सत्तानवे अट्ठानवे निन्यानवे")
        .split(" ").map { norm(it) }

    fun digitsFrom(tokens: List<String>, lead: Int = 3): String {
        val sb = StringBuilder()
        var skipped = 0
        for (tk in tokens) {
            val isDigits = tk.isNotEmpty() && tk.all { it.isDigit() }
            val n = if (isDigits) -1 else NUMS.indexOf(tk)
            if (isDigits) sb.append(tk)
            else if (n >= 0) sb.append(n)
            else if (sb.isNotEmpty()) break
            else if (++skipped > lead) break
        }
        return sb.toString()
    }

    fun skel(s: String): String {
        val sb = StringBuilder()
        for (ch in norm(s)) {
            val m: Char? = DEV[ch] ?: LAT[ch] ?: (if (ch.isDigit()) ch else null)
            if (m != null && (sb.isEmpty() || sb.last() != m)) sb.append(m)
        }
        return sb.toString()
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** बोले हुए नाम q से सबसे मिलता-जुलता item ढूँढता है. */
    fun <T> best(q: String, items: List<T>, name: (T) -> String): T? {
        val qs = skel(q)
        if (qs.isEmpty()) return null
        var bi: T? = null
        var bd = Int.MAX_VALUE
        var bl = Int.MAX_VALUE
        for (item in items) {
            val ns = skel(name(item))
            if (ns.isEmpty()) continue
            val d = if (qs.length >= 3 && ns.contains(qs)) 0 else lev(qs, ns)
            if (d < bd || (d == bd && ns.length < bl)) { bd = d; bl = ns.length; bi = item }
        }
        val thr = if (qs.length <= 1) 0 else maxOf(1, qs.length / 3)
        return if (bd <= thr) bi else null
    }

    fun isWake(tok: String) = tok.startsWith("हिम") || tok.contains("मांशु") || tok.contains("माशु") ||
        tok.contains("मांसु") || tok.contains("himan")
}
