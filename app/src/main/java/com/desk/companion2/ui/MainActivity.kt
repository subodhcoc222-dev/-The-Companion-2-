package com.desk.companion2.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var prefs: SharedPreferences

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvBatteryValue: TextView
    private lateinit var tvBatterySub: TextView
    private lateinit var tvHeartbeatValue: TextView
    private lateinit var tvHeartbeatSub: TextView
    private lateinit var btnRequestSnap: Button
    private lateinit var ivLivePhoto: ImageView
    private lateinit var tvPhotoPlaceholder: TextView
    private lateinit var tvPhotoTimestamp: TextView

    // Emergency Snooze Banner in Dashboard
    private lateinit var bannerSnooze: LinearLayout

    // Checklist Views
    private lateinit var tvShieldTitle: TextView
    private lateinit var shieldCard: LinearLayout
    private lateinit var viewAllDoneCard: TextView
    private lateinit var itemAdmin: View
    private lateinit var div1: View
    private lateinit var itemOverlay: View
    private lateinit var div2: View
    private lateinit var itemAutostart: View
    private lateinit var div3: View
    private lateinit var itemBattery: View

    private var lastHeartbeatMs: Long = 0L
    private var isAlarmActiveOnServer: Boolean = false
    private val loopHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("DeskCompanionPrefs", Context.MODE_PRIVATE)

        val serviceIntent = Intent(this, DeskMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        renderExecutiveDashboard()
        connectDirectlyToFirebase()
        startRealtimeUIRefresher()
    }

    override fun onResume() {
        super.onResume()
        refreshSecurityChecklistVisibility()
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

        // EMERGENCY SNOOZE BANNER (Visible only when alarm is ringing)
        bannerSnooze = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#DC2626"))
            background = createCardDrawable(Color.parseColor("#DC2626"), Color.parseColor("#EF4444"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.setMargins(0, 0, 0, dp(16))
            layoutParams = p
            visibility = View.GONE
        }

        val tvSnoozeMsg = TextView(this).apply {
            text = "🚨 ALARM IS RINGING!"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnBannerSnoozeAction = Button(this).apply {
            text = "SNOOZE (5M)"
            setTextColor(Color.parseColor("#991B1B"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                startService(Intent(this@MainActivity, DeskMonitorService::class.java).apply {
                    action = DeskMonitorService.ACTION_SNOOZE
                })
                bannerSnooze.visibility = View.GONE
            }
        }

        bannerSnooze.addView(tvSnoozeMsg)
        bannerSnooze.addView(btnBannerSnoozeAction)
        mainContainer.addView(bannerSnooze)

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
            text = "Waiting..."
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

        // 5. SECURITY CHECKLIST WIZARD (Items disappear as soon as granted)
        tvShieldTitle = TextView(this).apply {
            text = "🛡️ ANTI-TAMPER SECURITY CHECKLIST"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(8))
        }
        mainContainer.addView(tvShieldTitle)

        viewAllDoneCard = TextView(this).apply {
            text = "🛡️ All Anti-Tamper Protections Active ✓"
            setTextColor(Color.parseColor("#22C55E"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createCardDrawable(Color.parseColor("#052E16"), Color.parseColor("#15803D"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, dp(16))
            layoutParams = params
            visibility = View.GONE
        }
        mainContainer.addView(viewAllDoneCard)

        shieldCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#131B2E"), Color.parseColor("#1E293B"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, dp(16))
            layoutParams = params
        }

        itemAdmin = createChecklistItem("1", "Device Admin", "Blocks student from uninstalling companion") { requestAdmin() }
        div1 = createDivider()
        itemOverlay = createChecklistItem("2", "Display Over Apps", "Allows instant full-screen alarm trigger") { requestOverlayPermission() }
        div2 = createDivider()
        itemAutostart = createChecklistItem("3", "Allow Autostart", "Enables auto-restart for MI / Oppo / Vivo") { openOemAutostartSettings() }
        div3 = createDivider()
        itemBattery = createChecklistItem("4", "No Battery Restrictions", "Stops system from putting socket to sleep") { requestIgnoreBatteryOptimization() }

        shieldCard.addView(itemAdmin)
        shieldCard.addView(div1)
        shieldCard.addView(itemOverlay)
        shieldCard.addView(div2)
        shieldCard.addView(itemAutostart)
        shieldCard.addView(div3)
        shieldCard.addView(itemBattery)

        mainContainer.addView(shieldCard)

        rootScroll.addView(mainContainer)
        setContentView(rootScroll)
    }

    private fun isCompanionOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startRealtimeUIRefresher() {
        loopHandler.post(object : Runnable {
            override fun run() {
                updateLiveStatusBadge()
                loopHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun updateLiveStatusBadge() {
        // Show emergency snooze banner if alarm is ringing
        if (DeskMonitorService.isCurrentlyRinging || isAlarmActiveOnServer) {
            bannerSnooze.visibility = View.VISIBLE
        } else {
            bannerSnooze.visibility = View.GONE
        }

        val now = System.currentTimeMillis()
        val hasNet = isCompanionOnline()
        val diffSec = if (lastHeartbeatMs > 0L) (now - lastHeartbeatMs) / 1000 else 999L

        if (!hasNet || (lastHeartbeatMs > 0L && diffSec > 15L)) {
            // Immediate Disconnect State
            tvStatusBadge.text = "○ DESK DISCONNECTED"
            tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
            tvStatusBadge.background = createPillDrawable(Color.parseColor("#3B0D0D"), Color.parseColor("#EF4444"))

            tvHeartbeatValue.text = if (!hasNet) "No Internet" else "${diffSec}s ago"
            tvHeartbeatSub.text = if (!hasNet) "Wi-Fi / Mobile Data Off" else "Heartbeat Lost"
            tvHeartbeatSub.setTextColor(Color.parseColor("#EF4444"))
        } else if (isAlarmActiveOnServer || DeskMonitorService.isCurrentlyRinging) {
            tvStatusBadge.text = "● BREACH ALARM ACTIVE"
            tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
            tvStatusBadge.background = createPillDrawable(Color.parseColor("#3B0D0D"), Color.parseColor("#EF4444"))
        } else if (lastHeartbeatMs > 0L) {
            tvStatusBadge.text = "● DESK PROTECTED"
            tvStatusBadge.setTextColor(Color.parseColor("#22C55E"))
            tvStatusBadge.background = createPillDrawable(Color.parseColor("#052E16"), Color.parseColor("#22C55E"))

            tvHeartbeatValue.text = if (diffSec < 60) "${diffSec}s ago" else "${diffSec / 60}m ago"
            val timeFormatted = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(lastHeartbeatMs))
            tvHeartbeatSub.text = timeFormatted
            tvHeartbeatSub.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun refreshSecurityChecklistVisibility() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, CompanionAdminReceiver::class.java)
        val isAdminActive = dpm.isAdminActive(adminComp)

        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isNoBatteryRestrictions = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)

        val isAutostartDone = prefs.getBoolean("autostart_configured", false)

        // Toggle visibility: Hide completely if granted
        itemAdmin.visibility = if (isAdminActive) View.GONE else View.VISIBLE
        div1.visibility = itemAdmin.visibility

        itemOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        div2.visibility = itemOverlay.visibility

        itemAutostart.visibility = if (isAutostartDone) View.GONE else View.VISIBLE
        div3.visibility = itemAutostart.visibility

        itemBattery.visibility = if (isNoBatteryRestrictions) View.GONE else View.VISIBLE

        val allGranted = isAdminActive && hasOverlay && isAutostartDone && isNoBatteryRestrictions
        if (allGranted) {
            shieldCard.visibility = View.GONE
            tvShieldTitle.visibility = View.GONE
            viewAllDoneCard.visibility = View.VISIBLE
        } else {
            shieldCard.visibility = View.VISIBLE
            tvShieldTitle.visibility = View.VISIBLE
            viewAllDoneCard.visibility = View.GONE
        }
    }

    private fun connectDirectlyToFirebase() {
        val db = FirebaseDatabase.getInstance()
        dbRef = db.getReference(DeskConfig.FIREBASE_ROOT_NODE).child(DeskConfig.TARGET_DEVICE_ID)

        valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                isAlarmActiveOnServer = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false

                val bat = snapshot.child("battery_level").getValue(Int::class.java) ?: -1
                val charging = snapshot.child("is_charging").getValue(Boolean::class.java) ?: false
                if (bat >= 0) {
                    tvBatteryValue.text = "$bat%"
                    tvBatterySub.text = if (charging) "⚡ Charger Plugged" else "Discharging"
                    tvBatterySub.setTextColor(if (charging) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                }

                snapshot.child("last_heartbeat").getValue(Long::class.java)?.let {
                    lastHeartbeatMs = it
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

            override fun onCancelled(error: DatabaseError) {}
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
        prefs.edit().putBoolean("autostart_configured", true).apply()
        refreshSecurityChecklistVisibility()

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
