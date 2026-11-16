package com.prajwal.pilliontalk.ride

import android.content.Context
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
     * The link is up. [peers] is who's on the other end: for the rider, empty until the
     * pillion joins.
     */
    data class Connected(val link: LinkInfo, val peers: List<String>) : RideState {
        val paired: Boolean get() = peers.isNotEmpty()
    }

    data class Failed(val role: Role, val message: String) : RideState
}

/**
 * Owns the ride: starts it, watches it, ends it.
 *
 * Why not keep this in the ViewModel any more? A ViewModel belongs to a screen. When the app
 * goes to the background, Android may destroy the screen, and the ride would die with it.
 * This object belongs to the whole app instead (one instance, created by PillionTalkApplication),
 * and RideService keeps the app process alive while a ride is running. The screen just watches.
 *
 * Flutter equivalent: a top-level service object (like a Riverpod provider outside the widget tree).
 */
class RideController(context: Context) {

    private val link = WifiDirectLink(context)
    private val voiceEngine = VoiceEngine(context)

    // Lives as long as the app process. SupervisorJob: one failed coroutine doesn't cancel the others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<RideState>(RideState.Idle)
    val state: StateFlow<RideState> = _state.asStateFlow()

    /** Live voice numbers (ping, loss, buffer, volume…). */
    val voice: StateFlow<VoiceStats> = voiceEngine.stats

    private var rideJob: Job? = null

    fun start(role: Role) {
        rideJob?.cancel()
        // Set this right away (not inside the coroutine), so RideService never sees a stale "Idle"
        // when it starts up and stops itself by mistake.
        _state.value = RideState.Connecting(role)
        rideJob = scope.launch {

            val info = try {
                if (role == Role.RIDER) link.host() else link.join()
            } catch (e: CancellationException) {
                throw e // the ride was cancelled on purpose: let it stop quietly
            } catch (e: Exception) {
                _state.value = RideState.Failed(role, e.message ?: "Something went wrong")
                return@launch
            }

            _state.value = RideState.Connected(info, peers = link.peers(role).orEmpty())

            // Start voice straight away. The pillion knows the rider's address; the rider
            // waits for the pillion's first packet to learn theirs.
            withContext(Dispatchers.IO) {
                voiceEngine.start(peerIp = if (role == Role.PILLION) info.hostIp else null)
            }
            watch(info)
        }
    }

    /** Every 2 seconds, check who's connected, and notice if the link drops. */
    private suspend fun watch(info: LinkInfo) {
        while (scope.isActive) {
            delay(2_000)
            val peers = link.peers(info.role)
            if (peers == null) {
                stopVoice()
                val who = if (info.role == Role.RIDER) "The ride network" else "The connection to your rider"
                _state.value = RideState.Failed(info.role, "$who was lost.")
                return
            }
            _state.value = RideState.Connected(info, peers)
        }
    }

    fun toggleMute() = voiceEngine.setMuted(!voice.value.muted)

    fun selectOutput(id: Int) = voiceEngine.selectOutput(id)

    fun setVolume(fraction: Float) = voiceEngine.setVolume(fraction)

    fun setBoost(on: Boolean) = voiceEngine.setBoost(on)

    fun end() {
        rideJob?.cancel()
        scope.launch {
            stopVoice()
            runCatching { link.leave() }
            _state.value = RideState.Idle
        }
    }

    fun retry() {
        val current = _state.value
        if (current is RideState.Failed) start(current.role)
    }

    // stop() waits for the audio threads to finish, so keep it off the main thread.
    private suspend fun stopVoice() = withContext(Dispatchers.IO) { voiceEngine.stop() }
}
