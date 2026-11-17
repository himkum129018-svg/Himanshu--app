package com.himanshu.assistant

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * सीधा-सादा पर्दा: ऊपर एक बड़ा बटन (शुरू / बंद), नीचे हिमांशु ने क्या सुना और क्या बोला,
 * फिर टाइप करने की जगह. बाकी सब (चाबी, अनुमतियाँ, आवाज़) "सेटिंग" के अंदर छिपा है.
 */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var stateTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var mainBtn: Button
    private lateinit var armBtn: Button
    private lateinit var logTv: TextView
    private lateinit var keyState: TextView
    private lateinit var settingsBox: LinearLayout
    private val lines = ArrayDeque<String>()
    private var waiting = 0

    private val gold = Color.parseColor("#D4AF37")
    private val red = Color.parseColor("#B91C1C")
    private val card = Color.parseColor("#1F2937")
    private val green = Color.parseColor("#9FE870")

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun perms(): Array<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) { l.add(Manifest.permission.READ_MEDIA_AUDIO); l.add(Manifest.permission.POST_NOTIFICATIONS) }
        else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun tv(s: String, size: Float, color: Int = Color.WHITE) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color) }

    private fun btn(s: String, bg: Int = card, fg: Int = Color.WHITE, f: () -> Unit) = Button(this).apply {
        text = s; textSize = 16f; isAllCaps = false; setTextColor(fg)
        background = rounded(bg, 14)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener { f() }
    }

    private fun input(h: String, secret: Boolean = false) = EditText(this).apply {
        hint = h; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); isSingleLine = true
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun LinearLayout.addV(v: View, top: Int = 10) {
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("him", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(28)) }

        root.addV(tv("हिमांशु", 38f, gold), 0)
        stateTv = tv("", 16f, Color.LTGRAY); root.addV(stateTv, 4)

        mainBtn = btn("", gold, Color.BLACK) { if (HimanshuService.running) stopListening() else beginStart() }
        mainBtn.textSize = 22f
        root.addV(mainBtn, 18)
        mainBtn.minimumHeight = dp(72)

        armBtn = btn("🎤 अभी बोलो (नाम बोले बिना)") {
            startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.ARM))
        }
        root.addV(armBtn, 10)

        hintTv = tv("", 14f, Color.parseColor("#FFB4A9")); root.addV(hintTv, 10)

        logTv = tv("", 15f, green).apply {
            background = rounded(Color.parseColor("#111C27"), 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(120)
        }
        root.addV(logTv, 14)

        val box = input("यहाँ टाइप करके भी कह सकते हैं")
        root.addV(box, 14)
        fun send() {
            val q = box.text.toString().trim()
            if (q.isEmpty()) return
            if (!HimanshuService.running) { toast("पहले 'सुनना शुरू करो' दबाइए"); return }
            startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.CMD).putExtra("text", q))
            box.setText("")
        }
        box.setOnEditorActionListener { _, _, _ -> send(); true }
        root.addV(btn("🔎 भेजो") { send() }, 8)

        // ---- सेटिंग (छिपी रहती है) ----
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addV(btn("⚙ सेटिंग") {
            settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, 22)
        root.addView(settingsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        settingsBox.addV(tv("AI की चाबी (Gemini)", 18f, gold), 14)
        keyState = tv("", 16f); settingsBox.addV(keyState, 6)
        settingsBox.addV(btn("📋 चाबी कॉपी कर ली? तो यहाँ दबाइए") { pasteKey() })
        val keyBox = input("या चाबी यहाँ चिपकाइए", true); settingsBox.addV(keyBox)
        settingsBox.addV(btn("🔑 सेव करो") { saveKey(keyBox.text.toString()); keyBox.setText("") })
        settingsBox.addV(btn("🧪 चाबी जाँचो") { testKey() })

        settingsBox.addV(tv("आवाज़ और अनुमतियाँ", 18f, gold), 22)
        settingsBox.addV(Switch(this).apply {
            text = "पहले 'हिमांशु' बोलना ज़रूरी"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("needName", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("needName", on).apply() }
        })
        settingsBox.addV(btn("🎧 माइक फिर से चालू करो") {
            if (HimanshuService.running) startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.RESUME))
            else toast("पहले 'सुनना शुरू करो' दबाइए")
        })
        settingsBox.addV(btn("🔊 हिंदी आवाज़ सेट करो") { openTtsSettings() })
        settingsBox.addV(btn("✅ सारी अनुमतियाँ दो") {
            val miss = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (miss.isEmpty()) toast("सारी अनुमतियाँ मिल चुकी हैं ✅") else requestPermissions(miss.toTypedArray(), 2)
        })

        settingsBox.addV(tv("बोलकर देखिए", 18f, gold), 22)
        settingsBox.addV(tv("• हिमांशु, यूट्यूब खोलो\n• हिमांशु, डाउनलोड वाला गाना बजाओ\n• हिमांशु, मम्मी को फोन लगाओ\n" +
            "• हिमांशु, टॉर्च जलाओ\n• हिमांशु, फोन लगाओ जिसके आखिर में 21 हो\n• हिमांशु, पाँच मिनट का टाइमर लगाओ\n" +
            "• हिमांशु, सुबह सात बजे का अलार्म लगाओ\n• हिमांशु, दो गुणा तीन कितना होता है\n• हिमांशु, गूगल पर क्रिकेट स्कोर खोजो\n" +
            "• हिमांशु, याद रखो कि चाबी रसोई में है\n• हिमांशु, तुमने क्या याद रखा\n• हिमांशु, तुम क्या क्या कर सकते हो\n\n" +
            "एक बार 'हिमांशु' बोलिए, फिर 3 मिनट तक नाम बिना बोलते रहिए। सुलाने के लिए: 'सो जाओ'।", 14f, Color.LTGRAY), 6)

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F1720")); addView(root) })
        refreshKey()
        refreshUi()
    }

    // ---------- शुरू करने के चरण: अनुमति -> दूसरे ऐप के ऊपर -> बैटरी -> सेवा चालू ----------
    private fun beginStart() {
        val miss = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 1) else stage2()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code != 1) return
        if (!hasMic()) { toast("माइक की अनुमति के बिना हिमांशु सुन नहीं सकता"); return }
        stage2()
    }

    private fun stage2() {
        if (!Settings.canDrawOverlays(this)) {
            waiting = 3
            toast("'दूसरे ऐप के ऊपर दिखाओ' चालू कीजिए, तभी ऐप/कैमरा खुलेंगे। फिर वापस आइए")
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { waiting = 0; stage3() }
            return
        }
        stage3()
    }

    private fun stage3() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            waiting = 4
            toast("'अनुमति दो' दबाइए, ताकि हिमांशु हमेशा सुनता रहे")
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) { waiting = 0; stage4() }
            return
        }
        stage4()
    }

    private fun stage4() {
        if (!hasMic()) { toast("पहले माइक की अनुमति दीजिए"); return }
        try {
            startForegroundService(Intent(this, HimanshuService::class.java))
        } catch (e: Exception) {
            toast("चालू नहीं हो पाया: " + e.message)
        }
        window.decorView.postDelayed({ refreshUi() }, 700)
    }

    private fun stopListening() {
        stopService(Intent(this, HimanshuService::class.java))
        window.decorView.postDelayed({ refreshUi() }, 500)
    }

    private fun openTtsSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) {} }
    }

    // ---------- चाबी ----------
    private fun saveKey(k: String) {
        val key = k.trim()
        if (key.length < 20 || key.contains(" ")) { toast("यह चाबी जैसी नहीं लगी। aistudio.google.com/apikey से चाबी कॉपी कीजिए"); return }
        prefs.edit().putString("apikey", key).apply()
        refreshKey()
        toast("चाबी सेव हो गई ✅")
    }

    private fun testKey() {
        val key = prefs.getString("apikey", "") ?: ""
        if (key.isBlank()) { toast("पहले चाबी सेव कीजिए"); return }
        keyState.text = "जाँच रहा हूँ..."
        keyState.setTextColor(Color.LTGRAY)
        Thread {
            val t0 = System.currentTimeMillis()
            val r = try { Ai.ask(key, "दो और दो कितने होते हैं", emptyList()) } catch (e: Exception) { null }
            val sec = (System.currentTimeMillis() - t0) / 100 / 10.0
            runOnUiThread {
                if (r != null) {
                    keyState.text = "✅ चाबी सही है (" + sec + " सेकंड)। जवाब: " + r.optString("reply")
                    keyState.setTextColor(green)
                } else {
                    keyState.text = "❌ " + Ai.lastError
                    keyState.setTextColor(Color.parseColor("#FF8A80"))
                }
            }
        }.start()
    }

    private fun pasteKey() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        saveKey(t)
    }

    private fun hasKey() = !prefs.getString("apikey", "").isNullOrBlank()

    private fun refreshKey() {
        val has = hasKey()
        keyState.text = if (has) "चाबी: सेव है ✅" else "चाबी: अभी नहीं है ❌"
        keyState.setTextColor(if (has) green else Color.parseColor("#FF8A80"))
        hintTv.text = if (has) "" else "AI की चाबी नहीं है। फोन वाले आदेश (गाना, फोन, टॉर्च, अलार्म...) फिर भी चलेंगे; बाकी सवालों के लिए ⚙ सेटिंग में चाबी डालिए।"
        hintTv.visibility = if (has) View.GONE else View.VISIBLE
    }

    private fun refreshUi() {
        val on = HimanshuService.running
        mainBtn.text = if (on) "⏹ बंद करो" else "▶ सुनना शुरू करो"
        mainBtn.background = rounded(if (on) red else gold, 18)
        mainBtn.setTextColor(if (on) Color.WHITE else Color.BLACK)
        stateTv.text = if (on) "🎧 सुन रहा हूँ। बोलिए: \"हिमांशु, ...\"" else "अभी बंद है"
        armBtn.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun showLines() { logTv.text = if (lines.isEmpty()) "यहाँ दिखेगा कि हिमांशु ने क्या सुना और क्या बोला" else lines.joinToString("\n") }

    override fun onResume() {
        super.onResume()
        refreshKey()
        refreshUi()
        lines.clear(); lines.addAll(HimanshuService.snapshot().take(6)); showLines()
        HimanshuService.onHeard = { s ->
            runOnUiThread {
                lines.addFirst(s); while (lines.size > 6) lines.removeLast()
                showLines()
                refreshUi()
            }
        }
        when (waiting) {
            3 -> { waiting = 0; stage3() }
            4 -> { waiting = 0; stage4() }
        }
    }

    override fun onPause() { HimanshuService.onHeard = null; super.onPause() }
}
