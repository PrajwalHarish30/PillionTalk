package com.prajwal.pilliontalk.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the voice link is doing, for the UI. Updated twice a second. */
data class VoiceStats(
    val running: Boolean = false,
    val muted: Boolean = false,
    /** Where the audio is going, e.g. "Helmet headset (XYZ)". */
    val output: String = "",
    val sent: Int = 0,
    val received: Int = 0,
    val lost: Int = 0,
    /** Round trip time from the last ping, or null before the first reply. */
    val pingMs: Long? = null,
    /** How much audio is waiting to be played, in milliseconds. */
    val bufferedMs: Int = 0,
    /** Times we had nothing to play and filled in silence. */
    val underruns: Int = 0,
    /** True if we've heard from the other phone in the last 2 seconds. */
    val hearingPeer: Boolean = false,
    val error: String? = null,
) {
    val lossPercent: Double get() = if (received + lost == 0) 0.0 else lost * 100.0 / (received + lost)
}

/**
 * Two-way voice between the phones: microphone → UDP → other phone → speaker/headset.
 *
 * Phase 1 sends raw, uncompressed audio: 16,000 samples a second, 16 bits each, one channel
 * = 32 KB/s, or 256 kbit/s. That's nothing for Wi-Fi. Phase 2 swaps in the Opus codec.
 *
 * It runs on four plain threads, not coroutines. Audio is real-time: if a thread is late by
 * even 20 ms you hear a glitch, so each job gets its own high-priority thread that does
 * nothing else.
 *   1. capture:  read 20 ms from the mic → send it
 *   2. receive:  wait for packets → voice goes into the jitter buffer, pings get answered
 *   3. playback: take 20 ms from the jitter buffer → play it (or silence if it's empty)
 *   4. control:  send a ping every second, publish stats twice a second
 */
@SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before a ride starts.
class VoiceEngine(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()

    // @Volatile: written by one thread, read by others. Guarantees they all see the latest value.
    @Volatile private var running = false
    @Volatile private var muted = false
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var lastHeardAt = 0L

    private var socket: DatagramSocket? = null
    private var output = ""
    private val threads = mutableListOf<Thread>()

    /**
     * The jitter buffer. Packets don't arrive perfectly every 20 ms over Wi-Fi; they bunch
     * up and spread out. Holding a few frames back smooths that over. It's capped at
     * MAX_BUFFERED_FRAMES so delay can't build up: if it's full, the oldest frame is dropped.
     */
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(MAX_BUFFERED_FRAMES)

    // Counters shared between threads. Atomic* types are safe to update from several threads.
    private val sent = AtomicInteger()
    private val received = AtomicInteger()
    private val lost = AtomicInteger()
    private val underruns = AtomicInteger()
    private val lastPingMs = AtomicLong(-1)

    /**
     * Start talking. [peerIp] is the other phone's address if we know it (the pillion knows
     * the rider is at 192.168.49.1). The rider passes null and learns the pillion's address
     * from the first packet that arrives.
     */
    @Synchronized // start and stop can be called from different threads; never let them overlap
    fun start(peerIp: String?) {
        if (running) return
        resetCounters()
        peer = peerIp?.let { InetSocketAddress(InetAddress.getByName(it), PORT) }

        try {
            socket = DatagramSocket(PORT).apply { soTimeout = 500 }
            output = routeAudioToBestDevice()
        } catch (e: Exception) {
            stop()
            _stats.value = VoiceStats(error = "Couldn't start voice: ${e.message}")
            return
        }

        running = true
        threads += audioThread("capture", ::captureLoop)
        threads += audioThread("receive", ::receiveLoop)
        threads += audioThread("playback", ::playbackLoop)
        threads += Thread(::controlLoop, "voice-control")
        threads.forEach { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        socket?.close() // unblocks the receive thread
        threads.forEach { it.join(1_000) }
        threads.clear()
        socket = null
        jitterBuffer.clear()
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        _stats.value = _stats.value.copy(running = false, hearingPeer = false)
    }

    fun setMuted(value: Boolean) {
        muted = value
        _stats.value = _stats.value.copy(muted = value)
    }

    // --- Routing --------------------------------------------------------------------------------

    /**
     * Put the phone in "call mode" and choose where audio goes: helmet Bluetooth headset first,
     * then wired headphones, then the earpiece (quiet, so two phones in one room don't howl).
     */
    private fun routeAudioToBestDevice(): String {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val devices = audioManager.availableCommunicationDevices
        val choice =
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        if (choice != null) audioManager.setCommunicationDevice(choice)

        return when (choice?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset (${choice.productName})"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            else -> "Phone speaker"
        }
    }

    // --- The four loops ---------------------------------------------------------------------------

    private fun captureLoop() {
        val recorder = AudioRecord.Builder()
            // VOICE_COMMUNICATION = the mic as set up for calls: the phone switches on its own
            // echo cancellation, noise suppression and gain control, tuned for that model.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(maxOf(FRAME_BYTES * 4, AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)))
            .build()
        // Belt and braces: switch the effects on explicitly where the phone offers them.
        AcousticEchoCanceler.create(recorder.audioSessionId)?.enabled = true
        NoiseSuppressor.create(recorder.audioSessionId)?.enabled = true

        val frame = ByteArray(FRAME_BYTES)
        var sequence = 0
        recorder.startRecording()
        try {
            while (running) {
                // Blocks until 20 ms of audio is ready, so this loop runs exactly 50 times a second.
                val read = recorder.read(frame, 0, FRAME_BYTES)
                if (read <= 0) continue
                val target = peer ?: continue // rider: nobody to send to until the pillion says hello
                if (muted) continue
                send(Packets.voice(sequence, frame, read), target)
                sequence = (sequence + 1) and 0xFFFF
                sent.incrementAndGet()
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        var expectedSequence = -1

        while (running) {
            try {
                socket?.receive(packet) ?: return
            } catch (e: java.net.SocketTimeoutException) {
                continue // nothing for 500 ms; loop round and check `running`
            } catch (e: Exception) {
                return // socket closed by stop()
            }

            // Whoever talks to us is our peer. This is how the rider learns the pillion's address.
            peer = packet.socketAddress
            lastHeardAt = SystemClock.elapsedRealtime()

            when (buffer[0]) {
                Packets.VOICE -> {
                    val sequence = Packets.sequenceOf(buffer)
                    if (expectedSequence >= 0) {
                        val gap = (sequence - expectedSequence) and 0xFFFF
                        if (gap in 1..1000) lost.addAndGet(gap) // a jump means packets went missing
                    }
                    expectedSequence = (sequence + 1) and 0xFFFF
                    received.incrementAndGet()

                    val audio = buffer.copyOfRange(Packets.VOICE_HEADER_BYTES, packet.length)
                    if (!jitterBuffer.offer(audio)) { // full: drop the oldest to keep delay low
                        jitterBuffer.poll()
                        jitterBuffer.offer(audio)
                    }
                }
                Packets.PING -> send(Packets.pong(Packets.timestampOf(buffer)), packet.socketAddress)
                Packets.PONG -> lastPingMs.set(SystemClock.elapsedRealtime() - Packets.timestampOf(buffer))
            }
        }
    }

    private fun playbackLoop() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // "this is a call": follows call routing and volume
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(maxOf(FRAME_BYTES * 4, AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val silence = ByteArray(FRAME_BYTES)
        track.play()
        try {
            while (running) {
                // Nothing to play: fill in silence. Only count it as a "gap" once the other phone
                // has started sending, and only if it isn't simply muted (no packets while muted).
                val frame = jitterBuffer.poll() ?: silence.also {
                    if (received.get() > 0 && SystemClock.elapsedRealtime() - lastHeardAt < 200) underruns.incrementAndGet()
                }
                // write() blocks until there's room, so it paces this loop at real time.
                track.write(frame, 0, frame.size)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun controlLoop() {
        var tick = 0
        while (running) {
            if (tick % 2 == 0) peer?.let { send(Packets.ping(SystemClock.elapsedRealtime()), it) }
            publishStats()
            tick++
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun send(bytes: ByteArray, to: SocketAddress) {
        try {
            socket?.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: Exception) {
            // A dropped packet is fine for live audio; the watcher in RideViewModel notices real disconnects.
        }
    }

    private fun publishStats() {
        _stats.value = VoiceStats(
            running = running,
            muted = muted,
            output = output,
            sent = sent.get(),
            received = received.get(),
            lost = lost.get(),
            pingMs = lastPingMs.get().takeIf { it >= 0 },
            bufferedMs = jitterBuffer.size * FRAME_MS,
            underruns = underruns.get(),
            hearingPeer = SystemClock.elapsedRealtime() - lastHeardAt < 2_000,
        )
    }

    private fun resetCounters() {
        listOf(sent, received, lost, underruns).forEach { it.set(0) }
        lastPingMs.set(-1)
        lastHeardAt = 0
        jitterBuffer.clear()
    }

    private fun audioThread(name: String, body: () -> Unit) = Thread({
        // The highest priority an app can ask for: audio threads must never wait behind UI work.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        body()
    }, "voice-$name")

    private fun format(channelMask: Int) = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(channelMask)
        .setEncoding(ENCODING)
        .build()

    companion object {
        const val PORT = 50_505
        const val SAMPLE_RATE = 16_000 // "wideband" voice: the same rate as HD Voice calls
        const val FRAME_MS = 20
        const val FRAME_BYTES = SAMPLE_RATE / 1000 * FRAME_MS * 2 // 320 samples × 2 bytes = 640
        const val MAX_BUFFERED_FRAMES = 6 // at most 120 ms waiting to be played
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
