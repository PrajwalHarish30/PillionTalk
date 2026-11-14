package com.prajwal.pilliontalk.host

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prajwal.pilliontalk.wifidirect.HostedGroup
import com.prajwal.pilliontalk.wifidirect.WifiDirectHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every state the host screen can be in. A "sealed interface" means this list is complete:
 * the compiler knows there are exactly these four, so a `when` over them needs no `else`.
 * (Dart 3's sealed classes work the same way.)
 */
sealed interface HostState {
    data object Idle : HostState
    data object Starting : HostState
    data class Hosting(val group: HostedGroup) : HostState
    data class Failed(val message: String) : HostState
}

/**
 * Holds the screen's state and survives things like screen rotation.
 * Flutter equivalent: a ChangeNotifier provided above the widget, but Android keeps it alive for you.
 *
 * StateFlow is a value that can be observed, like ValueNotifier. The UI reads `state`;
 * only this class can write to `_state`.
 */
class HostViewModel(app: Application) : AndroidViewModel(app) {

    private val host = WifiDirectHost(app)

    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    fun start() {
        // viewModelScope: coroutines launched here are cancelled automatically when the ViewModel dies.
        viewModelScope.launch {
            _state.value = HostState.Starting
            _state.value = try {
                HostState.Hosting(host.start())
            } catch (e: Exception) {
                HostState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            try {
                host.stop()
            } finally {
                _state.value = HostState.Idle
            }
        }
    }

    // Called when the user leaves the screen for good. Don't leave a Wi-Fi Direct group running behind us.
    override fun onCleared() {
        host.release()
    }
}
