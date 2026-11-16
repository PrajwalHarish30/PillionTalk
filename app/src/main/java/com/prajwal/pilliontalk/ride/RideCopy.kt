package com.prajwal.pilliontalk.ride

import android.os.SystemClock
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.Role

/**
 * The fun status lines, shared by the ride screen and the notification so they always agree.
 *
 * Priority matters: the first rule that matches wins. Problems (weak signal) come before fun,
 * so a cute line never hides something you need to know.
 */
object RideCopy {

    /** The headline for the current moment, e.g. "Yapping at full throttle". */
    fun title(state: RideState, voice: VoiceStats): String = when (state) {
        RideState.Idle -> "Ride's over. Kickstand down."

        is RideState.Connecting -> when (state.role) {
            Role.RIDER -> "Warming up the airwaves…"
            Role.PILLION -> "Hunting for your captain…"
        }

        is RideState.Failed -> state.message.substringBefore(".").let { "$it." }

        is RideState.Connected -> {
            val name = voice.peerName ?: otherSeat(state.link.role)
            val justConnected = SystemClock.elapsedRealtime() - state.since < 4_000
            when {
                !state.paired && state.link.role == Role.RIDER -> "Saving a seat for you…"
                voice.weakSignal -> "Stay close, $name's fading…"
                voice.muted -> "Mic's parked"
                voice.peerSilenced -> "Earplugs in"
                justConnected -> "Helmets synced. Go!"
                voice.quietForMs > 30_000 -> "Comfortable silence"
                voice.boosted -> "Turned up to eleven"
                else -> "Yapping at full throttle"
            }
        }
    }

    /** The facts line under the title, e.g. "Prajwal · Helmet BT". */
    fun subtitle(state: RideState, voice: VoiceStats): String = when (state) {
        is RideState.Connected -> listOfNotNull(
            voice.peerName ?: if (state.paired) otherSeat(state.link.role) else null,
            voice.output?.label,
            "weak signal".takeIf { voice.weakSignal },
        ).joinToString(" · ")
        is RideState.Connecting -> if (state.role == Role.RIDER) "Creating a private network" else "Make sure your rider has started a ride"
        is RideState.Failed -> state.message.substringAfter(". ", "")
        RideState.Idle -> ""
    }

    private fun otherSeat(myRole: Role) = if (myRole == Role.RIDER) "your pillion" else "your rider"
}

/** 2530 seconds → "42:10", 3725 → "1:02:05". */
fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
