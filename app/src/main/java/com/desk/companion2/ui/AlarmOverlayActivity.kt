package com.desk.companion2.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.desk.companion2.data.DeskPreferences
import com.desk.companion2.service.DeskMonitorService

class AlarmOverlayActivity : AppCompatActivity() {

    private lateinit var deskPrefs: DeskPreferences
    private lateinit var reasonTextView: TextView
    private lateinit var snoozeButton: Button
    private lateinit var dismissPinButton: Button

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deskPrefs = DeskPreferences(this)

        // Lockscreen bypass & screen wake flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        DeskMonitorService.isOverlayVisible = true

        setupOverlayUI()

        // Register auto-dismiss receiver when camera recovers or student returns
        val filter = IntentFilter("com.desk.companion2.CLOSE_OVERLAY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newReason = intent.getStringExtra("EXTRA_REASON") ?: "STUDY BREACH DETECTED!"
        reasonTextView.text = newReason
    }

    private fun setupOverlayUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B71C1C")) // High-alert Red
            setPadding(48, 64, 48, 64)
        }

        val alertIcon = TextView(this).apply {
            text = "🚨"
            textSize = 72f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(alertIcon)

        val headerText = TextView(this).apply {
            text = "CRITICAL ALERT!"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(headerText)

        val reasonMsg = intent.getStringExtra("EXTRA_REASON") ?: "⚠️ STUDY BREACH! Student Left Desk."
        reasonTextView = TextView(this).apply {
            text = reasonMsg
            textSize = 18f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }
        rootLayout.addView(reasonTextView)

        val snoozeMins = deskPrefs.getSnoozeMinutes()
        snoozeButton = Button(this).apply {
            text = "⏰ SNOOZE ALARM (${snoozeMins} MIN)"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#FFEB3B")) // Prominent Yellow
            setPadding(32, 36, 32, 36)
            setOnClickListener {
                triggerSnooze()
            }
        }
        val snoozeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 32
        }
        rootLayout.addView(snoozeButton, snoozeParams)

        dismissPinButton = Button(this).apply {
            text = "🔒 DISMISS / DISARM (PIN REQUIRED)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#212121"))
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                showMasterPinDismissDialog()
            }
        }
        val dismissParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rootLayout.addView(dismissPinButton, dismissParams)

        val hintText = TextView(this).apply {
            text = "Alarm automatically stops once student returns or connection restores."
            textSize = 12f
            setTextColor(Color.parseColor("#EF9A9A"))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }
        rootLayout.addView(hintText)

        setContentView(rootLayout)
    }

    private fun triggerSnooze() {
        val snoozeIntent = Intent(this, DeskMonitorService::class.java).apply {
            action = DeskMonitorService.ACTION_SNOOZE
        }
        startService(snoozeIntent)
        finish()
    }

    private fun showMasterPinDismissDialog() {
        val input = EditText(this).apply {
            hint = "Enter 4-Digit Master PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("🔒 Authorize Silence")
            .setMessage("Enter Master PIN to disarm guard and silence emergency alarm:")
            .setView(input)
            .setPositiveButton("Verify & Disarm") { _, _ ->
                val entered = input.text.toString().trim()
                if (deskPrefs.verifyPin(entered)) {
                    // Disarm guard completely
                    deskPrefs.setGuardArmed(false)
                    val stateIntent = Intent(this, DeskMonitorService::class.java).apply {
                        action = DeskMonitorService.ACTION_STATE_CHANGED
                    }
                    startService(stateIntent)
                    Toast.makeText(this, "Guard Disarmed by Master PIN", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Incorrect PIN! Action rejected.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent bypassing overlay with hardware back button without Snooze or Master PIN
    }

    override fun onDestroy() {
        super.onDestroy()
        DeskMonitorService.isOverlayVisible = false
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Exception) {}
    }
}
