package com.desk.companion2

import android.content.Context

object DeskConfig {
    // Aapke camera phone ki active Cloud ID
    const val TARGET_DEVICE_ID = "349806"
    const val FIREBASE_ROOT_NODE = "desk_sentry"

    private const val PREFS_NAME = "DeskCompanionPrefs"
    private const val KEY_PAIRED_DEVICE_ID = "paired_target_id"

    fun getTargetDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_DEVICE_ID, TARGET_DEVICE_ID) ?: TARGET_DEVICE_ID
    }

    fun setTargetDeviceId(context: Context, newId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PAIRED_DEVICE_ID, newId.trim()).apply()
    }
}
