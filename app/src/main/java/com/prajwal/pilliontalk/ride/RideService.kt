package com.prajwal.pilliontalk.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.prajwal.pilliontalk.profile.initialsOf
import com.prajwal.pilliontalk.MainActivity
import com.prajwal.pilliontalk.PillionTalkApplication
import com.prajwal.pilliontalk.R
import com.prajwal.pilliontalk.voice.OutputKind
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
            ACTION_SPEAKER -> controller.toggleSpeaker()
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
        // Only re-post when something you'd see actually changes (the voice stats tick twice a
        // second; re-posting that often would be wasteful and can make the shade flicker).
        scope.launch {
            combine(controller.state, controller.voice) { state, voice -> NotificationContent.of(state, voice) }
                .distinctUntilChanged()
                .collect { content ->
                    if (content.ended) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(controller.state.value, controller.voice.value))
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

    /**
     * A "call style" notification: the same kind Android uses for phone and WhatsApp calls.
     * It gets a ride timer chip in the status bar, sits at the top of the shade, and shows its
     * buttons on the lock screen. The big title is the "person" of the call, so we put the fun
     * status line there; the facts go underneath.
     */
    private fun buildNotification(state: RideState, voice: VoiceStats): Notification {
        val content = NotificationContent.of(state, voice)

        // Tapping the notification opens the app.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val person = Person.Builder()
            .setName(content.title)
            .setIcon(IconCompat.createWithBitmap(avatarBitmap(content.initials)))
            .setImportant(true)
            .build()
        val style = NotificationCompat.CallStyle.forOngoingCall(person, actionIntent(ACTION_END, 2))

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentText(content.subtitle)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // show the controls on the lock screen
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (voice.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                if (voice.muted) "Unmute" else "Mute",
                actionIntent(ACTION_MUTE, 1),
            )

        if (state is RideState.Connected) {
            // The ride timer: "when" is the moment the ride started, and the chronometer counts up.
            builder.setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - state.since))
                .addAction(
                    R.drawable.ic_speaker,
                    if (content.onSpeaker) "Headset" else "Speaker",
                    actionIntent(ACTION_SPEAKER, 3),
                )
        }
        return builder.build()
    }

    /**
     * Draws the same kind of avatar the app uses (initials on a neutral circle) into a bitmap,
     * because notifications can't show Compose UI. Canvas here is Android's classic 2D drawing
     * API: the same idea as Compose's Canvas or Flutter's CustomPainter.
     */
    private fun avatarBitmap(initials: String): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2B2B.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F2F2.toInt()
            textSize = size * 0.36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Centre the text vertically: the baseline sits below the middle by half the text height.
        val y = size / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(initials, size / 2f, y, text)
        return bitmap
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
        private const val ACTION_SPEAKER = "com.prajwal.pilliontalk.SPEAKER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }
    }
}

/** Just the parts of the ride the notification shows, so we can tell when it needs re-posting. */
private data class NotificationContent(
    val title: String,
    val subtitle: String,
    val initials: String,
    val muted: Boolean,
    val onSpeaker: Boolean,
    val ended: Boolean,
) {
    companion object {
        fun of(state: RideState, voice: VoiceStats) = NotificationContent(
            title = RideCopy.title(state, voice),
            subtitle = RideCopy.subtitle(state, voice),
            initials = voice.peerName?.let(::initialsOf) ?: "?",
            muted = voice.muted,
            onSpeaker = voice.output?.kind == OutputKind.SPEAKER,
            ended = state == RideState.Idle,
        )
    }
}
