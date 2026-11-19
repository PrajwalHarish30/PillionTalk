package com.prajwal.pilliontalk.ride

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.prajwal.pilliontalk.PillionTalkApplication
import com.prajwal.pilliontalk.update.UpdateState
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.Role
import kotlinx.coroutines.flow.StateFlow

/**
 * The screen's window onto the ride. The ride itself lives in [RideController], which outlives
 * the screen; this ViewModel just passes the UI's taps through and exposes the state to draw.
 */
class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val pillion = app as PillionTalkApplication
    private val controller = pillion.rideController

    val state: StateFlow<RideState> = controller.state
    val voice: StateFlow<VoiceStats> = controller.voice
    val myName: StateFlow<String> = pillion.profile.name
    val update: StateFlow<UpdateState> = pillion.updater.state
    val versionName: String = pillion.updater.installedVersionName

    fun setMyName(name: String) = pillion.profile.setName(name)
    fun onAppResumed() = pillion.updater.onResume()
    fun installUpdate() = pillion.updater.install()

    fun start(role: Role) {
        // Start the foreground service first: it's what lets voice keep going in the background.
        RideService.start(getApplication())
        controller.start(role)
    }

    fun end() = controller.end()
    fun retry() = controller.retry()
    fun toggleMute() = controller.toggleMute()
    fun togglePeerSilenced() = controller.togglePeerSilenced()
    fun selectOutput(id: Int) = controller.selectOutput(id)
    fun setVolume(level: Float) = controller.setVolume(level)
}
