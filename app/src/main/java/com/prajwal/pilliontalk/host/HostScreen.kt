package com.prajwal.pilliontalk.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prajwal.pilliontalk.wifidirect.Band
import com.prajwal.pilliontalk.wifidirect.HostedGroup

/** The runtime permission Wi-Fi Direct needs depends on the Android version (see AndroidManifest.xml). */
private val wifiDirectPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

/**
 * The "stateful" half: it gets the ViewModel, handles permissions, and passes plain values
 * down to [HostContent]. Keeping the two apart lets HostContent be previewed and reused easily.
 */
@Composable
fun HostScreen(modifier: Modifier = Modifier, viewModel: HostViewModel = viewModel()) {
    // Like StreamBuilder / ValueListenableBuilder: recomposes whenever the state changes.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Shows Android's permission dialog and calls us back with the user's answer.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) viewModel.start()
    }

    HostContent(
        state = state,
        onStart = {
            val granted = wifiDirectPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) viewModel.start() else permissionLauncher.launch(wifiDirectPermissions)
        },
        onStop = viewModel::stop,
        modifier = modifier,
    )
}

/** The "stateless" half: given a state, draw it. No Android services, no side effects. */
@Composable
fun HostContent(
    state: HostState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pillion Talk", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Host a Wi-Fi Direct group for your pillion to join",
            style = MaterialTheme.typography.bodyMedium,
        )

        // `when` over a sealed interface: the compiler checks that every state is handled.
        when (state) {
            HostState.Idle -> Button(onClick = onStart) { Text("Host a ride") }

            HostState.Starting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Creating group…")
            }

            is HostState.Hosting -> {
                GroupDetails(state.group)
                OutlinedButton(onClick = onStop) { Text("Stop hosting") }
            }

            is HostState.Failed -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onStart) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun GroupDetails(group: HostedGroup) {
    // Green when we got 5 GHz (no fight with Bluetooth), amber otherwise.
    val bandColor = if (group.band == Band.GHZ_5) Color(0xFF2E7D32) else Color(0xFFEF6C00)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${group.band.label} · ${group.frequencyMhz} MHz",
                style = MaterialTheme.typography.headlineSmall,
                color = bandColor,
            )
            group.fallbackReason?.let {
                Text("Couldn't get 5 GHz: $it", style = MaterialTheme.typography.bodySmall)
            }
            DetailRow("Network", group.networkName)
            DetailRow("Password", group.passphrase)
            DetailRow("Host IP", group.ownerIp ?: "not yet assigned")
            DetailRow("Interface", group.interfaceName ?: "?")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// Previews: see each state in Android Studio's design pane without a phone.
@Preview(showBackground = true)
@Composable
private fun HostingPreview() {
    MaterialTheme {
        HostContent(
            state = HostState.Hosting(
                HostedGroup("DIRECT-PT-PillionTalk", "pillion-dev-123", 5745, "192.168.49.1", "p2p-wlan0-0", null),
            ),
            onStart = {},
            onStop = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FailedPreview() {
    MaterialTheme {
        HostContent(state = HostState.Failed("createGroup (5 GHz) failed: Wi-Fi Direct is busy"), onStart = {}, onStop = {})
    }
}
