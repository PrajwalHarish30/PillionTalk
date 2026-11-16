package com.prajwal.pilliontalk.voice

import java.nio.ByteBuffer

/**
 * The tiny protocol the two phones speak over UDP. Every packet starts with one "type" byte:
 *
 *   VOICE: [type][sequence: 2 bytes][20 ms of audio: 640 bytes]
 *   PING:  [type][timestamp: 8 bytes]   "what time is it on your side?"
 *   PONG:  [type][timestamp: 8 bytes]   the same timestamp, sent straight back
 *
 * The sequence number counts voice packets 0, 1, 2, … (wrapping after 65535). If the receiver
 * sees 41 then 43, it knows packet 42 was lost. UDP doesn't resend lost packets, and for live
 * voice that's what we want: a packet that arrives late is useless anyway.
 *
 * PING/PONG measure the round trip: send a ping with "now", get it back, subtract.
 */
object Packets {
    const val VOICE: Byte = 1
    const val PING: Byte = 2
    const val PONG: Byte = 3

    const val VOICE_HEADER_BYTES = 3

    fun voice(sequence: Int, audio: ByteArray, audioLength: Int): ByteArray =
        ByteBuffer.allocate(VOICE_HEADER_BYTES + audioLength)
            .put(VOICE)
            .putShort(sequence.toShort())
            .put(audio, 0, audioLength)
            .array()

    fun ping(timestampMs: Long): ByteArray = ByteBuffer.allocate(9).put(PING).putLong(timestampMs).array()

    fun pong(timestampMs: Long): ByteArray = ByteBuffer.allocate(9).put(PONG).putLong(timestampMs).array()

    /** Reads the 2-byte sequence number as 0..65535 (Kotlin's Short is signed, so mask it). */
    fun sequenceOf(packet: ByteArray): Int = ByteBuffer.wrap(packet, 1, 2).short.toInt() and 0xFFFF

    fun timestampOf(packet: ByteArray): Long = ByteBuffer.wrap(packet, 1, 8).long
}
