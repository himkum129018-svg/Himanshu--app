package com.himanshu.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val lines = ArrayDeque<String>()

    private fun perms(): Array<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) { l.add(Manifest.permission.READ_MEDIA_AUDIO); l.add(Manifest.permission.POST_NOTIFICATIONS) }
        else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("him", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }
        fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; textSize = 17f; setOnClickListener { f() } }

        root.addView(text("हिमांशु", 34f))
        root.addView(text("मेरा अपना हिंदी असिस्टेंट", 15f, Color.LTGRAY))
        root.addView(button("1) अनुमतियाँ दो") { requestPermissions(perms(), 1) })
        root.addView(button("▶ सुनना शुरू करो") { start() })
        root.addView(button("⏹ बंद करो") { stopService(Intent(this, HimanshuService::class.java)) })
        root.addView(button("🎤 अभी बोलो (नाम बिना)") {
            startForegroundService(Intent(this, HimanshuService::class.java).setAction(HimanshuService.ARM))
        })
        root.addView(Switch(this).apply {
            text = "पहले 'हिमांशु' बोलना ज़रूरी"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("needName", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("needName", on).apply() }
        })
        status = text("", 16f, Color.parseColor("#9FE870"))
        root.addView(status)
        root.addView(text(
            "\nबोलकर देखिए:\n• हिमांशु, यूट्यूब खोलो\n• हिमांशु, डाउनलोड वाला गाना बजाओ\n• हिमांशु, अगला गाना\n" +
                "• हिमांशु, राहुल को फोन लगाओ\n• हिमांशु, फोन लगाओ जिसके आखिर में 21 हो\n• हिमांशु, टॉर्च जलाओ\n" +
                "• हिमांशु, कैमरा खोलो\n• हिमांशु, आवाज़ बढ़ाओ\n• हिमांशु, पाँच मिनट का टाइमर लगाओ\n• हिमांशु, समय क्या हुआ\n\nएक बार 'हिमांशु' बोलिए, फिर 1 मिनट तक नाम बिना बोलते रहिए।\nसिखाना: 'लाइट का मतलब टॉर्च'\nसुलाना: 'सो जाओ'", 15f, Color.LTGRAY))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F1720")); addView(root) })
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "पहले 'अनुमतियाँ दो' दबाइए", Toast.LENGTH_LONG).show(); return
        }
        if (!Settings.canDrawOverlays(this)) askOverlay()
        startForegroundService(Intent(this, HimanshuService::class.java))
    }

    private fun askOverlay() {
        Toast.makeText(this, "'दूसरे ऐप के ऊपर दिखाओ' चालू कीजिए, तभी ऐप/कैमरा खुलेंगे", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (!Settings.canDrawOverlays(this)) askOverlay()
    }

    override fun onResume() {
        super.onResume()
        HimanshuService.onHeard = { s ->
            runOnUiThread {
                lines.addFirst(s); while (lines.size > 6) lines.removeLast()
                status.text = "\n" + lines.joinToString("\n")
            }
        }
    }

    override fun onPause() { HimanshuService.onHeard = null; super.onPause() }
}
