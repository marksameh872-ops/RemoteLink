package com.remotelink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.min

class RemoteView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
    var onCommand: ((String) -> Unit)? = null

    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    init { setBackgroundColor(Color.BLACK) }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s
        val h = b.height * s
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(b, null, dst, paint)
    }

    private fun nx(x: Float) = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun ny(y: Float) = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || dst.width() <= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = e.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val dist = hypot(e.x - downX, e.y - downY)
                val dur = e.eventTime - downT
                val threshold = 20 * resources.displayMetrics.density
                if (dist < threshold) {
                    if (dur > 500) onCommand?.invoke("LONG ${f(nx(downX))} ${f(ny(downY))}")
                    else onCommand?.invoke("TAP ${f(nx(downX))} ${f(ny(downY))}")
                } else {
                    val d = dur.coerceIn(100L, 1500L)
                    onCommand?.invoke(
                        "SWIPE ${f(nx(downX))} ${f(ny(downY))} ${f(nx(e.x))} ${f(ny(e.y))} $d"
                    )
                }
            }
        }
        return true
    }
}

class ControlActivity : AppCompatActivity() {

    private lateinit var remoteView: RemoteView
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var alive = true
    private var socket: Socket? = null
    private val sender = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ip = intent.getStringExtra("ip") ?: ""
        val pin = intent.getStringExtra("pin") ?: ""

        remoteView = RemoteView(this)
        remoteView.onCommand = { send(it) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(remoteView, LinearLayout.LayoutParams(-1, 0, 1f))

        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun navBtn(label: String, cmd: String) {
            val b = Button(this).apply {
                text = label
                setOnClickListener { send(cmd) }
            }
            navRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        navBtn("رجوع", "BACK")
        navBtn("الرئيسية", "HOME")
        navBtn("التطبيقات", "RECENTS")
        root.addView(navRow, LinearLayout.LayoutParams(-1, -2))

        val textRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply { hint = "اكتب نص وأرسله للجهاز الآخر" }
        val sendBtn = Button(this).apply {
            text = "إرسال"
            setOnClickListener {
                val t = input.text.toString()
                if (t.isNotEmpty()) {
                    send("TEXT $t")
                    input.setText("")
                }
            }
        }
        val bkspBtn = Button(this).apply {
            text = "مسح"
            setOnClickListener { send("BKSP") }
        }
        textRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        textRow.addView(sendBtn, LinearLayout.LayoutParams(-2, -2))
        textRow.addView(bkspBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(textRow, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        connect(ip, pin)
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun send(cmd: String) {
        sender.execute {
            try {
                val o = out ?: return@execute
                synchronized(o) {
                    o.writeUTF(cmd)
                    o.flush()
                }
            } catch (e: Exception) {
                // الاتصال مقفول
            }
        }
    }

    private fun connect(ip: String, pin: String) {
        thread(name = "connect") {
            var established = false
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(ip, ScreenService.PORT), 5000)
                s.tcpNoDelay = true
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                o.writeUTF("AUTH $pin")
                o.flush()
                s.soTimeout = 10000

                if (inp.readUTF() != "OK") {
                    toast("الرمز غلط")
                    runOnUiThread { finish() }
                    return@thread
                }
                out = o
                established = true
                toast("تم الاتصال")

                while (alive) {
                    val len = inp.readInt()
                    val buf = ByteArray(len)
                    inp.readFully(buf)
                    val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                    if (bmp != null) {
                        runOnUiThread {
                            remoteView.bitmap = bmp
                            remoteView.invalidate()
                        }
                    }
                }
            } catch (e: Exception) {
                if (alive) {
                    toast(if (established) "انقطع الاتصال" else "تعذر الاتصال. تأكد من العنوان وإن الجهازين على نفس الواي فاي")
                    runOnUiThread { finish() }
                }
            }
        }
    }

    override fun onDestroy() {
        alive = false
        try { socket?.close() } catch (e: Exception) {}
        sender.shutdownNow()
        super.onDestroy()
    }
}
