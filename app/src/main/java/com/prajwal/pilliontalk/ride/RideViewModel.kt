package com.prajwal.pilliontalk.ride

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.prajwal.pilliontalk.PillionTalkApplication
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.Role
import kotlinx.coroutines.flow.StateFlow

/**
 * The screen's window onto the ride. The ride itself lives in [RideController], which outlives
 * the screen; this ViewModel just passes the UI's taps through and exposes the state to draw.
 */
class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = (app as PillionTalkApplication).rideController

    val state: StateFlow<RideState> = controller.state
    val voice: StateFlow<VoiceStats> = controller.voice

    fun start(role: Role) {
        // Start the foreground service first: it's what lets voice keep going in the background.
        RideService.start(getApplication())
        controller.start(role)
    }

    fun end() = controller.end()
    fun retry() = controller.retry()
    fun toggleMute() = controller.toggleMute()
    fun selectOutput(id: Int) = controller.selectOutput(id)
    fun setVolume(fraction: Float) = controller.setVolume(fraction)
    fun setBoost(on: Boolean) = controller.setBoost(on)
}
