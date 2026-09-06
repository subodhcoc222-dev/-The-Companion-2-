package com.desk.companion2.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
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
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvBatteryText: TextView
    private lateinit var tvHeartbeatText: TextView
    private lateinit var ivLivePhoto: ImageView
    private lateinit var btnRequestSnap: Button
    private lateinit var btnViewLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, DeskMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        buildDashboardUI()
        initFirebaseListeners()
    }

    private fun buildDashboardUI() {
        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0F172A")) // Slate dark
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 36)
        }

        val tvTitle = TextView(this).apply {
            text = "🛡️ Desk Companion 2"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub = TextView(this).apply {
            text = "Paired with Desk ID: ${DeskConfig.TARGET_DEVICE_ID}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 2, 0, 20)
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 14f
            }
            setPadding(20, 16, 20, 16)
        }

        tvConnectionStatus = TextView(this).apply {
            text = "Status: CONNECTING..."
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvBatteryText = TextView(this).apply {
            text = "🔋 Camera Battery: --"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, 6, 0, 2)
        }
        tvHeartbeatText = TextView(this).apply {
            text = "⏱️ Last Heartbeat: Waiting..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }

        statusCard.addView(tvConnectionStatus)
        statusCard.addView(tvBatteryText)
        statusCard.addView(tvHeartbeatText)

        btnRequestSnap = Button(this).apply {
            text = "📸 Request Live Desk Photo"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            setPadding(0, 14, 0, 14)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 18, 0, 12)
            layoutParams = params
            setOnClickListener { requestSnapshot() }
        }

        ivLivePhoto = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500)
            setBackgroundColor(Color.parseColor("#1E293B"))
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        btnViewLogs = Button(this).apply {
            text = "📊 View Study Logs & History"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0F766E"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 14, 0, 24)
            layoutParams = params
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EventsActivity::class.java))
            }
        }

        val tvSetupHeader = TextView(this).apply {
            text = "🔒 System Anti-Tamper Setup"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 10)
        }

        val btnAdmin = Button(this).apply {
            text = "1. Enable Device Admin (Anti-Uninstall)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { requestAdmin() }
        }

        val btnOverlay = Button(this).apply {
            text = "2. Allow Display Over Apps (Alarm Popup)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { requestOverlayPermission() }
        }

        val btnOemAutostart = Button(this).apply {
            text = "3. Enable Autostart (MI / Oppo / Vivo)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { openOemAutostartSettings() }
        }

        val btnBatterySaver = Button(this).apply {
            text = "4. Battery Saver: No Restrictions"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { requestIgnoreBatteryOptimization() }
        }

        container.addView(tvTitle)
        container.addView(tvSub)
        container.addView(statusCard)
        container.addView(btnRequestSnap)
        container.addView(ivLivePhoto)
        container.addView(btnViewLogs)
        container.addView(tvSetupHeader)
        container.addView(btnAdmin)
        container.addView(btnOverlay)
        container.addView(btnOemAutostart)
        container.addView(btnBatterySaver)

        root.addView(container)
        setContentView(root)
    }

    private fun initFirebaseListeners() {
        dbRef = FirebaseDatabase.getInstance()
            .getReference(DeskConfig.FIREBASE_ROOT_NODE)
            .child(DeskConfig.TARGET_DEVICE_ID)

        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: "OFFLINE"
                val isAlarm = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false

                if (isAlarm) {
                    tvConnectionStatus.text = "Status: 🚨 ALARM RINGING (BREACH)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                } else if (status == "ONLINE") {
                    tvConnectionStatus.text = "Status: ● ONLINE (Protected)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#22C55E"))
                } else {
                    tvConnectionStatus.text = "Status: ○ OFFLINE"
                    tvConnectionStatus.setTextColor(Color.GRAY)
                }

                val bat = snapshot.child("battery_level").getValue(Int::class.java) ?: -1
                val charging = snapshot.child("is_charging").getValue(Boolean::class.java) ?: false
                tvBatteryText.text = "🔋 Camera Battery: $bat% ${if (charging) "⚡ (Charging)" else ""}"

                val lastBeat = snapshot.child("last_heartbeat").getValue(Long::class.java) ?: 0L
                if (lastBeat > 0) {
                    val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(lastBeat))
                    val diffSec = (System.currentTimeMillis() - lastBeat) / 1000
                    tvHeartbeatText.text = "⏱️ Last Beat: $timeStr (${diffSec}s ago)"
                }

                val base64Img = snapshot.child("latest_snapshot_base64").getValue(String::class.java)
                if (!base64Img.isNullOrEmpty()) {
                    try {
                        val decodedBytes = Base64.decode(base64Img, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        ivLivePhoto.setImageBitmap(bmp)
                        ivLivePhoto.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun requestSnapshot() {
        dbRef.child("commands").child("request_snap").setValue(true)
        Toast.makeText(this, "Requested photo from camera phone...", Toast.LENGTH_SHORT).show()
    }

    private fun requestAdmin() {
        val comp = ComponentName(this, CompanionAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects Companion 2 from uninstallation.")
        }
        startActivity(intent)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Battery optimization already set to No Restrictions!", Toast.LENGTH_SHORT).show()
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
}
