package com.prajwal.pilliontalk.ride

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prajwal.pilliontalk.ui.theme.PillionColors
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme
import com.prajwal.pilliontalk.voice.AudioOutput
import com.prajwal.pilliontalk.voice.OutputKind
import com.prajwal.pilliontalk.voice.VoiceStats
import com.prajwal.pilliontalk.wifidirect.Band
import com.prajwal.pilliontalk.wifidirect.LinkInfo
import com.prajwal.pilliontalk.wifidirect.Role

/**
 * The screen you see during a ride, for both seats. It's "stateless": give it a state and
 * two callbacks, and it draws. That's what lets the previews at the bottom show every state.
 */
@Composable
fun RideScreen(
    state: RideState,
    voice: VoiceStats,
    onToggleMute: () -> Unit,
    onSelectOutput: (Int) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBoostChange: (Boolean) -> Unit,
    onEnd: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Turn the state into what to show. A `when` over a sealed type must cover every case.
    val role = when (state) {
        RideState.Idle -> Role.RIDER
        is RideState.Connecting -> state.role
        is RideState.Connected -> state.link.role
        is RideState.Failed -> state.role
    }
    val status = statusFor(state)

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RoleBadge(role)

        // The middle scrolls if a small screen can't fit the details card.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Spacer(Modifier.height(8.dp))
            StatusHalo(emoji = if (role == Role.RIDER) "🏍️" else "🎧", color = status.color, pulsing = status.pulsing)
            Text(status.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                status.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (state is RideState.Connected) {
                BandChip(state.link)
                VoicePanel(voice, onToggleMute, onSelectOutput, onVolumeChange, onBoostChange)
                ConnectionDetails(state.link)
            }
            if (state is RideState.Failed) {
                Button(onClick = onRetry) { Text("Try again") }
            }
        }

        // Big, always-visible way out. Easy to hit with gloves.
        OutlinedButton(
            onClick = onEnd,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, PillionColors.Bad.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PillionColors.Bad),
        ) {
            Text(if (state is RideState.Failed) "Back" else "End ride", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Title, subtitle and colour for each state, worded for each seat. */
private data class Status(val title: String, val subtitle: String, val color: Color, val pulsing: Boolean)

private fun statusFor(state: RideState): Status = when (state) {
    RideState.Idle -> Status("", "", PillionColors.TextSecondary, pulsing = false)

    is RideState.Connecting -> when (state.role) {
        Role.RIDER -> Status("Starting your ride…", "Creating a private Wi-Fi Direct network", PillionColors.Amber, pulsing = true)
        Role.PILLION -> Status("Looking for your rider…", "Make sure their phone has started a ride", PillionColors.Amber, pulsing = true)
    }

    is RideState.Connected -> when {
        state.link.role == Role.RIDER && !state.paired ->
            Status("Waiting for your pillion", "Ask them to open Pillion Talk and tap \"I'm the pillion\"", PillionColors.Amber, pulsing = true)
        state.link.role == Role.RIDER ->
            Status("Pillion connected", state.peers.joinToString(), PillionColors.Good, pulsing = false)
        else ->
            Status("Connected to your rider", state.peers.joinToString(), PillionColors.Good, pulsing = false)
    }

    is RideState.Failed -> Status("Couldn't connect", state.message, PillionColors.Bad, pulsing = false)
}

@Composable
private fun RoleBadge(role: Role) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            if (role == Role.RIDER) "RIDER" else "PILLION",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * A circle with the seat's emoji, plus expanding rings while we're waiting for something.
 * rememberInfiniteTransition ≈ an AnimationController with ..repeat() in Flutter.
 */
@Composable
private fun StatusHalo(emoji: String, color: Color, pulsing: Boolean) {
    // Smoothly fade between colours when the state changes (amber → green, etc.).
    val animatedColor by animateColorAsState(color, label = "haloColor")
    val transition = rememberInfiniteTransition(label = "halo")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "haloProgress",
    )

    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            // Canvas ≈ Flutter's CustomPaint. Two rings, half a cycle apart, growing and fading.
            Canvas(Modifier.fillMaxSize()) {
                listOf(progress, (progress + 0.5f) % 1f).forEach { p ->
                    drawCircle(
                        color = animatedColor.copy(alpha = (1f - p) * 0.5f),
                        radius = size.minDimension / 2 * (0.5f + 0.5f * p),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = animatedColor.copy(alpha = 0.15f),
            border = BorderStroke(3.dp, animatedColor),
            modifier = Modifier.size(104.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 44.sp) }
        }
    }
}

/** Green when we got 5 GHz (no fight with the headset's Bluetooth), amber otherwise. */
@Composable
private fun BandChip(link: LinkInfo) {
    val good = link.band == Band.GHZ_5 || link.band == Band.GHZ_6
    val color = if (good) PillionColors.Good else PillionColors.Warn
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            if (good) "${link.band.label} · clear of Bluetooth" else "${link.band.label} · may interfere with Bluetooth",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The talk controls: where audio is going, a big mute button, and live link quality.
 */
@Composable
private fun VoicePanel(
    voice: VoiceStats,
    onToggleMute: () -> Unit,
    onSelectOutput: (Int) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBoostChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (voice.muted) "🔇" else "🎙️", fontSize = 28.sp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            voice.error != null -> "Voice problem"
                            !voice.running -> "Voice starting…"
                            voice.hearingPeer -> "Talking"
                            else -> "Waiting for the other phone"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        voice.error ?: voice.output?.let { "Audio: ${it.label}" } ?: "Choosing audio output…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (voice.error != null) PillionColors.Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (voice.outputs.isNotEmpty()) {
                OutputPicker(voice.outputs, voice.output, onSelectOutput)
            }
            if (voice.output?.kind == OutputKind.SPEAKER) {
                Text(
                    "On speaker, keep the phones apart or you'll get echo and howling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PillionColors.Warn,
                )
            }

            if (voice.running) {
                VolumeControl(voice.volume, voice.boost, onVolumeChange, onBoostChange)
            }

            // Big mute toggle. Amber when you're live, red when muted, so it's obvious at a glance.
            Button(
                onClick = onToggleMute,
                enabled = voice.running,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (voice.muted) {
                    ButtonDefaults.buttonColors(containerColor = PillionColors.Bad, contentColor = Color.Black)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (voice.muted) "Muted · tap to talk" else "Mute my mic", style = MaterialTheme.typography.titleMedium)
            }

            if (voice.running) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Ping", voice.pingMs?.let { "$it ms" } ?: "–")
                    Stat("Loss", "%.1f%%".format(voice.lossPercent))
                    Stat("Buffer", "${voice.bufferedMs} ms")
                    Stat("Gaps", voice.underruns.toString())
                }
            }
        }
    }
}

