package com.desk.companion2.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.desk.companion2.DeskConfig
import com.desk.companion2.receivers.CompanionAdminReceiver
import com.desk.companion2.service.DeskMonitorService
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var dbRef: DatabaseReference
    private var valueListener: ValueEventListener? = null

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvBatteryValue: TextView
    private lateinit var tvBatterySub: TextView
    private lateinit var tvHeartbeatValue: TextView
    private lateinit var tvHeartbeatSub: TextView
    private lateinit var btnRequestSnap: Button
    private lateinit var ivLivePhoto: ImageView
    private lateinit var tvPhotoPlaceholder: TextView
    private lateinit var tvPhotoTimestamp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, DeskMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        renderExecutiveDashboard()
        connectDirectlyToFirebase()
    }

    private fun renderExecutiveDashboard() {
        val rootScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }

        // 1. TOP HEADER (HARDCODED TARGET ID)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvAppTitle = TextView(this).apply {
            text = "Desk Companion"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        val tvDeviceIdBadge = TextView(this).apply {
            text = "TARGET: #${DeskConfig.TARGET_DEVICE_ID} (PERMANENT GUARD)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        titleCol.addView(tvAppTitle)
        titleCol.addView(tvDeviceIdBadge)

        tvStatusBadge = TextView(this).apply {
            text = "● CONNECTING"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = createPillDrawable(Color.parseColor("#271C0C"), Color.parseColor("#F59E0B"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        headerRow.addView(titleCol)
        headerRow.addView(tvStatusBadge)
        mainContainer.addView(headerRow)

        // 2. TELEMETRY CARDS
        val telemetryGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, dp(16))
            layoutParams = params
        }

        val batteryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#131B2E"), Color.parseColor("#1E293B"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val tvBatTitle = TextView(this).apply {
            text = "⚡ BATTERY"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvBatteryValue = TextView(this).apply {
            text = "--%"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }
        tvBatterySub = TextView(this).apply {
            text = "Syncing state..."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }
        batteryCard.addView(tvBatTitle)
        batteryCard.addView(tvBatteryValue)
        batteryCard.addView(tvBatterySub)

        val heartbeatCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#131B2E"), Color.parseColor("#1E293B"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        val tvHbTitle = TextView(this).apply {
            text = "⏱ HEARTBEAT"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvHeartbeatValue = TextView(this).apply {
            text = "--"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        tvHeartbeatSub = TextView(this).apply {
            text = "Standby"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }
        heartbeatCard.addView(tvHbTitle)
        heartbeatCard.addView(tvHeartbeatValue)
        heartbeatCard.addView(tvHeartbeatSub)

        telemetryGrid.addView(batteryCard)
        telemetryGrid.addView(heartbeatCard)
        mainContainer.addView(telemetryGrid)

        // 3. LIVE SNAPSHOT BUTTON & DIRECT VIEWFINDER
        btnRequestSnap = createModernButton("📸 Request Live Snapshot", Color.parseColor("#0284C7"), Color.WHITE).apply {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            params.setMargins(0, 0, 0, dp(10))
            layoutParams = params
            setOnClickListener { requestSnapshot() }
        }
        mainContainer.addView(btnRequestSnap)

        val photoWindowFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)).apply {
                setMargins(0, 0, 0, dp(16))
            }
            background = createCardDrawable(Color.parseColor("#0F172A"), Color.parseColor("#334155"))
        }

        ivLivePhoto = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        tvPhotoPlaceholder = TextView(this).apply {
            text = "📷 Live Viewfinder Ready\nTap 'Request Live Snapshot' above to fetch photo"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        tvPhotoTimestamp = TextView(this).apply {
            text = "● LIVE CAPTURE"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            background = createPillDrawable(Color.parseColor("#B91C1C"), Color.TRANSPARENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val p = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(dp(12), 0, 0, dp(12))
            }
            layoutParams = p
            visibility = View.GONE
        }

        photoWindowFrame.addView(ivLivePhoto)
        photoWindowFrame.addView(tvPhotoPlaceholder)
        photoWindowFrame.addView(tvPhotoTimestamp)
        mainContainer.addView(photoWindowFrame)

        // 4. STUDY LOGS BUTTON
        val btnViewReports = createModernButton("📊 View Complete Study Logs", Color.parseColor("#0F766E"), Color.WHITE).apply {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            params.setMargins(0, 0, 0, dp(20))
            layoutParams = params
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EventsActivity::class.java))
            }
        }
        mainContainer.addView(btnViewReports)

        // 5. SECURITY CHECKLIST
        val tvShieldTitle = TextView(this).apply {
            text = "🛡️ ANTI-TAMPER SECURITY CHECKLIST"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(8))
        }
        mainContainer.addView(tvShieldTitle)

        val shieldCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#131B2E"), Color.parseColor("#1E293B"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, dp(16))
            layoutParams = params
        }

        shieldCard.addView(createChecklistItem("1", "Device Admin", "Blocks student from uninstalling companion") { requestAdmin() })
        shieldCard.addView(createDivider())
        shieldCard.addView(createChecklistItem("2", "Display Over Apps", "Allows instant full-screen alarm trigger") { requestOverlayPermission() })
        shieldCard.addView(createDivider())
        shieldCard.addView(createChecklistItem("3", "Allow Autostart", "Enables auto-restart for MI / Oppo / Vivo") { openOemAutostartSettings() })
        shieldCard.addView(createDivider())
        shieldCard.addView(createChecklistItem("4", "No Battery Restrictions", "Stops system from putting socket to sleep") { requestIgnoreBatteryOptimization() })

        mainContainer.addView(shieldCard)

        rootScroll.addView(mainContainer)
        setContentView(rootScroll)
    }

    private fun connectDirectlyToFirebase() {
        val db = FirebaseDatabase.getInstance()
        dbRef = db.getReference(DeskConfig.FIREBASE_ROOT_NODE).child(DeskConfig.TARGET_DEVICE_ID)

        valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    tvStatusBadge.text = "○ CAMERA STANDBY"
                    tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
                    tvStatusBadge.background = createPillDrawable(Color.parseColor("#1E293B"), Color.parseColor("#475569"))
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: "OFFLINE"
                val isAlarm = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false

                when {
                    isAlarm -> {
                        tvStatusBadge.text = "● BREACH ALARM ACTIVE"
                        tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                        tvStatusBadge.background = createPillDrawable(Color.parseColor("#3B0D0D"), Color.parseColor("#EF4444"))
                    }
                    status == "ONLINE" -> {
                        tvStatusBadge.text = "● DESK PROTECTED"
                        tvStatusBadge.setTextColor(Color.parseColor("#22C55E"))
                        tvStatusBadge.background = createPillDrawable(Color.parseColor("#052E16"), Color.parseColor("#22C55E"))
                    }
                    else -> {
                        tvStatusBadge.text = "○ DESK OFFLINE"
                        tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
                        tvStatusBadge.background = createPillDrawable(Color.parseColor("#1E293B"), Color.parseColor("#475569"))
                    }
                }

                val bat = snapshot.child("battery_level").getValue(Int::class.java) ?: -1
                val charging = snapshot.child("is_charging").getValue(Boolean::class.java) ?: false
                if (bat >= 0) {
                    tvBatteryValue.text = "$bat%"
                    tvBatterySub.text = if (charging) "⚡ Charger Plugged" else "Discharging"
                    tvBatterySub.setTextColor(if (charging) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                }

                val lastBeat = snapshot.child("last_heartbeat").getValue(Long::class.java) ?: 0L
                if (lastBeat > 0) {
                    val diffSec = (System.currentTimeMillis() - lastBeat) / 1000
                    tvHeartbeatValue.text = if (diffSec < 60) "${diffSec}s ago" else "${diffSec / 60}m ago"
                    val timeFormatted = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(lastBeat))
                    tvHeartbeatSub.text = timeFormatted
                }

                val base64Img = snapshot.child("latest_snapshot_base64").getValue(String::class.java)
                if (!base64Img.isNullOrEmpty()) {
                    try {
                        val decodedBytes = Base64.decode(base64Img, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        ivLivePhoto.setImageBitmap(bmp)
                        ivLivePhoto.visibility = View.VISIBLE
                        tvPhotoPlaceholder.visibility = View.GONE

                        val snapTime = snapshot.child("latest_snap_time").getValue(Long::class.java) ?: System.currentTimeMillis()
                        val snapTimeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(snapTime))
                        tvPhotoTimestamp.text = "● LIVE SNAPSHOT ($snapTimeStr)"
                        tvPhotoTimestamp.visibility = View.VISIBLE
                        btnRequestSnap.text = "🔄 Refresh Live Snapshot"
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                tvStatusBadge.text = "⚠️ FIREBASE ERROR"
                tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
            }
        }
        dbRef.addValueEventListener(valueListener!!)
    }

    private fun requestSnapshot() {
        btnRequestSnap.text = "⏳ Requesting Frame..."
        dbRef.child("commands").child("request_snap").setValue(true)
        Toast.makeText(this, "Command sent to camera stand...", Toast.LENGTH_SHORT).show()
    }

    private fun createCardDrawable(bgColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun createPillDrawable(bgColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(20).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }
    }

    private fun createModernButton(text: String, bgTint: Int, txtColor: Int): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(txtColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            val shape = GradientDrawable().apply {
                setColor(bgTint)
                cornerRadius = dp(12).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), shape, null)
        }
    }

    private fun createChecklistItem(number: String, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }

        val badge = TextView(this).apply {
            text = number
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createPillDrawable(Color.parseColor("#1E293B"), Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvMain = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(tvMain)
        textCol.addView(tvSub)

        val tvArrow = TextView(this).apply {
            text = "›"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, dp(4), 0)
        }

        row.addView(badge)
        row.addView(textCol)
        row.addView(tvArrow)
        return row
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestAdmin() {
        val comp = ComponentName(this, CompanionAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Locks app against removal by student.")
        }
        startActivity(intent)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, "Display Overlay already authorized ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Battery is set to 'No Restrictions' ✓", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOemAutostartSettings() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        valueListener?.let { dbRef.removeEventListener(it) }
    }
}
