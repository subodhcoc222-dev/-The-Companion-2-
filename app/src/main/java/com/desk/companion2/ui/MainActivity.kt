package com.desk.companion2.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.desk.companion2.DeskConfig
import com.desk.companion2.data.DeskPreferences
import com.desk.companion2.data.RestSlot
import com.desk.companion2.service.DeskMonitorService
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var deskPrefs: DeskPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI Elements
    private lateinit var statusBadge: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var snapshotImageView: ImageView
    private lateinit var snapshotTimeText: TextView
    private lateinit var snapshotPlaceholderText: TextView
    private lateinit var guardStateCard: LinearLayout
    private lateinit var guardSwitch: Switch
    private lateinit var guardStateTitle: TextView
    private lateinit var guardStateSubtitle: TextView

    // Firebase Listener for Live UI & Snapshot
    private lateinit var dbRef: DatabaseReference
    private var fbListener: ValueEventListener? = null

    private val overlayCloseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDashboardUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deskPrefs = DeskPreferences(this)

        // Ensure Persistent Monitor Service is running
        val serviceIntent = Intent(this, DeskMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        buildProfessionalUI()

        // Check if Master PIN needs initial setup
        if (!deskPrefs.isPinSet()) {
            showFirstLaunchPinSetupDialog()
        }

        setupFirebaseLiveSync()

        val filter = IntentFilter("com.desk.companion2.CLOSE_OVERLAY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayCloseReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayCloseReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDashboardUI()
    }

    private fun buildProfessionalUI() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0F1115"))
            isFillViewport = true
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 64)
        }
        scrollView.addView(rootLayout)

        // 1. Header Section
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 36)
        }
        val appTitle = TextView(this).apply {
            text = "🛡️ DESK COMPANION 2"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            letterSpacing = 0.05f
        }
        val appSubtitle = TextView(this).apply {
            text = "Autonomous Sentinel & Camera Monitor"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 6, 0, 0)
        }
        headerLayout.addView(appTitle)
        headerLayout.addView(appSubtitle)
        rootLayout.addView(headerLayout)

        // 2. Live Status Badge Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#181B22"), 24f)
            setPadding(36, 32, 36, 32)
        }
        statusBadge = TextView(this).apply {
            text = "● INITIALIZING..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#69F0AE"))
        }
        statusDetailText = TextView(this).apply {
            text = "Synchronizing with desk node..."
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 8, 0, 0)
        }
        statusCard.addView(statusBadge)
        statusCard.addView(statusDetailText)
        rootLayout.addView(statusCard)

        // Spacing
        rootLayout.addView(createSpacer(28))

        // 3. Camera Snapshot Preview Card
        val snapshotCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#181B22"), 24f)
            setPadding(32, 28, 32, 32)
        }

        val snapshotHeader = RelativeLayout(this).apply {
            setPadding(0, 0, 0, 20)
        }
        val snapshotTitle = TextView(this).apply {
            text = "📷 DESK LIVE SNAPSHOT"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        val titleParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_START) }
        snapshotHeader.addView(snapshotTitle, titleParams)

        snapshotTimeText = TextView(this).apply {
            text = "Awaiting photo..."
            textSize = 11f
            setTextColor(Color.parseColor("#78909C"))
        }
        val timeParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
        snapshotHeader.addView(snapshotTimeText, timeParams)
        snapshotCard.addView(snapshotHeader)

        // Image Frame Container
        val imageContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                540
            )
            background = createCardDrawable(Color.parseColor("#0B0C0E"), 18f)
        }

        snapshotImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        imageContainer.addView(snapshotImageView)

        snapshotPlaceholderText = TextView(this).apply {
            text = "No Camera Snapshot Received Yet"
            textSize = 13f
            setTextColor(Color.parseColor("#546E7A"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        imageContainer.addView(snapshotPlaceholderText)
        snapshotCard.addView(imageContainer)
        rootLayout.addView(snapshotCard)

        // Spacing
        rootLayout.addView(createSpacer(28))

        // 4. Master Guard Switch Card
        guardStateCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardDrawable(Color.parseColor("#1B2E1E"), 24f, Color.parseColor("#2E7D32"), 2)
            setPadding(36, 32, 36, 32)
        }

        val guardTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        guardStateTitle = TextView(this).apply {
            text = "GUARD IS ARMED"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        guardStateSubtitle = TextView(this).apply {
            text = "Active desk breach detection enabled"
            textSize = 11f
            setTextColor(Color.parseColor("#A5D6A7"))
            setPadding(0, 4, 0, 0)
        }
        guardTextLayout.addView(guardStateTitle)
        guardTextLayout.addView(guardStateSubtitle)
        guardStateCard.addView(guardTextLayout)

        guardSwitch = Switch(this).apply {
            scaleX = 1.25f
            scaleY = 1.25f
        }
        guardSwitch.setOnClickListener {
            val targetArmed = guardSwitch.isChecked
            // Revert switch visually until PIN verified
            guardSwitch.isChecked = !targetArmed

            val prompt = if (targetArmed) "Enter Master PIN to ARM Guard" else "Enter Master PIN to DISARM Guard"
            showPinVerificationDialog(prompt) { verified ->
                if (verified) {
                    deskPrefs.setGuardArmed(targetArmed)
                    guardSwitch.isChecked = targetArmed
                    updateDashboardUI()

                    val intent = Intent(this, DeskMonitorService::class.java).apply {
                        action = DeskMonitorService.ACTION_STATE_CHANGED
                    }
                    startService(intent)
                }
            }
        }
        guardStateCard.addView(guardSwitch)
        rootLayout.addView(guardStateCard)

        // Spacing
        rootLayout.addView(createSpacer(24))

        // 5. Secure Settings Button Card
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardDrawable(Color.parseColor("#1E242B"), 20f)
            setPadding(36, 32, 36, 32)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showPinVerificationDialog("Enter Master PIN to open Settings") { verified ->
                    if (verified) {
                        showSecureSettingsDialog()
                    }
                }
            }
        }

        val settingsIcon = TextView(this).apply {
            text = "⚙️"
            textSize = 22f
            setPadding(0, 0, 24, 0)
        }
        settingsCard.addView(settingsIcon)

        val settingsTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val settingsTitle = TextView(this).apply {
            text = "Sentinel Security Settings"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val settingsSub = TextView(this).apply {
            text = "Master PIN • 4 Rest Slots • Breach Snooze Timer"
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 4, 0, 0)
        }
        settingsTextLayout.addView(settingsTitle)
        settingsTextLayout.addView(settingsSub)
        settingsCard.addView(settingsTextLayout)

        val chevron = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.parseColor("#78909C"))
        }
        settingsCard.addView(chevron)
        rootLayout.addView(settingsCard)

        setContentView(scrollView)
        updateDashboardUI()
    }

    private fun setupFirebaseLiveSync() {
        dbRef = FirebaseDatabase.getInstance()
            .getReference(DeskConfig.FIREBASE_ROOT_NODE)
            .child(DeskConfig.TARGET_DEVICE_ID)

        fbListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // 1. Snapshot processing
                val base64Image = snapshot.child("last_snapshot").getValue(String::class.java)
                    ?: snapshot.child("snapshot_base64").getValue(String::class.java)

                if (!base64Image.isNullOrEmpty()) {
                    try {
                        val cleanBase64 = if (base64Image.contains(",")) {
                            base64Image.substringAfter(",")
                        } else {
                            base64Image
                        }
                        val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                        if (bitmap != null) {
                            snapshotImageView.setImageBitmap(bitmap)
                            snapshotImageView.visibility = View.VISIBLE
                            snapshotPlaceholderText.visibility = View.GONE
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 2. Timestamp update
                val lastHb = snapshot.child("last_heartbeat").getValue(Long::class.java) ?: 0L
                if (lastHb > 0L) {
                    val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                    snapshotTimeText.text = "Sync: ${sdf.format(Date(lastHb))}"
                }

                updateDashboardUI()
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        dbRef.addValueEventListener(fbListener!!)
    }

    private fun updateDashboardUI() {
        val isArmed = deskPrefs.isGuardArmed()
        guardSwitch.isChecked = isArmed

        if (!isArmed) {
            statusBadge.text = "⏸️ GUARD DISARMED / PAUSED"
            statusBadge.setTextColor(Color.parseColor("#EF5350"))
            statusDetailText.text = "Background service active • Alarms & TTS muted by PIN"

            guardStateCard.background = createCardDrawable(Color.parseColor("#2A1717"), 24f, Color.parseColor("#D32F2F"), 2)
            guardStateTitle.text = "GUARD IS DISARMED"
            guardStateTitle.setTextColor(Color.parseColor("#FFCDD2"))
            guardStateSubtitle.text = "Tap switch and verify Master PIN to re-arm"
            guardStateSubtitle.setTextColor(Color.parseColor("#E57373"))
        } else if (deskPrefs.isRestTimeActive()) {
            statusBadge.text = "🌙 REST SCHEDULE ACTIVE"
            statusBadge.setTextColor(Color.parseColor("#FFD54F"))
            statusDetailText.text = "App is in scheduled quiet hours • Zero false sirens"

            guardStateCard.background = createCardDrawable(Color.parseColor("#2E2816"), 24f, Color.parseColor("#F57F17"), 2)
            guardStateTitle.text = "REST TIME SCHEDULED"
            guardStateTitle.setTextColor(Color.parseColor("#FFE082"))
            guardStateSubtitle.text = "Monitoring active but currently silent"
            guardStateSubtitle.setTextColor(Color.parseColor("#FFD54F"))
        } else {
            statusBadge.text = "● DESK PROTECTED & ARMED"
            statusBadge.setTextColor(Color.parseColor("#00E676"))
            statusDetailText.text = "Heartbeat & breach sentinels operational"

            guardStateCard.background = createCardDrawable(Color.parseColor("#122617"), 24f, Color.parseColor("#2E7D32"), 2)
            guardStateTitle.text = "GUARD IS ARMED"
            guardStateTitle.setTextColor(Color.WHITE)
            guardStateSubtitle.text = "Instant 0s sirens & emergency hotline alert active"
            guardStateSubtitle.setTextColor(Color.parseColor("#A5D6A7"))
        }
    }

    private fun showFirstLaunchPinSetupDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        val msg = TextView(this).apply {
            text = "Welcome! Set your private 4-digit Master PIN. This secures your Guard state, Rest slots, and alarm intervention."
            textSize = 13f
            setTextColor(Color.parseColor("#CFD8DC"))
            setPadding(0, 0, 0, 32)
        }
        dialogView.addView(msg)

        val input1 = EditText(this).apply {
            hint = "Enter 4-Digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        dialogView.addView(input1)

        val input2 = EditText(this).apply {
            hint = "Confirm 4-Digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        dialogView.addView(input2)

        AlertDialog.Builder(this)
            .setTitle("🔒 Create Master PIN")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Save & Arm") { _, _ ->
                val p1 = input1.text.toString().trim()
                val p2 = input2.text.toString().trim()

                if (p1.length == 4 && p1 == p2) {
                    deskPrefs.setMasterPin(p1)
                    Toast.makeText(this, "Master PIN established!", Toast.LENGTH_SHORT).show()
                    updateDashboardUI()
                } else {
                    Toast.makeText(this, "PINs must match and be 4 numeric digits!", Toast.LENGTH_LONG).show()
                    showFirstLaunchPinSetupDialog()
                }
            }
            .show()
    }

    private fun showPinVerificationDialog(titleText: String, onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            hint = "Enter 4-Digit Master PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 40, 48, 40)
        }

        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val entered = input.text.toString().trim()
                if (deskPrefs.verifyPin(entered)) {
                    onResult(true)
                } else {
                    Toast.makeText(this, "Incorrect PIN! Access Denied.", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onResult(false) }
            .setCancelable(false)
            .show()
    }

    private fun showSecureSettingsDialog() {
        val options = arrayOf(
            "🔑 Change Master PIN",
            "⏱️ Set Breach Snooze Timer (${deskPrefs.getSnoozeMinutes()} Min)",
            "🌙 Configure 4 Rest Time Slots"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Master Security Configuration")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showChangePinDialog()
                    1 -> showSnoozeConfigDialog()
                    2 -> showRestSlotsDialog()
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showChangePinDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }
        val currentInput = EditText(this).apply {
            hint = "Current PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newInput = EditText(this).apply {
            hint = "New 4-Digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmInput = EditText(this).apply {
            hint = "Confirm New PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(currentInput)
        layout.addView(newInput)
        layout.addView(confirmInput)

        AlertDialog.Builder(this)
            .setTitle("🔑 Change Master PIN")
            .setView(layout)
            .setPositiveButton("Update") { _, _ ->
                val curr = currentInput.text.toString().trim()
                val n1 = newInput.text.toString().trim()
                val n2 = confirmInput.text.toString().trim()

                if (deskPrefs.verifyPin(curr)) {
                    if (n1.length == 4 && n1 == n2) {
                        deskPrefs.setMasterPin(n1)
                        Toast.makeText(this, "Master PIN updated successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "New PIN must be exactly 4 digits and match!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Current PIN is incorrect!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSnoozeConfigDialog() {
        val currentMins = deskPrefs.getSnoozeMinutes()
        val choices = arrayOf("1 Minute", "2 Minutes", "3 Minutes", "4 Minutes", "5 Minutes")
        val checkedIndex = (currentMins - 1).coerceIn(0, 4)

        AlertDialog.Builder(this)
            .setTitle("⏱️ Breach Snooze Duration")
            .setSingleChoiceItems(choices, checkedIndex) { dialog, which ->
                val selectedMins = which + 1
                deskPrefs.setSnoozeMinutes(selectedMins)
                Toast.makeText(this, "Snooze duration set to $selectedMins minutes", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRestSlotsDialog() {
        val slots = deskPrefs.getRestSlots()
        val slotTitles = slots.map {
            val status = if (it.enabled) "ON" else "OFF"
            val start = String.format(Locale.getDefault(), "%02d:%02d", it.startHour, it.startMinute)
            val end = String.format(Locale.getDefault(), "%02d:%02d", it.endHour, it.endMinute)
            "${it.name} [$status] - $start to $end"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("🌙 Rest Time Slots (Tap to Configure)")
            .setItems(slotTitles) { _, which ->
                showEditSingleSlotDialog(slots[which])
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showEditSingleSlotDialog(slot: RestSlot) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        val enableCheck = CheckBox(this).apply {
            text = "Enable ${slot.name}"
            textSize = 15f
            isChecked = slot.enabled
        }
        layout.addView(enableCheck)

        var tempSH = slot.startHour
        var tempSM = slot.startMinute
        var tempEH = slot.endHour
        var tempEM = slot.endMinute

        val timeBtn = Button(this).apply {
            text = "Time: ${String.format(Locale.getDefault(), "%02d:%02d", tempSH, tempSM)} to ${String.format(Locale.getDefault(), "%02d:%02d", tempEH, tempEM)}"
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, sh, sm ->
                    tempSH = sh
                    tempSM = sm
                    TimePickerDialog(this@MainActivity, { _, eh, em ->
                        tempEH = eh
                        tempEM = em
                        text = "Time: ${String.format(Locale.getDefault(), "%02d:%02d", tempSH, tempSM)} to ${String.format(Locale.getDefault(), "%02d:%02d", tempEH, tempEM)}"
                    }, tempEH, tempEM, true).show()
                }, tempSH, tempSM, true).show()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 }
        layout.addView(timeBtn, btnParams)

        AlertDialog.Builder(this)
            .setTitle("Configure: ${slot.name}")
            .setView(layout)
            .setPositiveButton("Save Slot") { _, _ ->
                slot.enabled = enableCheck.isChecked
                slot.startHour = tempSH
                slot.startMinute = tempSM
                slot.endHour = tempEH
                slot.endMinute = tempEM
                deskPrefs.saveRestSlot(slot)
                Toast.makeText(this, "Slot updated!", Toast.LENGTH_SHORT).show()
                updateDashboardUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createCardDrawable(bgColor: Int, cornerRadius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            this.cornerRadius = cornerRadius
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun createSpacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightDp
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fbListener?.let { dbRef.removeEventListener(it) }
        try {
            unregisterReceiver(overlayCloseReceiver)
        } catch (_: Exception) {}
    }
}
