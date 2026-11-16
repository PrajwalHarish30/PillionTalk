package com.prajwal.pilliontalk.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.prajwal.pilliontalk.MainActivity
import com.prajwal.pilliontalk.PillionTalkApplication
import com.prajwal.pilliontalk.R
import com.prajwal.pilliontalk.voice.VoiceStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the ride alive when the app isn't on screen.
 *
 * Android freely pauses or kills apps in the background, and since Android 11 it won't let a
 * background app use the microphone at all. A *foreground service* is the exception: the app
 * promises the user it's doing something they want right now, and shows a notification that
 * can't be swiped away. In return Android keeps it running, mic included.
 *
 * The service doesn't do the ride itself; RideController does. This just holds the app
 * "in the foreground" from Android's point of view, shows the notification, and stops itself
 * when the ride ends.
 */
class RideService : Service() {

    private val controller by lazy { (application as PillionTalkApplication).rideController }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Keeps the CPU awake with the screen off, so audio and network threads aren't put to sleep.
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PillionTalk:ride")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null // nobody binds; we're started and stopped

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_END -> controller.end()
            ACTION_MUTE -> controller.toggleMute()
            else -> goForeground()
        }
        return START_NOT_STICKY // if Android kills us anyway, don't restart a half-dead ride
    }

    private fun goForeground() {
        // Must be called within a few seconds of starting, or Android crashes the app.
        // The "microphone" type is what allows mic use while in the background.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(controller.state.value, controller.voice.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wakeLock.acquire(12 * 60 * 60 * 1000L) // a safety cap: 12 hours

        // Keep the notification up to date, and stop when the ride is over.
        scope.launch {
            combine(controller.state, controller.voice) { state, voice -> state to voice }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second.muted == b.second.muted && a.second.hearingPeer == b.second.hearingPeer }
                .collect { (state, voice) ->
                    if (state == RideState.Idle) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(state, voice))
                    }
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    // The user swiped the app away from recents: end the ride cleanly.
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.end()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(state: RideState, voice: VoiceStats): Notification {
        val title = when (state) {
            RideState.Idle -> "Ride ended"
            is RideState.Connecting -> "Connecting…"
            is RideState.Connected -> when {
                voice.hearingPeer -> if (voice.muted) "On a ride · muted" else "On a ride · talking"
                state.paired -> "Connected"
                else -> "Waiting for your pillion"
            }
            is RideState.Failed -> "Connection lost"
        }

        // Tapping the notification opens the app.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Pillion Talk keeps voice running while you ride.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (voice.muted) "Unmute" else "Mute", actionIntent(ACTION_MUTE, 1))
            .addAction(0, "End ride", actionIntent(ACTION_END, 2))
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, RideService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Ride", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a ride is active"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ride"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_END = "com.prajwal.pilliontalk.END"
        private const val ACTION_MUTE = "com.prajwal.pilliontalk.MUTE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }
    }
}
