package com.prajwal.pilliontalk.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** The kinds of places voice can come out of (and, for headsets, go in through). */
enum class OutputKind(val emoji: String) {
    BLUETOOTH("🎧"),
    WIRED("🔌"),
    EARPIECE("📱"),
    SPEAKER("🔊"),
}

/** One choice in the output picker. [id] is Android's id for the device. */
data class AudioOutput(val id: Int, val kind: OutputKind, val label: String)

/**
 * Decides where call audio goes: earpiece, loudspeaker, wired or Bluetooth headset.
 *
 * In "call mode" (MODE_IN_COMMUNICATION) Android routes both the mic and the sound to one
 * "communication device", and you choose it with setCommunicationDevice(). Picking a Bluetooth
 * headset makes the phone open the headset's call channel (SCO), so its mic is used too.
 *
 * Rules:
 * - By default pick the best available: Bluetooth headset, then wired, then earpiece.
 * - If the user picks something, stick with it.
 * - Except: when a headset is plugged in or connects mid-ride, switch to it (that's almost
 *   always why you connected it). If the chosen device disappears, fall back to the best.
 */
class AudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** What the user explicitly chose, or null for "automatic". */
    @Volatile private var userChoice: OutputKind? = null

    @Volatile var outputs: List<AudioOutput> = emptyList()
        private set

    @Volatile var current: AudioOutput? = null
        private set

    /** Called whenever [outputs] or [current] change, so the engine can refresh the UI. */
    var onChanged: () -> Unit = {}

    // Android tells us when devices appear or disappear (headset connected, cable unplugged…).
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val headsetArrived = addedDevices.any { kindOf(it) == OutputKind.BLUETOOTH || kindOf(it) == OutputKind.WIRED }
            if (headsetArrived) userChoice = null // go back to automatic, which prefers the headset
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    // …and when the communication device actually changes (Bluetooth can take a moment to switch).
    private val deviceChangedListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        current = device?.let(::toOutput)
        onChanged()
    }

    fun start() {
        userChoice = null
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, deviceChangedListener)
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnCommunicationDeviceChangedListener(deviceChangedListener)
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        outputs = emptyList()
        current = null
    }

    /** The user tapped an output in the picker. */
    fun select(id: Int) {
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == id } ?: return
        userChoice = kindOf(device)
        apply(device)
    }

    private fun refresh() {
        val devices = audioManager.availableCommunicationDevices.filter { kindOf(it) != null }
        outputs = devices.map(::toOutput)
            .distinctBy { it.kind to it.label } // one headset can show up twice (classic + LE Audio)
            .sortedBy { it.kind.ordinal }

        val target = userChoice?.let { choice -> devices.firstOrNull { kindOf(it) == choice } }
            ?: devices.minByOrNull { kindOf(it)!!.ordinal } // BLUETOOTH < WIRED < EARPIECE < SPEAKER
        if (target != null && target.id != audioManager.communicationDevice?.id) {
            apply(target)
        } else {
            current = audioManager.communicationDevice?.let(::toOutput)
            onChanged()
        }
    }

    private fun apply(device: AudioDeviceInfo) {
        audioManager.setCommunicationDevice(device)
        current = toOutput(device)
        onChanged()
    }

    private fun toOutput(device: AudioDeviceInfo): AudioOutput {
        val kind = kindOf(device) ?: OutputKind.SPEAKER
        val label = when (kind) {
            OutputKind.BLUETOOTH -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Headset"
            OutputKind.WIRED -> "Wired"
            OutputKind.EARPIECE -> "Phone"
            OutputKind.SPEAKER -> "Speaker"
        }
        return AudioOutput(device.id, kind, label)
    }

    private fun kindOf(device: AudioDeviceInfo): OutputKind? = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
        else -> null
    }
}
