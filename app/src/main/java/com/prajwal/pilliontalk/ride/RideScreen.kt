package com.prajwal.pilliontalk.ride

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prajwal.pilliontalk.profile.initialsOf
import com.prajwal.pilliontalk.ui.components.Avatar
import com.prajwal.pilliontalk.ui.components.SectionLabel
import com.prajwal.pilliontalk.ui.components.VolumeSlider
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme
import com.prajwal.pilliontalk.voice.AudioOutput
import com.prajwal.pilliontalk.voice.OutputKind
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.Band
import com.prajwal.pilliontalk.wifidirect.LinkInfo
import com.prajwal.pilliontalk.wifidirect.Role
import kotlinx.coroutines.delay

/** Everything the ride screen can ask for. Grouped so the screen's signature stays readable. */
class RideActions(
    val onToggleMute: () -> Unit = {},
    val onTogglePeerSilenced: () -> Unit = {},
    val onSelectOutput: (Int) -> Unit = {},
    val onVolumeChange: (Float) -> Unit = {},
    val onEnd: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The ride screen. Layout borrowed from voice-call apps:
 *   header      "Ride · 42:10" and the band
 *   status      the fun line ("Yapping at full throttle") and the facts under it
 *   tiles       you and the other person; a ring lights up around whoever is talking
 *   control bar mic · output (dropdown, with volume) · details · end
 *
 * It's "stateless": give it state and callbacks and it draws. The sheets' open/closed flags are
 * the only state it keeps itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideScreen(
    state: RideState,
    voice: VoiceStats,
    myName: String,
    actions: RideActions,
    modifier: Modifier = Modifier,
) {
    var showPeerSheet by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Header(state)

        Spacer(Modifier.height(20.dp))
        Text(RideCopy.title(state, voice), style = MaterialTheme.typography.headlineMedium)
        val subtitle = RideCopy.subtitle(state, voice)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(24.dp))
        Tiles(state, voice, myName, onPeerClick = { showPeerSheet = true })

        if (state is RideState.Failed) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = actions.onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
        }

        Spacer(Modifier.weight(1f))
        ControlBar(state, voice, actions, onDetails = { showDetails = true })
    }

    val connected = state as? RideState.Connected
    if (showPeerSheet && connected != null && connected.paired) {
        ModalBottomSheet(onDismissRequest = { showPeerSheet = false }) {
            PeerSheet(connected, voice, actions)
        }
    }
    if (showDetails && connected != null) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            DetailsSheet(connected.link, voice)
        }
    }
}

