package com.desk.companion2.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.desk.companion2.DeskConfig
import com.desk.companion2.ui.AlarmOverlayActivity
import com.google.firebase.database.*
import java.util.Calendar

class DeskMonitorService : Service() {

    private val CHANNEL_ID = "DeskCompanion2ServiceChannel"
    private val WARNING_CHANNEL_ID = "DeskCompanion2WarningChannel"
    private val NOTIFICATION_ID = 8001

    private var dbRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var isAlarmActiveOnDesk = false
    private var isSnoozed = false
    private var isCurrentlyRinging = false
    private var lastHeartbeatTimestamp: Long = 0L

    private var lastSentWarningMinute = -1
    private val SNOOZE_PERIOD_MS = 5 * 60 * 1000L

    companion object {
        const val ACTION_SNOOZE = "ACTION_SNOOZE"
        var isOverlayVisible = false
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannels()
        acquireServiceWakeLock()

        startForeground(NOTIFICATION_ID, buildPermanentNotification("Monitoring Desk Sentry..."))
        connectToFirebase()
        startHeartbeatWatchdog()
        startVolumeLockLoop()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)

            val serviceChan = NotificationChannel(
                CHANNEL_ID,
                "Desk Monitor Permanent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(serviceChan)

            val warningChan = NotificationChannel(
                WARNING_CHANNEL_ID,
                "Heartbeat Warning Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
            }
            mgr.createNotificationChannel(warningChan)
        }
    }

    private fun acquireServiceWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Companion2::MonitorLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun buildPermanentNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desk Companion 2 (Active Guard)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun isNightWindow(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 5
    }

    private fun isMorningGraceWindow(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return hour == 5 && minute in 0..9
    }

    private fun connectToFirebase() {
        valueListener?.let { dbRef?.removeEventListener(it) }

        val targetId = DeskConfig.getTargetDeviceId(this)
        dbRef = FirebaseDatabase.getInstance()
            .getReference(DeskConfig.FIREBASE_ROOT_NODE)
            .child(targetId)

        valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.child("last_heartbeat").getValue(Long::class.java)?.let {
                    lastHeartbeatTimestamp = it
                }

                val deskAlarm = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false
                isAlarmActiveOnDesk = deskAlarm

                if (isAlarmActiveOnDesk) {
                    if (!isCurrentlyRinging && !isSnoozed) {
                        triggerAlarm("⚠️ STUDY BREACH! Left Desk / Buffer Expired.")
                    }
                } else {
                    if (isCurrentlyRinging || isOverlayVisible) {
                        dismissAlarmAndOverlay()
                    }
                    isSnoozed = false
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        dbRef?.addValueEventListener(valueListener!!)
    }

    private fun startHeartbeatWatchdog() {
        handler.post(object : Runnable {
            override fun run() {
                checkHeartbeatState()
                handler.postDelayed(this, 10000)
            }
        })
    }

    private fun checkHeartbeatState() {
        if (lastHeartbeatTimestamp == 0L) return

        val diffMs = System.currentTimeMillis() - lastHeartbeatTimestamp
        val elapsedMins = (diffMs / (60 * 1000)).toInt()

        if (isNightWindow()) {
            if (elapsedMins >= 10 && !isOverlayVisible) {
                launchOverlay("⚠️ Night Notice: Camera Phone is Offline / Powered Off.")
            }
            return
        }

        if (isMorningGraceWindow()) {
            val cal = Calendar.getInstance()
            val min = cal.get(Calendar.MINUTE)
            if (min % 2 == 0 && min != lastSentWarningMinute) {
                lastSentWarningMinute = min
                postWarningNotification("Camera Phone Offline during morning grace (${10 - min} mins left).")
            }
            return
        }

        if (elapsedMins in 2..10) {
            if (elapsedMins % 2 == 0 && elapsedMins != lastSentWarningMinute) {
                lastSentWarningMinute = elapsedMins
                postWarningNotification("Heartbeat Missed: Camera phone offline or net cut ($elapsedMins mins).")
            }
        } else if (elapsedMins > 10) {
            if (!isCurrentlyRinging && !isSnoozed) {
                triggerAlarm("⚠️ CRITICAL: Camera phone offline or powered off for >10 mins!")
            }
        } else {
            lastSentWarningMinute = -1
        }
    }

    private fun postWarningNotification(msg: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setContentTitle("⚠️ Desk Sentry Heartbeat Warning")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        mgr.notify(8002, notif)
    }

    private fun triggerAlarm(reason: String) {
        isCurrentlyRinging = true
        launchOverlay(reason)

        if (!isNightWindow()) {
            playLoudAlarmSound()
        }
    }

    private fun launchOverlay(reason: String) {
        isOverlayVisible = true
        val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("EXTRA_REASON", reason)
        }
        startActivity(intent)
    }

    private fun playLoudAlarmSound() {
        try {
            lockVolumeToMax()
            if (mediaPlayer == null) {
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, alertUri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            } else if (mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun lockVolumeToMax() {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (_: Exception) {}
    }

    private fun startVolumeLockLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (isCurrentlyRinging && !isNightWindow()) {
                    lockVolumeToMax()
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    fun snoozeAlarm() {
        muteAlarmSound()
        isSnoozed = true
        isOverlayVisible = false

        handler.postDelayed({
            isSnoozed = false
            dbRef?.get()?.addOnSuccessListener { snapshot ->
                val deskAlarm = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false
                val diffMs = System.currentTimeMillis() - lastHeartbeatTimestamp
                val elapsedMins = (diffMs / (60 * 1000)).toInt()

                if (deskAlarm || elapsedMins > 10) {
                    triggerAlarm("⚠️ SNOOZE EXPIRED: Student still absent from desk!")
                }
            }
        }, SNOOZE_PERIOD_MS)
    }

    private fun muteAlarmSound() {
        isCurrentlyRinging = false
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissAlarmAndOverlay() {
        muteAlarmSound()
        isOverlayVisible = false
        sendBroadcast(Intent("com.desk.companion2.CLOSE_OVERLAY"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SNOOZE) {
            snoozeAlarm()
        } else {
            // Reconnect if called after device ID change
            connectToFirebase()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        valueListener?.let { dbRef?.removeEventListener(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        val restartIntent = Intent(applicationContext, DeskMonitorService::class.java)
        startService(restartIntent)
    }
}
