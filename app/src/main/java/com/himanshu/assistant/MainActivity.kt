package com.himanshu.assistant

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var keyState: TextView
    private lateinit var prefs: SharedPreferences
    private val lines = ArrayDeque<String>()
    private val gold = Color.parseColor("#D4AF37")

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun perms(): Array<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) { l.add(Manifest.permission.READ_MEDIA_AUDIO); l.add(Manifest.permission.POST_NOTIFICATIONS) }
        else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    private fun tv(s: String, size: Float, color: Int = Color.WHITE) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color) }

    private fun btn(s: String, f: () -> Unit) = Button(this).apply {
        text = s; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#1F2937")); setOnClickListener { f() }
    }

    private fun input(h: String, secret: Boolean = false) = EditText(this).apply {
        hint = h; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); isSingleLine = true
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("him", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(24)) }
        fun add(v: View) {
            root.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
        }

        add(tv("Himanshu", 36f, gold))
        add(tv("मेरा अपना हिंदी असिस्टेंट", 15f, Color.LTGRAY))

        add(tv("① AI की चाबी (Gemini)", 18f, gold))
        keyState = tv("", 16f); add(keyState)
        add(btn("📋 चाबी कॉपी कर ली? तो यहाँ दबाइए") { pasteKey() })
        val keyBox = input("या चाबी यहाँ चिपकाइए", true); add(keyBox)
        add(btn("🔑 सेव करो") { saveKey(keyBox.text.toString()); keyBox.setText("") })

        add(tv("② शुरू करो", 18f, gold))
        add(btn("अनुमतियाँ दो") { requestPermissions(perms(), 1) })
        add(btn("▶ सुनना शुरू करो") { start() })
        add(btn("🎧 माइक फिर से चालू करो") {
            startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.RESUME))
        })
        add(btn("⏹ बंद करो") { stopService(Intent(this, HimanshuService::class.java)) })
        add(btn("🎤 अभी बोलो (नाम बिना)") {
            startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.ARM))
        })
        add(Switch(this).apply {
            text = "पहले 'हिमांशु' बोलना ज़रूरी"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("needName", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("needName", on).apply() }
        })

        add(tv("③ टाइप करके आदेश दो", 18f, gold))
        val box = input("जैसे: दो और दो कितने होते हैं"); add(box)
        fun send() {
            val q = box.text.toString().trim()
            if (q.isNotEmpty()) {
                startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.CMD).putExtra("text", q))
                box.setText("")
            }
        }
        box.setOnEditorActionListener { _, _, _ -> send(); true }
        add(btn("🔎 भेजो") { send() })

        status = tv("", 15f, Color.parseColor("#9FE870")); add(status)
        add(tv("बोलकर देखिए:\n• हिमांशु, यूट्यूब खोलो\n• हिमांशु, डाउनलोड वाला गाना बजाओ\n• हिमांशु, कॉल द मम्मी\n" +
            "• हिमांशु, टॉर्च जलाओ\n• हिमांशु, फोन लगाओ जिसके आखिर में 21 हो\n• हिमांशु, पाँच मिनट का टाइमर लगाओ\n\n" +
            "एक बार 'हिमांशु' बोलिए, फिर 1 मिनट तक नाम बिना बोलते रहिए। सुलाने के लिए: 'सो जाओ'।", 14f, Color.LTGRAY))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F1720")); addView(root) })
        refreshKey()
    }

    private fun saveKey(k: String) {
        val key = k.trim()
        if (key.length < 20 || key.contains(" ")) { toast("यह चाबी जैसी नहीं लगी। aistudio.google.com/apikey से चाबी कॉपी कीजिए"); return }
        prefs.edit().putString("apikey", key).apply()
        refreshKey()
        toast("चाबी सेव हो गई ✅")
    }

    private fun pasteKey() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        saveKey(t)
    }

    private fun refreshKey() {
        val has = !prefs.getString("apikey", "").isNullOrBlank()
        keyState.text = if (has) "चाबी: सेव है ✅" else "चाबी: अभी नहीं है ❌"
        keyState.setTextColor(if (has) Color.parseColor("#9FE870") else Color.parseColor("#FF8A80"))
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("पहले 'अनुमतियाँ दो' दबाइए"); return
        }
        if (!Settings.canDrawOverlays(this)) askOverlay()
        startForegroundService(Intent(this, HimanshuService::class.java))
    }

    private fun askOverlay() {
        toast("'दूसरे ऐप के ऊपर दिखाओ' चालू कीजिए, तभी ऐप/कैमरा खुलेंगे")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (!Settings.canDrawOverlays(this)) askOverlay()
    }

    override fun onResume() {
        super.onResume()
        refreshKey()
        HimanshuService.onHeard = { s ->
            runOnUiThread {
                lines.addFirst(s); while (lines.size > 6) lines.removeLast()
                status.text = lines.joinToString("\n")
            }
        }
    }

    override fun onPause() { HimanshuService.onHeard = null; super.onPause() }
}