/** "Ride · 42:10" on the left, the band on the right. The timer ticks once a second. */
@Composable
private fun Header(state: RideState) {
    // produceState: a value that a coroutine keeps updating (Flutter: a StreamBuilder over a timer).
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val connected = state as? RideState.Connected
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (connected != null) "Ride · ${formatDuration((now - connected.since) / 1000)}" else "Ride",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (connected != null) {
            Text(
                connected.link.band.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Two tiles, you and them. */
@Composable
private fun Tiles(state: RideState, voice: VoiceStats, myName: String, onPeerClick: () -> Unit) {
    val connected = state as? RideState.Connected
    val paired = connected?.paired == true
    val peerName = voice.peerName

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            label = "You",
            avatar = {
                Avatar(
                    initials = initialsOf(myName.ifBlank { "You" }),
                    size = 64.dp,
                    speaking = voice.meSpeaking,
                    badge = if (voice.muted) Icons.Rounded.MicOff else null,
                )
            },
            modifier = Modifier.weight(1f),
        )
        Tile(
            label = when {
                paired -> peerName ?: "Connecting…"
                state is RideState.Failed -> "Lost"
                else -> "Waiting…"
            },
            avatar = {
                Avatar(
                    initials = if (paired && peerName != null) initialsOf(peerName) else "?",
                    size = 64.dp,
                    speaking = paired && voice.peerSpeaking && !voice.peerSilenced,
                    waiting = !paired,
                    badge = if (voice.peerSilenced) Icons.AutoMirrored.Rounded.VolumeOff else null,
                )
            },
            onClick = if (paired) onPeerClick else null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Tile(label: String, avatar: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            avatar()
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surface, modifier = modifier) { content() }
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surface, modifier = modifier) { content() }
    }
}

/** Mic · output · details · end. Big round targets for gloves. */
@Composable
private fun ControlBar(state: RideState, voice: VoiceStats, actions: RideActions, onDetails: () -> Unit) {
    var outputMenuOpen by remember { mutableStateOf(false) }
    val live = voice.running

    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundButton(
                icon = if (voice.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                description = if (voice.muted) "Unmute" else "Mute",
                onClick = actions.onToggleMute,
                enabled = live,
                // Muted is the one state worth flagging loudly: invert the button.
                inverted = voice.muted,
            )

            // The output pill opens a dropdown anchored to it: outputs, then the volume slider.
            Box {
                Surface(
                    onClick = { outputMenuOpen = true },
                    enabled = live,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.height(56.dp).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(iconFor(voice.output?.kind), contentDescription = "Audio output")
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(
                    expanded = outputMenuOpen,
                    onDismissRequest = { outputMenuOpen = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    SectionLabel("Output", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    voice.outputs.forEach { output ->
                        DropdownMenuItem(
                            text = { Text(labelFor(output)) },
                            leadingIcon = { Icon(iconFor(output.kind), contentDescription = null) },
                            trailingIcon = {
                                if (output.id == voice.output?.id) Icon(Icons.Rounded.Check, contentDescription = "Selected")
                            },
                            onClick = {
                                actions.onSelectOutput(output.id)
                                outputMenuOpen = false
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    VolumeSlider(
                        value = voice.volume,
                        onValueChange = actions.onVolumeChange,
                        modifier = Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            RoundButton(icon = Icons.Outlined.Info, description = "Connection details", onClick = onDetails, enabled = state is RideState.Connected)

            RoundButton(
                icon = Icons.Rounded.CallEnd,
                description = "End ride",
                onClick = actions.onEnd,
                container = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    inverted: Boolean = false,
    container: Color = if (inverted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = if (inverted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        modifier = Modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description) }
    }
}

/** Tapping the other person's tile: their volume, and "silence them". */
@Composable
private fun PeerSheet(state: RideState.Connected, voice: VoiceStats, actions: RideActions) {
    val name = voice.peerName ?: if (state.link.role == Role.RIDER) "Your pillion" else "Your rider"
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(initials = initialsOf(name), size = 52.dp, speaking = voice.peerSpeaking && !voice.peerSilenced)
            Column {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        voice.peerSilenced -> "Silenced"
                        voice.weakSignal -> "Signal's thinning out"
                        voice.peerSpeaking -> "Talking"
                        else -> "Quiet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        VolumeSlider(value = voice.volume, onValueChange = actions.onVolumeChange)
        OutlinedButton(onClick = actions.onTogglePeerSilenced, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(if (voice.peerSilenced) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (voice.peerSilenced) "Hear them again" else "Silence them")
        }
    }
}

/** The numbers, for the curious (and for debugging voice quality). */
@Composable
private fun DetailsSheet(link: LinkInfo, voice: VoiceStats) {
    val quality = when {
        voice.weakSignal -> "Weak"
        voice.lossPercent < 1 && (voice.pingMs ?: 0) < 60 -> "Excellent"
        else -> "Good"
    }
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connection details", style = MaterialTheme.typography.titleLarge)
        DetailRow("Signal", quality)
        DetailRow("Band", "${link.band.label} · ${link.frequencyMhz} MHz")
        if (link.band != Band.GHZ_5 && link.band != Band.GHZ_6) {
            Text(
                "2.4 GHz can interfere with Bluetooth headsets. ${link.fallbackReason ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DetailRow("Ping", voice.pingMs?.let { "$it ms" } ?: "–")
        DetailRow("Packet loss", "%.1f%%".format(voice.lossPercent))
        DetailRow("Buffer · gaps", "${voice.bufferedMs} ms · ${voice.underruns}")
        DetailRow("Output", voice.output?.let(::labelFor) ?: "–")
        DetailRow("This phone", link.myIp ?: "?")
        DetailRow("Rider's phone", link.hostIp ?: "?")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

private fun iconFor(kind: OutputKind?): ImageVector = when (kind) {
    OutputKind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    OutputKind.WIRED -> Icons.Rounded.Usb
    OutputKind.EARPIECE -> Icons.Rounded.PhoneAndroid
    OutputKind.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
    null -> Icons.Rounded.Headphones
}

private fun labelFor(output: AudioOutput): String = when (output.kind) {
    OutputKind.EARPIECE -> "Phone earpiece"
    else -> output.label
}

// --- Previews: see states without a phone -----------------------------------------------------

private val previewLink = LinkInfo(Role.RIDER, "DIRECT-PT-PillionTalk", 5745, "192.168.49.1", "192.168.49.1", "p2p-wlan0-0", null)
private val previewVoice = VoiceStats(
    running = true,
    peerName = "Prajwal",
    meSpeaking = true,
    outputs = listOf(AudioOutput(1, OutputKind.BLUETOOTH, "Helmet BT"), AudioOutput(2, OutputKind.EARPIECE, "Phone")),
    output = AudioOutput(1, OutputKind.BLUETOOTH, "Helmet BT"),
    volume = 1.4f,
    pingMs = 6,
)

@Preview(name = "Talking · dark", showBackground = true, heightDp = 780)
@Composable
private fun TalkingDarkPreview() {
    PillionTalkTheme(dark = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RideScreen(RideState.Connected(previewLink, listOf("Redmi"), SystemClock.elapsedRealtime() - 60_000), previewVoice, "Harish", RideActions())
        }
    }
}

@Preview(name = "Muted · light", showBackground = true, heightDp = 780)
@Composable
private fun MutedLightPreview() {
    PillionTalkTheme(dark = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RideScreen(RideState.Connected(previewLink, listOf("Redmi"), SystemClock.elapsedRealtime() - 60_000), previewVoice.copy(muted = true, meSpeaking = false), "Harish", RideActions())
        }
    }
}

@Preview(name = "Waiting · dark", showBackground = true, heightDp = 780)
@Composable
private fun WaitingPreview() {
    PillionTalkTheme(dark = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RideScreen(RideState.Connected(previewLink, emptyList(), SystemClock.elapsedRealtime()), VoiceStats(running = true), "Harish", RideActions())
        }
    }
}
