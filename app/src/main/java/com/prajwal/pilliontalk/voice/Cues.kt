package com.prajwal.pilliontalk.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The little sounds the app plays into your headset: someone joined, someone left, mic on/off.
 *
 * There are no sound files. Each cue is made of a few short notes, generated as raw samples
 * when the app starts (the same 16 kHz, 16-bit format as the voice). VoiceEngine mixes them
 * into the voice it's playing, so they go wherever the voice goes: helmet headset, earpiece
 * or speaker. A separate player could end up on the wrong output.
 *
 * Each note is a sine wave (a pure tone) plus a little of its octave for warmth, shaped by an
 * "envelope": a 5 ms fade in, so it doesn't click, and an exponential fade out, like a struck bell.
 */
enum class Cue(val samples: ShortArray) {
    /** You're in the ride: a single soft note. */
    YOU_JOINED(notes(Note(784.0, 160))),

    /** The other person arrived: rising, bright. */
    PEER_JOINED(notes(Note(659.3, 110), Note(880.0, 110), Note(1318.5, 220))),

    /** The other person left or dropped off: falling. */
    PEER_LEFT(notes(Note(1318.5, 110), Note(880.0, 110), Note(587.3, 240))),

    /** Mic off: two notes going down. */
    MUTED(notes(Note(880.0, 80), Note(587.3, 130))),

    /** Mic on: two notes going up. */
    UNMUTED(notes(Note(587.3, 80), Note(880.0, 130))),
}

private class Note(val hz: Double, val ms: Int)

private const val RATE = VoiceEngine.SAMPLE_RATE
private const val LOUDNESS = 0.22 // of full scale: noticeable in a helmet, not startling

private fun notes(vararg notes: Note): ShortArray {
    val out = ArrayList<Short>()
    for (note in notes) {
        val count = RATE * note.ms / 1000
        val fadeIn = RATE * 5 / 1000
        for (i in 0 until count) {
            val t = i.toDouble() / RATE
            val envelope = min(1.0, i.toDouble() / fadeIn) * exp(-4.0 * i / count)
            val wave = sin(2 * PI * note.hz * t) + 0.25 * sin(2 * PI * note.hz * 2 * t)
            out += (wave / 1.25 * envelope * LOUDNESS * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return out.toShortArray()
}
