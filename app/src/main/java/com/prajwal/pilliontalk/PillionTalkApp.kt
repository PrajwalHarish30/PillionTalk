package com.prajwal.pilliontalk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prajwal.pilliontalk.home.HomeScreen
import com.prajwal.pilliontalk.ride.RideScreen
import com.prajwal.pilliontalk.ride.RideState
import com.prajwal.pilliontalk.ride.RideViewModel
import com.prajwal.pilliontalk.wifidirect.Role
import kotlinx.coroutines.launch

/**
 * Runtime permissions a ride needs: the microphone, plus Wi-Fi Direct's permission,
 * which depends on the Android version (see AndroidManifest.xml).
 */
private val ridePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.RECORD_AUDIO)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
    }

/**
 * The root of the UI. Navigation here is just "which screen matches the ride state":
 * no ride → home, anything else → the ride screen. No navigation library needed.
 * (In Flutter terms: a top-level widget that switches its child based on app state.)
 */
@Composable
fun PillionTalkApp(viewModel: RideViewModel = viewModel()) {
    // Like StreamBuilder / ValueListenableBuilder: recomposes whenever the state changes.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Remember which seat was tapped while the permission dialog is open.
    var pendingRole by rememberSaveable { mutableStateOf<Role?>(null) }

    // Shows Android's permission dialog and calls us back with the answer.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val role = pendingRole
        pendingRole = null
        if (results.values.all { it } && role != null) {
            viewModel.start(role)
        } else {
            scope.launch { snackbar.showSnackbar("Pillion Talk needs Nearby devices (to connect) and Microphone (to talk).") }
        }
    }

    fun pickRole(role: Role) {
        val granted = ridePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            viewModel.start(role)
        } else {
            pendingRole = role
            permissionLauncher.launch(ridePermissions)
        }
    }

    val onRide = state != RideState.Idle

    // On the ride screen, the system back gesture ends the ride instead of closing the app.
    BackHandler(enabled = onRide) { viewModel.end() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        // Cross-fade between home and ride. AnimatedContent ≈ Flutter's AnimatedSwitcher.
        AnimatedContent(
            targetState = onRide,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { showRide ->
            if (showRide) {
                RideScreen(
                    state = state,
                    voice = voice,
                    onToggleMute = viewModel::toggleMute,
                    onEnd = viewModel::end,
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                HomeScreen(onPickRole = ::pickRole, modifier = Modifier.padding(innerPadding))
            }
        }
    }
}