/**
 * One chip per available output. FilterChip is Material's "pick one of these" chip
 * (Flutter: ChoiceChip). It scrolls sideways if there are too many to fit.
 */
@Composable
private fun OutputPicker(outputs: List<AudioOutput>, current: AudioOutput?, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        outputs.forEach { output ->
            val selected = output.kind == current?.kind && output.label == current.label
            FilterChip(
                selected = selected,
                onClick = { onSelect(output.id) },
                label = { Text("${output.kind.emoji}  ${output.label}", maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/**
 * How loud the other person is. The slider is the phone's call volume (the volume buttons move
 * it too). Boost goes past the phone's maximum for loud roads.
 */
@Composable
private fun VolumeControl(
    volume: Float,
    boost: Boolean,
    onVolumeChange: (Float) -> Unit,
    onBoostChange: (Boolean) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🔈", fontSize = 18.sp)
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text("🔊", fontSize = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Boost", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Louder than max, for wind and engine noise",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = boost, onCheckedChange = onBoostChange)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tap to expand the nerdy details. rememberSaveable keeps it open across screen rotation. */
@Composable
private fun ConnectionDetails(link: LinkInfo) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Hide connection details" else "Show connection details")
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Network", link.networkName)
                    DetailRow("Frequency", "${link.frequencyMhz} MHz")
                    DetailRow("This phone", link.myIp ?: "?")
                    DetailRow("Rider's phone", link.hostIp ?: "?")
                    DetailRow("Interface", link.interfaceName ?: "?")
                    link.fallbackReason?.let {
                        Text(
                            "Why not 5 GHz: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = PillionColors.Warn,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// --- Previews: every state, without a phone ---------------------------------------------------

private val previewLink = LinkInfo(
    role = Role.RIDER,
    networkName = "DIRECT-PT-PillionTalk",
    frequencyMhz = 5745,
    hostIp = "192.168.49.1",
    myIp = "192.168.49.1",
    interfaceName = "p2p-wlan0-0",
    fallbackReason = null,
)

private val previewHeadset = AudioOutput(1, OutputKind.BLUETOOTH, "Helmet BT")

private val previewVoice = VoiceStats(
    running = true,
    outputs = listOf(
        previewHeadset,
        AudioOutput(2, OutputKind.EARPIECE, "Phone"),
        AudioOutput(3, OutputKind.SPEAKER, "Speaker"),
    ),
    output = previewHeadset,
    received = 1200,
    lost = 3,
    pingMs = 6,
    bufferedMs = 60,
    hearingPeer = true,
)

@Preview(name = "Rider waiting", showBackground = true, backgroundColor = 0xFF0E1318, heightDp = 900)
@Composable
private fun RiderWaitingPreview() {
    PillionTalkTheme {
        RideScreen(
            RideState.Connected(previewLink, emptyList()),
            voice = previewVoice.copy(hearingPeer = false, pingMs = null),
            onToggleMute = {}, onSelectOutput = {}, onVolumeChange = {}, onBoostChange = {}, onEnd = {}, onRetry = {},
        )
    }
}

@Preview(name = "Pillion talking", showBackground = true, backgroundColor = 0xFF0E1318, heightDp = 900)
@Composable
private fun PillionTalkingPreview() {
    PillionTalkTheme {
        RideScreen(
            RideState.Connected(previewLink.copy(role = Role.PILLION, myIp = "192.168.49.23"), listOf("Nothing Phone (4a) Pro")),
            voice = previewVoice,
            onToggleMute = {}, onSelectOutput = {}, onVolumeChange = {}, onBoostChange = {}, onEnd = {}, onRetry = {},
        )
    }
}

@Preview(name = "Muted", showBackground = true, backgroundColor = 0xFF0E1318, heightDp = 900)
@Composable
private fun MutedPreview() {
    PillionTalkTheme {
        RideScreen(
            RideState.Connected(previewLink, listOf("Redmi Note 11 Pro+")),
            voice = previewVoice.copy(muted = true),
            onToggleMute = {}, onSelectOutput = {}, onVolumeChange = {}, onBoostChange = {}, onEnd = {}, onRetry = {},
        )
    }
}

@Preview(name = "Failed", showBackground = true, backgroundColor = 0xFF0E1318, heightDp = 800)
@Composable
private fun FailedPreview() {
    PillionTalkTheme {
        RideScreen(
            RideState.Failed(Role.PILLION, "Couldn't find your rider's phone. Is it hosting a ride nearby?"),
            voice = VoiceStats(),
            onToggleMute = {}, onSelectOutput = {}, onVolumeChange = {}, onBoostChange = {}, onEnd = {}, onRetry = {},
        )
    }
}
