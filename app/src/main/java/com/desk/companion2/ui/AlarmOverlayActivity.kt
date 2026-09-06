package com.desk.companion2.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.desk.companion2.service.DeskMonitorService

class AlarmOverlayActivity : AppCompatActivity() {

    private var closeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#991B1B")) // Deep Alert Red
            setPadding(40, 40, 40, 40)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val tvIcon = TextView(this).apply {
            text = "🚨"
            textSize = 64f
            gravity = Gravity.CENTER
        }

        val tvTitle = TextView(this).apply {
            text = "DESK SENTRY BREACH"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 12)
        }

        val reasonText = intent.getStringExtra("EXTRA_REASON") ?: "Student is away from study desk!"
        val tvReason = TextView(this).apply {
            text = reasonText
            setTextColor(Color.parseColor("#FEF08A"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val btnSnooze = Button(this).apply {
            text = "Snooze Alarm (5 Mins)"
            textSize = 16f
            setTextColor(Color.parseColor("#991B1B"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16f
            }
            setPadding(32, 20, 32, 20)
            setOnClickListener {
                val snoozeIntent = Intent(this@AlarmOverlayActivity, DeskMonitorService::class.java).apply {
                    action = DeskMonitorService.ACTION_SNOOZE
                }
                startService(snoozeIntent)
                finish()
            }
        }

        root.addView(tvIcon)
        root.addView(tvTitle)
        root.addView(tvReason)
        root.addView(btnSnooze)
        setContentView(root)

        // Auto-dismiss receiver when student sits back at desk
        closeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                finish()
            }
        }
        registerReceiver(closeReceiver, IntentFilter("com.desk.companion2.CLOSE_OVERLAY"))
    }

    override fun onBackPressed() {
        // Back press blocks closing alarm
    }

    override fun onDestroy() {
        super.onDestroy()
        closeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        DeskMonitorService.isOverlayVisible = false
    }
}
