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
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.desk.companion2.DeskConfig
import com.desk.companion2.data.DeskPreferences
import com.desk.companion2.ui.AlarmOverlayActivity
import com.google.firebase.database.*
import java.util.Locale

class DeskMonitorService : Service(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "DeskCompanion2ServiceChannel_v5"
    private val ALARM_CHANNEL_ID = "DeskCompanion2AlarmChannel_v5"
    private val WARNING_CHANNEL_ID = "DeskCompanion2WarningChannel_v5"
    private val NOTIFICATION_ID = 8001
    private val ALARM_NOTIFICATION_ID = 8099
    private val DISCONNECT_NOTIF_ID = 8098

    private lateinit var dbRef: DatabaseReference
    private var valueListener: ValueEventListener? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    private lateinit var deskPrefs: DeskPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var isAlarmActiveOnDesk = false
    private var isSnoozed = false
    private var lastHeartbeatTimestamp: Long = 0L

    // Disconnection & Voice Timeline Variables
    private var disconnectStartTime: Long = 0L
    private var hasSpokenDisconnect = false
    private var isCriticalDisconnectAlarmFired = false

    companion object {
        const val ACTION_SNOOZE = "ACTION_SNOOZE"
        const val ACTION_STATE_CHANGED = "ACTION_STATE_CHANGED"
        var isOverlayVisible = false
        var isCurrentlyRinging = false
        var isDeskConnected = false
    }

    override fun onCreate() {
        super.onCreate()
        deskPrefs = DeskPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initTtsEngine()
        createHighPriorityChannels()
        acquireServiceWakeLock()

        startForeground(NOTIFICATION_ID, buildPermanentNotification("Initializing Desk Guard..."))
        connectDirectlyToFirebase()
        startUnifiedWatchdog()
        startVolumeLockLoop()
    }

    private fun initTtsEngine() {
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(attrs)
            isTtsReady = true
        }
    }

    private fun speakUrgentAlert(text: String) {
        if (!isTtsReady || !deskPrefs.isGuardArmed() || deskPrefs.isRestTimeActive()) return

        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (_: Exception) {}

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "TTS_ALERT_${System.currentTimeMillis()}")
    }

    private fun createHighPriorityChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val serviceChan = NotificationChannel(
                CHANNEL_ID,
                "Desk Monitor Permanent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(serviceChan)

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
            .setContentTitle("Desk Companion 2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildPermanentNotification(text))
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

                if (!deskPrefs.isGuardArmed() || deskPrefs.isRestTimeActive()) {
                    if (isCurrentlyRinging || isOverlayVisible) dismissAlarmAndOverlay()
                    return
                }

                if (isAlarmActiveOnDesk) {
                    if (!isCurrentlyRinging && !isSnoozed) {
                        triggerAlarm("⚠️ STUDY BREACH! Student Left Desk.")
                    }
                } else {
                    if (isCurrentlyRinging && !isCriticalDisconnectAlarmFired) {
                        dismissAlarmAndOverlay()
                    }
                    isSnoozed = false
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        dbRef.addValueEventListener(valueListener!!)
    }

    private fun startUnifiedWatchdog() {
        handler.post(object : Runnable {
            override fun run() {
                evaluateSystemState()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun evaluateSystemState() {
        // State 1: Guard is Disarmed via Master PIN
        if (!deskPrefs.isGuardArmed()) {
            updateServiceNotification("🛡️ Desk Guard: PAUSED (Disabled by Master PIN)")
            isDeskConnected = false
            if (isCurrentlyRinging || isOverlayVisible) dismissAlarmAndOverlay()
            cancelDisconnectNotification()
            disconnectStartTime = 0L
            hasSpokenDisconnect = false
            return
        }

        // State 2: Active Rest Schedule
        if (deskPrefs.isRestTimeActive()) {
            updateServiceNotification("🌙 Desk Guard: REST SCHEDULE ACTIVE (Silent)")
            isDeskConnected = true
            if (isCurrentlyRinging || isOverlayVisible) dismissAlarmAndOverlay()
            cancelDisconnectNotification()
            disconnectStartTime = 0L
            hasSpokenDisconnect = false
            return
        }

        // State 3: Guard is Armed & Actively Monitoring
        updateServiceNotification("🛡️ Desk Guard: ARMED & ACTIVE (Monitoring)")

        val now = System.currentTimeMillis()
        val hasInternet = isCompanionOnline()
        val heartbeatAgeSec = if (lastHeartbeatTimestamp > 0L) (now - lastHeartbeatTimestamp) / 1000 else 999L
        val isDisconnected = !hasInternet || (lastHeartbeatTimestamp > 0L && heartbeatAgeSec > 15L)

        if (isDisconnected) {
            isDeskConnected = false

            if (disconnectStartTime == 0L) {
                disconnectStartTime = now
                hasSpokenDisconnect = false
                isCriticalDisconnectAlarmFired = false
            } else {
                val elapsedSec = (now - disconnectStartTime) / 1000

                // Phase 1: 15 seconds buffer -> Trigger Voice Alert and Notification
                if (elapsedSec >= 15 && !hasSpokenDisconnect) {
                    hasSpokenDisconnect = true
                    speakUrgentAlert("Warning! Internet lost, please turn on hotspot.")
                    postDisconnectNotification("⚠️ INTERNET LOST! Turn ON Hotspot Now")
                }

                // Phase 2: 2 minutes (120 seconds) passed -> Full Siren & Red Screen Overlay
                if (elapsedSec >= 120 && !isCriticalDisconnectAlarmFired) {
                    isCriticalDisconnectAlarmFired = true
                    if (!isCurrentlyRinging && !isSnoozed) {
                        triggerAlarm("🚨 CRITICAL: Desk disconnected >2 mins! Turn on hotspot or check camera.")
                    }
                }
            }
        } else {
            // Desk is fully reconnected
            isDeskConnected = true

            if (disconnectStartTime != 0L) {
                if (hasSpokenDisconnect) {
                    speakUrgentAlert("Connection restored, desk back online.")
                }
                disconnectStartTime = 0L
                hasSpokenDisconnect = false
                cancelDisconnectNotification()

                if (isCriticalDisconnectAlarmFired) {
                    isCriticalDisconnectAlarmFired = false
                    if (!isAlarmActiveOnDesk) {
                        dismissAlarmAndOverlay()
                    }
                }
            }
        }
    }

    private fun postDisconnectNotification(msg: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notif = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setContentTitle("⚠️ DESK CONNECTION LOST")
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
        playLoudAlarmSound()
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

        val snoozeMins = deskPrefs.getSnoozeMinutes()
        val alarmNotif = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle("🚨 EMERGENCY DESK ALERT!")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "SNOOZE (${snoozeMins}M)", snoozePendingIntent)
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
                if (isCurrentlyRinging && deskPrefs.isGuardArmed() && !deskPrefs.isRestTimeActive()) {
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

        val snoozeDelayMs = deskPrefs.getSnoozeMinutes() * 60 * 1000L

        handler.postDelayed({
            isSnoozed = false
            if (!deskPrefs.isGuardArmed() || deskPrefs.isRestTimeActive()) return@postDelayed

            dbRef.get().addOnSuccessListener { snapshot ->
                val deskAlarm = snapshot.child("alarm_active").getValue(Boolean::class.java) ?: false
                val now = System.currentTimeMillis()
                val lastHb = snapshot.child("last_heartbeat").getValue(Long::class.java) ?: 0L
                val hbAgeSec = (now - lastHb) / 1000

                if (deskAlarm || isCriticalDisconnectAlarmFired || hbAgeSec > 30) {
                    triggerAlarm("⚠️ SNOOZE EXPIRED: Student still absent or camera disconnected!")
                }
            }
        }, snoozeDelayMs)
    }

    private fun muteAlarmSound() {
        isCurrentlyRinging = false
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun dismissAlarmAndOverlay() {
        muteAlarmSound()
        isOverlayVisible = false
        isCriticalDisconnectAlarmFired = false

        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(ALARM_NOTIFICATION_ID)

        sendBroadcast(Intent("com.desk.companion2.CLOSE_OVERLAY"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> snoozeAlarm()
            ACTION_STATE_CHANGED -> evaluateSystemState()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        valueListener?.let { dbRef.removeEventListener(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        val restartIntent = Intent(applicationContext, DeskMonitorService::class.java)
        startService(restartIntent)
    }
}
