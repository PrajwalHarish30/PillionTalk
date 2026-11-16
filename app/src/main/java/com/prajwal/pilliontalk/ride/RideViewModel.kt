package com.prajwal.pilliontalk.ride

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prajwal.pilliontalk.voice.VoiceEngine
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.LinkInfo
import com.prajwal.pilliontalk.wifidirect.Role
import com.prajwal.pilliontalk.wifidirect.WifiDirectLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Owns the ride: starts it, watches it, ends it. The UI only reads [state] / [voice] and calls functions.
 * Flutter equivalent: a ChangeNotifier provided above the app, which Android keeps alive
 * across screen rotations for you.
 */
class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val link = WifiDirectLink(app)
    private val voiceEngine = VoiceEngine(app)

    // StateFlow ≈ ValueNotifier. Only this class can write; the UI gets the read-only version.
    private val _state = MutableStateFlow<RideState>(RideState.Idle)
    val state: StateFlow<RideState> = _state.asStateFlow()

    /** Live voice numbers (ping, loss, buffer…) for the ride screen. */
    val voice: StateFlow<VoiceStats> = voiceEngine.stats

    // The coroutine running the current ride, so we can cancel it when the ride ends.
    private var rideJob: Job? = null

    fun start(role: Role) {
        rideJob?.cancel()
        // viewModelScope: coroutines here are cancelled automatically when the ViewModel dies.
        rideJob = viewModelScope.launch {
            _state.value = RideState.Connecting(role)

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
        while (viewModelScope.isActive) {
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

    fun toggleMute() {
        voiceEngine.setMuted(!voice.value.muted)
    }

    fun end() {
        rideJob?.cancel()
        viewModelScope.launch {
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

    // Called when the app is closed for good. Don't leave voice or Wi-Fi Direct running behind us.
    override fun onCleared() {
        voiceEngine.stop()
        link.release()
    }
}
