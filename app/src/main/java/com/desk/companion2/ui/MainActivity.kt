package com.desk.companion2.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.desk.companion2.data.DeskPreferences
import com.desk.companion2.data.RestSlot
import com.desk.companion2.service.DeskMonitorService

class MainActivity : AppCompatActivity() {

    private lateinit var deskPrefs: DeskPreferences
    private lateinit var statusTextView: TextView
    private lateinit var guardToggleButton: ToggleButton
    private lateinit var settingsButton: Button
    private lateinit var infoTextView: TextView

    private val overlayCloseReceiver = object : BroadcastReceiver() {
        onReceive(context: Context?, intent: Intent?) {
            // Handled or refreshed if needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deskPrefs = DeskPreferences(this)

        // Ensure background service is running
        val serviceIntent = Intent(this, DeskMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setupUI()

        // First-launch Master PIN Setup check
        if (!deskPrefs.isPinSet()) {
            showFirstLaunchPinSetupDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDashboardUI()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "🛡️ Desk Companion 2"
            textSize = 24f
            setTextColor(Color.WHITE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        statusTextView = TextView(this).apply {
            text = "Checking status..."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        layout.addView(statusTextView)

        guardToggleButton = ToggleButton(this).apply {
            textOn = "GUARD ARMED (Tap to Disarm)"
            textOff = "GUARD DISARMED (Tap to Arm)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1B5E20"))
            setPadding(32, 32, 32, 32)
            setOnbuttonClickListener()
        }
        
        // Custom click handling for Master PIN security on toggle
        guardToggleButton.setOnClickListener {
            // Reverse toggle state temporarily until PIN is verified
            val targetState = !guardToggleButton.isChecked
            guardToggleButton.isChecked = !targetState

            showPinVerificationDialog("Enter Master PIN to change Guard State") { verified ->
                if (verified) {
                    val newState = !targetState
                    deskPrefs.setGuardArmed(newState)
                    guardToggleButton.isChecked = newState
                    updateDashboardUI()
                    
                    // Notify service of state change
                    val intent = Intent(this, DeskMonitorService::class.java).apply {
                        action = DeskMonitorService.ACTION_STATE_CHANGED
                    }
                    startService(intent)
                }
            }
        }
        layout.addView(guardToggleButton)

        settingsButton = Button(this).apply {
            text = "⚙️ Secure Settings (PIN Protected)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                showPinVerificationDialog("Enter Master PIN to access Settings") { verified ->
                    if (verified) {
                        showSecureSettingsDialog()
                    }
                }
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 48 }
        layout.addView(settingsButton, params)

        infoTextView = TextView(this).apply {
            text = "Local Master PIN secured • Multi-slot Rest Active • 1-5m Snooze Ready"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        layout.addView(infoTextView)

        setContentView(layout)
        updateDashboardUI()
    }

    private fun updateDashboardUI() {
        val isArmed = deskPrefs.isGuardArmed()
        guardToggleButton.isChecked = isArmed
        
        if (!isArmed) {
            statusTextView.text = "STATUS: PAUSED / DISARMED\n(Protected by Master PIN)"
            statusTextView.setTextColor(Color.parseColor("#FF5252"))
            guardToggleButton.setBackgroundColor(Color.parseColor("#B71C1C"))
        } else if (deskPrefs.isRestTimeActive()) {
            statusTextView.text = "STATUS: REST SCHEDULE ACTIVE\n(Temporarily Silent)"
            statusTextView.setTextColor(Color.parseColor("#FFD54F"))
            guardToggleButton.setBackgroundColor(Color.parseColor("#F57F17"))
        } else {
            statusTextView.text = "STATUS: ● DESK PROTECTED\n(Active Monitoring)"
            statusTextView.setTextColor(Color.parseColor("#69F0AE"))
            guardToggleButton.setBackgroundColor(Color.parseColor("#1B5E20"))
        }
    }

    private fun showFirstLaunchPinSetupDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val msg = TextView(this).apply {
            text = "Welcome! Set your 4-Digit Master PIN to secure this application."
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
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
            .setTitle("🔒 Set Master PIN")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Save PIN") { _, _ ->
                val p1 = input1.text.toString().trim()
                val p2 = input2.text.toString().trim()

                if (p1.length == 4 && p1 == p2) {
                    deskPrefs.setMasterPin(p1)
                    Toast.makeText(this, "Master PIN Saved Successfully!", Toast.LENGTH_SHORT).show()
                    updateDashboardUI()
                } else {
                    Toast.makeText(this, "PINs must match and be 4 digits!", Toast.LENGTH_LONG).show()
                    showFirstLaunchPinSetupDialog()
                }
            }
            .show()
    }

    private fun showPinVerificationDialog(titleText: String, onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            hint = "Enter Master PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val entered = input.text.toString().trim()
                if (deskPrefs.verifyPin(entered)) {
                    onResult(true)
                } else {
                    Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
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
            "⏱️ Set Breach Snooze Timer (1-5 Min)",
            "🌙 Configure 4 Rest Time Slots"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Secure Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showChangePinDialog()
                    1 -> showSnoozeConfigDialog()
                    2 -> showRestSlotsDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showChangePinDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
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
            hint = "Confirm New 4-Digit PIN"
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
                        Toast.makeText(this, "PIN Updated Successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "New PIN must be 4 digits and match!", Toast.LENGTH_LONG).show()
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
            .setTitle("⏱️ Select Breach Snooze Duration")
            .setSingleChoiceItems(choices, checkedIndex) { dialog, which ->
                val selectedMins = which + 1
                deskPrefs.setSnoozeMinutes(selectedMins)
                Toast.makeText(this, "Snooze set to $selectedMins minutes", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRestSlotsDialog() {
        val slots = deskPrefs.getRestSlots()
        val slotTitles = slots.map { "${it.name} (${if (it.enabled) "ON" else "OFF"}) - ${String.format("%02d:%02d", it.startHour, it.startMinute)} to ${String.format("%02d:%02d", it.endHour, it.endMinute)}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("🌙 Rest Time Slots (Tap to Edit)")
            .setItems(slotTitles) { _, which ->
                showEditSingleSlotDialog(slots[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEditSingleSlotDialog(slot: RestSlot) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val enableCheck = CheckBox(this).apply {
            text = "Enable ${slot.name}"
            isChecked = slot.enabled
        }
        layout.addView(enableCheck)

        val timeBtn = Button(this).apply {
            text = "Set Start & End Time"
            var tempSH = slot.startHour
            var tempSM = slot.startMinute
            var tempEH = slot.endHour
            var tempEM = slot.endMinute

            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, sh, sm ->
                    tempSH = sh
                    tempSM = sm
                    TimePickerDialog(this@MainActivity, { _, eh, em ->
                        tempEH = eh
                        tempEM = em
                        slot.startHour = tempSH
                        slot.startMinute = tempSM
                        slot.endHour = tempEH
                        slot.endMinute = tempEM
                        text = "Time: ${String.format("%02d:%02d", tempSH, tempSM)} - ${String.format("%02d:%02d", tempEH, tempEM)}"
                    }, tempEH, tempEM, true).show()
                }, tempSH, tempSM, true).show()
            }
        }
        layout.addView(timeBtn)

        AlertDialog.Builder(this)
            .setTitle("Edit Slot: ${slot.name}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                slot.enabled = enableCheck.isChecked
                deskPrefs.saveRestSlot(slot)
                Toast.makeText(this, "Rest Slot Updated!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
