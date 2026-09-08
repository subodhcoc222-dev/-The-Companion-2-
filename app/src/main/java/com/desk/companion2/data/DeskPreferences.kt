package com.desk.companion2.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

data class RestSlot(
    val id: Int,
    var name: String,
    var enabled: Boolean,
    var startHour: Int,
    var startMinute: Int,
    var endHour: Int,
    var endMinute: Int
)

class DeskPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("DeskCompanionSecurePrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MASTER_PIN = "key_master_pin"
        private const val KEY_GUARD_ARMED = "key_guard_armed"
        private const val KEY_SNOOZE_MINUTES = "key_snooze_minutes"
    }

    // --- 1. Master PIN Management ---
    fun isPinSet(): Boolean = prefs.getString(KEY_MASTER_PIN, null) != null

    fun getMasterPin(): String = prefs.getString(KEY_MASTER_PIN, "") ?: ""

    fun setMasterPin(pin: String) {
        prefs.edit().putString(KEY_MASTER_PIN, pin).apply()
    }

    fun verifyPin(inputPin: String): Boolean = getMasterPin() == inputPin

    // --- 2. Master Guard State (ARM / DISARM) ---
    fun isGuardArmed(): Boolean = prefs.getBoolean(KEY_GUARD_ARMED, true)

    fun setGuardArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_GUARD_ARMED, armed).apply()
    }

    // --- 3. Breach Snooze Duration (1 to 5 Minutes) ---
    fun getSnoozeMinutes(): Int = prefs.getInt(KEY_SNOOZE_MINUTES, 5)

    fun setSnoozeMinutes(mins: Int) {
        val clamped = mins.coerceIn(1, 5)
        prefs.edit().putInt(KEY_SNOOZE_MINUTES, clamped).apply()
    }

    // --- 4. 4 Independent Rest Time Slots ---
    fun getRestSlots(): List<RestSlot> {
        val defaultNames = listOf("Night Rest", "Gym / Evening", "Spare Slot 3", "Spare Slot 4")
        val defaultEnabled = listOf(true, false, false, false)
        val defaultStartH = listOf(22, 17, 14, 0)
        val defaultStartM = listOf(0, 30, 0, 0)
        val defaultEndH = listOf(5, 19, 15, 0)
        val defaultEndM = listOf(0, 0, 0, 0)

        return (1..4).map { id ->
            RestSlot(
                id = id,
                name = prefs.getString("slot_${id}_name", defaultNames[id - 1]) ?: defaultNames[id - 1],
                enabled = prefs.getBoolean("slot_${id}_enabled", defaultEnabled[id - 1]),
                startHour = prefs.getInt("slot_${id}_sh", defaultStartH[id - 1]),
                startMinute = prefs.getInt("slot_${id}_sm", defaultStartM[id - 1]),
                endHour = prefs.getInt("slot_${id}_eh", defaultEndH[id - 1]),
                endMinute = prefs.getInt("slot_${id}_em", defaultEndM[id - 1])
            )
        }
    }

    fun saveRestSlot(slot: RestSlot) {
        prefs.edit()
            .putBoolean("slot_${slot.id}_enabled", slot.enabled)
            .putInt("slot_${slot.id}_sh", slot.startHour)
            .putInt("slot_${slot.id}_sm", slot.startMinute)
            .putInt("slot_${slot.id}_eh", slot.endHour)
            .putInt("slot_${slot.id}_em", slot.endMinute)
            .apply()
    }

    fun isRestTimeActive(): Boolean {
        val cal = Calendar.getInstance()
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        for (slot in getRestSlots()) {
            if (!slot.enabled) continue

            val startMinutes = slot.startHour * 60 + slot.startMinute
            val endMinutes = slot.endHour * 60 + slot.endMinute

            if (startMinutes <= endMinutes) {
                // Same day slot (e.g. 17:30 to 19:00)
                if (currentMinutes in startMinutes until endMinutes) return true
            } else {
                // Crosses midnight (e.g. 22:00 to 05:00)
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) return true
            }
        }
        return false
    }
}
