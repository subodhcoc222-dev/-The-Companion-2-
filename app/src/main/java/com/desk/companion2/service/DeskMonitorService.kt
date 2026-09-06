package com.desk.companion2.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import androidx.core.app.NotificationCompat
import com.desk.companion2.DeskConfig
import com.desk.companion2.ui.AlarmOverlayActivity
import com.google.firebase.database.*
import java.util.Calendar

class DeskMonitorService : Service() {

    private val CHANNEL_ID = "DeskCompanion2ServiceChannel_v3"
    private val ALARM_CHANNEL_ID = "DeskCompanion2AlarmChannel_v3"
    private val WARNING_CHANNEL_ID = "DeskCompanion2WarningChannel_v3"
    private val NOTIFICATION_ID = 8001
    private val ALARM_NOTIFICATION_ID = 8099
    private val DISCONNECT_NOTIF_ID = 8098

    private lateinit var dbRef: DatabaseReference
    private var valueListener: ValueEventListener? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var isAlarmActiveOnDesk = false
    private var isSnoozed = false
    private var lastHeartbeatTimestamp: Long = 0L

    // Disconnect Timing & Escalation
    private var disconnectStartTime: Long = 0L
    private var lastReportedMinute: Int = -1

    private val SNOOZE_PERIOD_MS = 5 * 60 * 1000L

    companion object {
        const val ACTION_SNOOZE = "ACTION_SNOOZE"
        var isOverlayVisible = false
        var isCurrentlyRinging = false
        var isDeskConnected = false
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createHighPriorityChannels()
        acquireServiceWakeLock()

        startForeground(NOTIFICATION_ID, buildPermanentNotification("Monitoring Desk Sentry..."))
        connectDirectlyToFirebase()
        startFastWatchdog()
        startVolumeLockLoop()
    }

    private fun createHighPriorityChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // 1. Silent Ongoing Service
            val serviceChan = NotificationChannel(
                CHANNEL_ID,
                "Desk Monitor Permanent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(serviceChan)

            // 2. Loud Emergency Breach Channel
            val alarmChan = NotificationChannel(
                ALARM_CHANNEL_ID,
                "Emergency Desk Breach Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audioAttr)
            }
            mgr.createNotificationChannel(alarmChan)

            // 3. High-Priority Warning & Disconnect Alerts with Sound + Vibration
            val warningChan = NotificationChannel(
                WARNING_CHANNEL_ID,
                "Desk Disconnection Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(soundUri, audioAttr)
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

    private fun isCompanionOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun connectDirectlyToFirebase() {
        dbRef = FirebaseDatabase.getInstance()
            .getReference(DeskConfig.FIREBASE_ROOT_NODE)
            .child(DeskConfig.TARGET_DEVICE_ID)

        valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.child("last_heartbeat").getValue(Long::class.java)?.let {
                    lastHeartbeatTimestamp = it
                }

                val deskAlarm = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false
                isAlarmActiveOnDesk = deskAlarm

                if (isAlarmActiveOnDesk) {
                    if (!isCurrentlyRinging && !isSnoozed) {
                        triggerAlarm("⚠️ STUDY BREACH! Student Left Desk.")
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
        dbRef.addValueEventListener(valueListener!!)
    }

    private fun startFastWatchdog() {
        handler.post(object : Runnable {
            override fun run() {
                checkConnectionHealth()
                handler.postDelayed(this, 2000) // Har 2 second me check
            }
        })
    }

    private fun checkConnectionHealth() {
        val now = System.currentTimeMillis()
        val hasInternet = isCompanionOnline()
        val heartbeatAgeSec = if (lastHeartbeatTimestamp > 0L) (now - lastHeartbeatTimestamp) / 1000 else 999L

        val isDisconnected = !hasInternet || (lastHeartbeatTimestamp > 0L && heartbeatAgeSec > 15L)

        if (isDisconnected) {
            isDeskConnected = false

            if (disconnectStartTime == 0L) {
                // 1. Instant Disconnect Alert (0 Second)
                disconnectStartTime = now
                lastReportedMinute = 0
                val reason = if (!hasInternet) "Wi-Fi / Mobile Data Disconnected on Companion Phone!" else "Desk Camera Heartbeat Lost (>15s)!"
                postEscalatingNotification("⚠️ DESK DISCONNECTED (Just Now)", reason)
            } else {
                // 2. Periodic Escalating Warnings: 2 min, 5 min, 6 min, 10 min...
                val elapsedMins = ((now - disconnectStartTime) / (60 * 1000)).toInt()
                if (elapsedMins > 0 && elapsedMins != lastReportedMinute) {
                    if (elapsedMins == 2 || elapsedMins == 5 || elapsedMins == 6 || elapsedMins % 5 == 0) {
                        lastReportedMinute = elapsedMins
                        val reason = "Desk has been disconnected for $elapsedMins minute(s)! Check camera phone & internet."
                        postEscalatingNotification("⚠️ DESK STILL DISCONNECTED ($elapsedMins Mins)", reason)
                    }
                }
            }
        } else {
            // Reconnected successfully
            isDeskConnected = true
            if (disconnectStartTime != 0L) {
                disconnectStartTime = 0L
                lastReportedMinute = -1
                cancelDisconnectNotification()
            }
        }
    }

    private fun postEscalatingNotification(title: String, msg: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notif = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        mgr.notify(DISCONNECT_NOTIF_ID, notif)
    }

    private fun cancelDisconnectNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(DISCONNECT_NOTIF_ID)
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

        val overlayIntent = Intent(this, AlarmOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_REASON", reason)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            overlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, DeskMonitorService::class.java).apply {
            action = ACTION_SNOOZE
        }
        val snoozePendingIntent = PendingIntent.getService(
            this,
            1002,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmNotif = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle("🚨 STUDY BREACH DETECTED!")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "SNOOZE (5 MINS)", snoozePendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(ALARM_NOTIFICATION_ID, alarmNotif)

        try {
            startActivity(overlayIntent)
        } catch (_: Exception) {}
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

        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(ALARM_NOTIFICATION_ID)

        handler.postDelayed({
            isSnoozed = false
            dbRef.get().addOnSuccessListener { snapshot ->
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
        } catch (e: Exception) {}
    }

    private fun dismissAlarmAndOverlay() {
        muteAlarmSound()
        isOverlayVisible = false

        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(ALARM_NOTIFICATION_ID)

        sendBroadcast(Intent("com.desk.companion2.CLOSE_OVERLAY"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SNOOZE) {
            snoozeAlarm()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        valueListener?.let { dbRef.removeEventListener(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        val restartIntent = Intent(applicationContext, DeskMonitorService::class.java)
        startService(restartIntent)
    }
}
