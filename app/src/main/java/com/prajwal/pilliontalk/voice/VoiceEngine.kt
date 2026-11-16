package com.prajwal.pilliontalk.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.wifi.WifiManager
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** What the voice link is doing, for the UI. Updated twice a second, and when the output changes. */
data class VoiceStats(
    val running: Boolean = false,
    val muted: Boolean = false,
    /** Where voice can go, for the output picker. */
    val outputs: List<AudioOutput> = emptyList(),
    /** Where voice is going right now. */
    val output: AudioOutput? = null,
    val sent: Int = 0,
    val received: Int = 0,
    val lost: Int = 0,
    /** Round trip time from the last ping, or null before the first reply. */
    val pingMs: Long? = null,
    /** How much audio is waiting to be played, in milliseconds. */
    val bufferedMs: Int = 0,
    /** Times a frame wasn't there when it was needed and we had to cover the gap. */
    val underruns: Int = 0,
    /** True if we've heard from the other phone in the last 2 seconds. */
    val hearingPeer: Boolean = false,
    /** Call volume, 0.0 to 1.0. The phone's volume buttons change it too. */
    val volume: Float = 0f,
    /** Extra digital gain on top of the maximum volume, for loud roads. */
    val boost: Boolean = false,
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
 *   3. playback: take 20 ms from the jitter buffer → play it (covering any gaps)
 *   4. control:  send a ping every second, publish stats twice a second
 */
@SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before a ride starts.
class VoiceEngine(context: Context) {

    private val router = AudioRouter(context).apply { onChanged = { publishStats() } }
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Wi-Fi saves power by napping between packets and delivering them in bursts. Great for web
     * pages, terrible for voice: you hear it as stutter. A low-latency lock asks the phone not to
     * nap while we hold it. Android honours it while the app is on screen (which it is: the ride
     * screen keeps the display on).
     */
    private val wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PillionTalk:voice")
        .apply { setReferenceCounted(false) }

    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()

    // @Volatile: written by one thread, read by others. Guarantees they all see the latest value.
    @Volatile private var running = false
    @Volatile private var muted = false
    @Volatile private var boostGain = 1f
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var lastHeardAt = 0L
    @Volatile private var error: String? = null

    private var socket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()

    /**
     * The jitter buffer. Packets don't arrive perfectly every 20 ms over Wi-Fi; they bunch up
     * and spread out. Holding a few frames back smooths that over.
     * - Playback waits until TARGET_FRAMES are queued before starting ("prefill").
     * - It's capped at MAX_BUFFERED_FRAMES; when full, the oldest frame is dropped so delay
     *   can't keep growing.
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
            router.start()
            wifiLock.acquire()
        } catch (e: Exception) {
            stop()
            error = "Couldn't start voice: ${e.message}"
            publishStats()
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
        router.stop()
        if (wifiLock.isHeld) wifiLock.release()
        publishStats()
    }

    fun setMuted(value: Boolean) {
        muted = value
        publishStats()
    }

    /** Send voice to a different output (earpiece, speaker, headset…). */
    fun selectOutput(id: Int) = router.select(id)

    /**
     * Sets the phone's call volume (the same one the volume buttons change during a call),
     * as a fraction of its maximum. Android's call volume has a minimum above zero, so it
     * never goes fully silent; use mute on the other phone for that.
     */
    fun setVolume(fraction: Float) {
        val stream = AudioManager.STREAM_VOICE_CALL
        val min = audioManager.getStreamMinVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val index = (min + fraction.coerceIn(0f, 1f) * (max - min)).roundToInt()
        audioManager.setStreamVolume(stream, index, 0)
        publishStats()
    }

    /**
     * Boost multiplies every sample by 2 (+6 dB) before playing it: louder than the phone's
     * maximum, for wind and engine noise. Loud peaks get clipped (flattened), which can sound
     * harsh, so it's opt-in.
     */
    fun setBoost(on: Boolean) {
        boostGain = if (on) BOOST_GAIN else 1f
        publishStats()
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
                        if (gap > 1000) continue // an old, late packet: too late to play, skip it
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
        var lastFrame: ByteArray? = null
        var concealed = 0      // how many frames in a row we've had to make up
        var buffering = true   // waiting for the jitter buffer to fill to TARGET_FRAMES

        track.play()
        try {
            while (running) {
                // write() blocks until there's room, so every pass of this loop takes ~20 ms.
                if (buffering) {
                    if (jitterBuffer.size >= TARGET_FRAMES) buffering = false
                    track.write(silence, 0, FRAME_BYTES)
                    continue
                }

                val frame = jitterBuffer.poll()
                when {
                    frame != null -> {
                        val out = if (boostGain != 1f) scale(frame, boostGain) else frame
                        track.write(out, 0, out.size)
                        lastFrame = frame
                        concealed = 0
                    }
                    // A frame is late or lost. For one or two frames, replay the last one more
                    // quietly: the ear barely notices, where a hole of silence sounds like a click.
                    lastFrame != null && concealed < MAX_CONCEALED_FRAMES -> {
                        concealed++
                        lastFrame = scale(lastFrame, 0.5f)
                        track.write(lastFrame, 0, lastFrame.size)
                        if (isTalking()) underruns.incrementAndGet()
                    }
                    // Still nothing: the other side went quiet (muted) or the link hiccuped.
                    // Play silence and re-fill the buffer before playing again.
                    else -> {
                        if (isTalking()) underruns.incrementAndGet()
                        lastFrame = null
                        buffering = true
                        track.write(silence, 0, FRAME_BYTES)
                    }
                }
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

    /** Voice packets arrived in the last 200 ms, so a missing frame is a real gap, not a pause. */
    private fun isTalking() = SystemClock.elapsedRealtime() - lastHeardAt < 200

    /**
     * Returns a copy of a frame with every 16-bit sample multiplied by [gain]. Results are clamped
     * to the 16-bit range: without that, a loud sample would wrap around and become a loud crack.
     */
    private fun scale(frame: ByteArray, gain: Float): ByteArray {
        val out = frame.copyOf()
        val samples = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until samples.limit()) {
            val scaled = (samples.get(i) * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples.put(i, scaled.toShort())
        }
        return out
    }

    private fun currentVolume(): Float {
        val stream = AudioManager.STREAM_VOICE_CALL
        val min = audioManager.getStreamMinVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        return if (max == min) 1f else (audioManager.getStreamVolume(stream) - min).toFloat() / (max - min)
    }

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
            outputs = router.outputs,
            output = router.current,
            sent = sent.get(),
            received = received.get(),
            lost = lost.get(),
            pingMs = lastPingMs.get().takeIf { it >= 0 },
            bufferedMs = jitterBuffer.size * FRAME_MS,
            underruns = underruns.get(),
            hearingPeer = running && SystemClock.elapsedRealtime() - lastHeardAt < 2_000,
            volume = currentVolume(), // re-read every time, so the volume buttons show up too
            boost = boostGain != 1f,
            error = error,
        )
    }

    private fun resetCounters() {
        listOf(sent, received, lost, underruns).forEach { it.set(0) }
        lastPingMs.set(-1)
        lastHeardAt = 0
        error = null
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
        const val TARGET_FRAMES = 3       // start playing once 60 ms is queued
        const val MAX_BUFFERED_FRAMES = 8 // never more than 160 ms waiting to be played
        const val MAX_CONCEALED_FRAMES = 2
        const val BOOST_GAIN = 2f // +6 dB
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
