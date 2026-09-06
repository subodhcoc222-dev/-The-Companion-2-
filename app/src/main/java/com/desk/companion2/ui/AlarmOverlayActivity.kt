package com.desk.companion2.ui

import android.app.KeyguardManager
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

        // Screen On & Lockscreen Dismissal (Android 8 to 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#991B1B")) // Deep Alert Red
            setPadding(dp(32), dp(32), dp(32), dp(32))
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
            setPadding(0, dp(16), 0, dp(12))
        }

        val reasonText = intent.getStringExtra("EXTRA_REASON") ?: "Student is away from study desk!"
        val tvReason = TextView(this).apply {
            text = reasonText
            setTextColor(Color.parseColor("#FEF08A"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(36))
        }

        val btnSnooze = Button(this).apply {
            text = "Snooze Alarm (5 Mins)"
            textSize = 16f
            setTextColor(Color.parseColor("#991B1B"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(24), dp(16), dp(24), dp(16))
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

        // Safe Receiver Registration (Fixes Android 14 SecurityException Crash)
        closeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                finish()
            }
        }
        val filter = IntentFilter("com.desk.companion2.CLOSE_OVERLAY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent dismissal with back button
    }

    override fun onDestroy() {
        super.onDestroy()
        closeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        DeskMonitorService.isOverlayVisible = false
    }
}
