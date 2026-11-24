package com.venice.lockdown

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pinned = false
    private var suspendRefocusUntil = 0L
    private var autoLockPosted = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = unlock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        hideSystemUi()
        refreshState()

        findViewById<View>(R.id.btnOverlay).setOnClickListener {
            suspendRefocusUntil = System.currentTimeMillis() + 9000
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<View>(R.id.btnHome).setOnClickListener {
            suspendRefocusUntil = System.currentTimeMillis() + 9000
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }

        findViewById<View>(R.id.btnLock).setOnClickListener { lockNow() }

        val f = IntentFilter(Const.ACTION_UNLOCK_REQUEST)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(unlockReceiver, f, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(unlockReceiver, f)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        refreshState()

        if (!autoLockPosted && Settings.canDrawOverlays(this)) {
            autoLockPosted = true
            handler.postDelayed({
                autoLockPosted = false
                if (!isFinishing) lockNow()
            }, 1800)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshState() {
        val granted = Settings.canDrawOverlays(this)
        findViewById<View>(R.id.btnLock).isEnabled = granted
        findViewById<View>(R.id.warn).visibility =
            if (granted) View.GONE else View.VISIBLE
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun setIconVisible(visible: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "$packageName.Launcher"),
                if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
        }
    }

    private fun lockNow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps", Toast.LENGTH_SHORT).show()
            return
        }

        findViewById<View>(R.id.setupPanel).visibility = View.GONE
        findViewById<View>(R.id.unlockPanel).visibility = View.GONE

        startForegroundService(Intent(this, OverlayService::class.java))
        setIconVisible(false)
        hideSystemUi()

        if (!pinned) {
            try {
                startLockTask()
                pinned = true
            } catch (_: Exception) {
            }
        }
    }

    private fun unlock() {
        pinned = false
        try { stopLockTask() } catch (_: Exception) {}
        stopService(Intent(this, OverlayService::class.java))
        setIconVisible(true)

        findViewById<View>(R.id.unlockPanel).visibility = View.GONE
        findViewById<View>(R.id.setupPanel).visibility = View.VISIBLE

        Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ openOtherLauncher() }, 500)
    }

    private fun openOtherLauncher() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val list = packageManager.queryIntentActivities(i, 0)
        for (ri in list) {
            if (ri.activityInfo.packageName != packageName) {
                startActivity(
                    Intent().setComponent(
                        ComponentName(
                            ri.activityInfo.packageName,
                            ri.activityInfo.name
                        )
                    )
                )
                return
            }
        }
        startActivity(i)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) return
        if (System.currentTimeMillis() < suspendRefocusUntil) return
        if (!pinned) return

        handler.postDelayed({
            if (!hasWindowFocus() && !isFinishing && pinned) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, 400)
    }
}
