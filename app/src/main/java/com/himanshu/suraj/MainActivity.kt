package com.himanshu.suraj

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.setPadding(48, 48, 48, 48)
        val t = TextView(this)
        t.text = "हिमांशु"
        t.textSize = 32f
        t.gravity = Gravity.CENTER
        val b1 = Button(this)
        b1.text = "हिमांशु चालू करो"
        b1.setOnClickListener { chalu() }
        val b2 = Button(this)
        b2.text = "दूसरे ऐप के ऊपर दिखने की अनुमति दो"
        b2.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        val b3 = Button(this)
        b3.text = "हिमांशु बंद करो"
        b3.setOnClickListener { stopService(Intent(this, SurajService::class.java)) }
        l.addView(t); l.addView(b1); l.addView(b2); l.addView(b3)
        setContentView(l)
    }

    private fun chalu() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
            p.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            p.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) shuru() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) shuru()
    }

    private fun shuru() {
        startForegroundService(Intent(this, SurajService::class.java))
    }
}
