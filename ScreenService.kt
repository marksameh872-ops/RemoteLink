package com.remotelink

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ScreenService : Service() {

    companion object {
        const val PORT = 8080
        @Volatile var pin: String = ""
        @Volatile var running: Boolean = false
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var serverSocket: ServerSocket? = null

    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var frameSeq = 0L
    private var lastFrameTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        running = true
        startCapture(code, data)
        startServer()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("share", "مشاركة الشاشة", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val notification = Notification.Builder(this, "share")
            .setContentTitle("RemoteLink")
            .setContentText("شاشة هذا الجهاز مشاركة الآن")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val scale = minOf(1f, 720f / screenW)
        capW = (screenW * scale).toInt() and 1.inv()
        capH = (screenH * scale).toInt() and 1.inv()

        handlerThread = HandlerThread("capture").also { it.start() }
        val handler = Handler(handlerThread!!.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader -> onImage(reader) }, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "RemoteLink", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < 90) return
            lastFrameTime = now

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * capW
            val full = Bitmap.createBitmap(
                capW + rowPadding / pixelStride, capH, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (rowPadding > 0) Bitmap.createBitmap(full, 0, 0, capW, capH) else full

            val bos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 55, bos)
            latestJpeg = bos.toByteArray()
            frameSeq++

            if (cropped !== full) cropped.recycle()
            full.recycle()
        } catch (e: Exception) {
            // تجاهل الإطار التالف
        } finally {
            image.close()
        }
    }

    private fun startServer() {
        thread(name = "server") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                // السيرفر اتقفل
            }
        }
    }

    private fun handleClient(s: Socket) {
        try {
            s.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

            s.soTimeout = 5000
            val auth = input.readUTF()
            if (pin.isEmpty() || auth != "AUTH $pin") {
                Thread.sleep(2000) // إبطاء محاولات التخمين
                out.writeUTF("FAIL")
                out.flush()
                s.close()
                return
            }
            s.soTimeout = 0
            out.writeUTF("OK")
            out.flush()

            val reader = thread(name = "commands") {
                try {
                    while (true) handleCommand(input.readUTF())
                } catch (e: Exception) {
                    // العميل فصل
                }
            }

            var lastSeq = -1L
            var lastSend = 0L
            while (running && !s.isClosed && reader.isAlive) {
                val jpg = latestJpeg
                val now = System.currentTimeMillis()
                if (jpg != null && (frameSeq != lastSeq || now - lastSend > 1000)) {
                    out.writeInt(jpg.size)
                    out.write(jpg)
                    out.flush()
                    lastSeq = frameSeq
                    lastSend = now
                }
                Thread.sleep(40)
            }
        } catch (e: Exception) {
            // الاتصال انقطع
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    private fun handleCommand(cmd: String) {
        try {
            val acc = RemoteAccessibilityService.instance ?: return
            val p = cmd.split(" ")
            when (p[0]) {
                "TAP" -> acc.tap(p[1].toFloat() * screenW, p[2].toFloat() * screenH, 50)
                "LONG" -> acc.tap(p[1].toFloat() * screenW, p[2].toFloat() * screenH, 700)
                "SWIPE" -> acc.swipe(
                    p[1].toFloat() * screenW, p[2].toFloat() * screenH,
                    p[3].toFloat() * screenW, p[4].toFloat() * screenH,
                    p[5].toLong()
                )
                "BACK" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "HOME" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "RECENTS" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "TEXT" -> acc.typeText(cmd.substring(5))
                "BKSP" -> acc.backspace()
            }
        } catch (e: Exception) {
            // أمر غير صالح
        }
    }

    override fun onDestroy() {
        running = false
        pin = ""
        try { serverSocket?.close() } catch (e: Exception) {}
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        handlerThread?.quitSafely()
        super.onDestroy()
    }
}
