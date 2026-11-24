package com.venice.lockdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var top: View? = null
    private var bottom: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (top == null) addOverlays()
        return START_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun params(h: Int, g: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = g
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) p.fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return p
    }

    private fun addOverlays() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF07070A.toInt())
            isClickable = true
            setOnClickListener { }
            setOnLongClickListener {
                sendBroadcast(
                    Intent(Const.ACTION_UNLOCK_REQUEST).setPackage(packageName)
                )
                true
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(40), 0, 0)
        }

        val date = TextView(this).apply {
            setTextColor(0xFFB0B0C0.toInt())
            textSize = 14f
        }

        val clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 64f
        }

        val hint = TextView(this).apply {
            setTextColor(0xFF808090.toInt())
            textSize = 11f
            text = "hold the clock to unlock"
            setPadding(0, dp(6), 0, 0)
        }

        col.addView(date)
        col.addView(clock)
        col.addView(hint)
        root.addView(col)

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val df = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

        val tick = object : Runnable {
            override fun run() {
                val now = Date()
                clock.text = sdf.format(now)
                date.text = df.format(now)
                handler.postDelayed(
                    this,
                    1000L - (System.currentTimeMillis() % 1000L)
                )
            }
        }
        tick.run()

        val bar = View(this).apply {
            setBackgroundColor(0xFF07070A.toInt())
            isClickable = true
            setOnClickListener { }
        }

        try {
            wm.addView(root, params(dp(200), Gravity.TOP))
            top = root
            wm.addView(bar, params(dp(56), Gravity.BOTTOM))
            bottom = bar
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Const.CH_ID,
                    "Lock",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }

        val n = Notification.Builder(this, Const.CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Locked")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(1, n)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { top?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { bottom?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
