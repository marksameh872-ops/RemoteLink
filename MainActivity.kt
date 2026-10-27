package com.remotelink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var accStatus: TextView
    private lateinit var shareInfo: TextView
    private var pad = 0

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                ScreenService.pin = (100000..999999).random().toString()
                val i = Intent(this, ScreenService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", r.data)
                ContextCompat.startForegroundService(this, i)
                refresh()
            } else {
                Toast.makeText(this, "تم رفض مشاركة الشاشة", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun add(v: View, topMargin: Int = pad / 2) {
            root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = topMargin })
        }
        fun title(t: String) = TextView(this).apply { text = t; textSize = 18f; setTextColor(Color.parseColor("#1565C0")) }
        fun btn(t: String, click: () -> Unit) = Button(this).apply { text = t; setOnClickListener { click() } }

        add(TextView(this).apply { text = "RemoteLink"; textSize = 26f }, 0)

        // ----- الجهاز المتحكَّم فيه -----
        add(title("الجهاز اللي هيتم التحكم فيه"), pad)
        accStatus = TextView(this)
        add(accStatus)
        add(btn("1) فعّل خدمة إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        add(btn("2) ابدأ مشاركة الشاشة") {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        })
        add(btn("إيقاف المشاركة") {
            stopService(Intent(this, ScreenService::class.java))
            ScreenService.pin = ""
            refresh()
        })
        shareInfo = TextView(this).apply { textSize = 16f }
        add(shareInfo)

        // ----- الجهاز المتحكِّم -----
        add(title("الجهاز اللي هتتحكم منه"), pad * 2)
        val prefs = getSharedPreferences("rl", MODE_PRIVATE)
        val ipField = EditText(this).apply {
            hint = "عنوان IP للجهاز التاني (مثال 192.168.1.20)"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(prefs.getString("ip", ""))
        }
        val pinField = EditText(this).apply {
            hint = "الرمز (6 أرقام)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        add(ipField)
        add(pinField)
        add(btn("اتصال والتحكم") {
            val ip = ipField.text.toString().trim()
            val pin = pinField.text.toString().trim()
            if (ip.isEmpty() || pin.isEmpty()) {
                Toast.makeText(this, "اكتب العنوان والرمز", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("ip", ip).apply()
                startActivity(
                    Intent(this, ControlActivity::class.java)
                        .putExtra("ip", ip)
                        .putExtra("pin", pin)
                )
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = RemoteAccessibilityService.instance != null
        accStatus.text = if (on) "خدمة إمكانية الوصول: مفعّلة ✓" else "خدمة إمكانية الوصول: غير مفعّلة ✗"
        accStatus.setTextColor(if (on) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        shareInfo.text = if (ScreenService.pin.isNotEmpty())
            "المشاركة شغالة ✓\nالعنوان: ${localIp()}\nالرمز: ${ScreenService.pin}"
        else "المشاركة متوقفة"
    }

    private fun localIp(): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: ""
                }
            }
        } catch (e: Exception) {
        }
        return "غير متصل بالواي فاي"
    }
}
