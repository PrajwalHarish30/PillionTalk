package com.prajwal.pilliontalk.ride

import android.content.Context
import android.os.SystemClock
import com.prajwal.pilliontalk.profile.ProfileStore
import com.prajwal.pilliontalk.voice.Cue
import com.prajwal.pilliontalk.voice.VoiceEngine
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.LinkInfo
import com.prajwal.pilliontalk.wifidirect.Role
import com.prajwal.pilliontalk.wifidirect.WifiDirectLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every state a ride can be in. "Sealed" means this list is complete, so a `when` over it
 * must handle every case (Dart 3's sealed classes work the same way).
 */
sealed interface RideState {
    /** No ride: show the home screen. */
    data object Idle : RideState

    /** Rider: creating the group. Pillion: looking for the rider's group. */
    data class Connecting(val role: Role) : RideState

    /**
     * The link is up. [peers] is who's on the other end (device names): for the rider, empty
     * until the pillion joins. [since] is when the link came up, for the ride timer.
     */
    data class Connected(val link: LinkInfo, val peers: List<String>, val since: Long) : RideState {
        val paired: Boolean get() = peers.isNotEmpty()
    }

    data class Failed(val role: Role, val message: String) : RideState
}

/**
 * Owns the ride: starts it, watches it, ends it.
 *
 * Why not keep this in the ViewModel? A ViewModel belongs to a screen. When the app goes to the
 * background, Android may destroy the screen, and the ride would die with it. This object
 * belongs to the whole app instead (one instance, created by PillionTalkApplication), and
 * RideService keeps the app process alive while a ride is running. The screen just watches.
 *
 * Flutter equivalent: a top-level service object (like a Riverpod provider outside the widget tree).
 */
class RideController(context: Context, private val profile: ProfileStore) {

    private val link = WifiDirectLink(context)
    private val voiceEngine = VoiceEngine(context)

    // Lives as long as the app process. SupervisorJob: one failed coroutine doesn't cancel the others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<RideState>(RideState.Idle)
    val state: StateFlow<RideState> = _state.asStateFlow()

    /** Live voice info (who's speaking, volume, output, stats…). */
    val voice: StateFlow<VoiceStats> = voiceEngine.stats

    private var rideJob: Job? = null

    /** Shutting the last ride down (goodbye sound, leaving the Wi-Fi group), after the screen has moved on. */
    private var teardownJob: Job? = null

    fun start(role: Role) {
        rideJob?.cancel()
        // Set this right away (not inside the coroutine), so RideService never sees a stale "Idle"
        // when it starts up and stops itself by mistake.
        _state.value = RideState.Connecting(role)
        val previousTeardown = teardownJob
        rideJob = scope.launch {
            previousTeardown?.join() // a new ride can't start until the old one has let go of Wi-Fi and audio
            val info = try {
                if (role == Role.RIDER) link.host() else link.join()
            } catch (e: CancellationException) {
                throw e // the ride was cancelled on purpose: let it stop quietly
            } catch (e: Exception) {
                _state.value = RideState.Failed(role, e.message ?: "Something went wrong")
                return@launch
            }

            val since = SystemClock.elapsedRealtime()
            _state.value = RideState.Connected(info, peers = link.peers(role).orEmpty(), since = since)

            // Start voice straight away. The pillion knows the rider's address; the rider
            // waits for the pillion's first packet to learn theirs.
            withContext(Dispatchers.IO) {
                voiceEngine.start(
                    peerIp = if (role == Role.PILLION) info.hostIp else null,
                    name = profile.name.value.ifBlank { android.os.Build.MODEL },
                )
            }
            watch(info, since)
        }
    }

    /** Every 2 seconds, check who's connected, and notice if the link drops. */
    private suspend fun watch(info: LinkInfo, since: Long) {
        while (scope.isActive) {
            delay(2_000)
            val peers = link.peers(info.role)
            if (peers == null) {
                val who = voice.value.peerName ?: if (info.role == Role.RIDER) "Your pillion" else "Your rider"
                _state.value = RideState.Failed(info.role, "$who took a detour. The connection was lost.")
                teardownJob = scope.launch { stopVoice(Cue.PEER_LEFT) }
                return
            }
            _state.value = RideState.Connected(info, peers, since)
        }
    }

    fun toggleMute() = voiceEngine.setMuted(!voice.value.muted)

    fun togglePeerSilenced() = voiceEngine.setPeerSilenced(!voice.value.peerSilenced)

    fun selectOutput(id: Int) = voiceEngine.selectOutput(id)

    /** Switch between the headset/earpiece and the loudspeaker (for the notification button). */
    fun toggleSpeaker() {
        val stats = voice.value
        val onSpeaker = stats.output?.kind == com.prajwal.pilliontalk.voice.OutputKind.SPEAKER
        val target = if (onSpeaker) {
            stats.outputs.firstOrNull { it.kind != com.prajwal.pilliontalk.voice.OutputKind.SPEAKER }
        } else {
            stats.outputs.firstOrNull { it.kind == com.prajwal.pilliontalk.voice.OutputKind.SPEAKER }
        }
        target?.let { voiceEngine.selectOutput(it.id) }
    }

    /** 0.0–2.0: up to 1.0 is the phone's volume, above that is boost. */
    fun setVolume(level: Float) = voiceEngine.setVolume(level)

    /**
     * End the ride. The screen goes back home straight away; the goodbye sound and leaving the
     * Wi-Fi group (which can take a second or two) carry on in the background.
     */
    fun end() {
        rideJob?.cancel()
        _state.value = RideState.Idle
        val previousTeardown = teardownJob
        teardownJob = scope.launch {
            previousTeardown?.join()
            stopVoice(Cue.YOU_LEFT)
            runCatching { link.leave() }
        }
    }

    fun retry() {
        val current = _state.value
        if (current is RideState.Failed) start(current.role)
    }

    // stop() waits for the audio threads to finish, so keep it off the main thread.
    private suspend fun stopVoice(goodbye: Cue? = null) = withContext(Dispatchers.IO) { voiceEngine.stop(goodbye) }
}
