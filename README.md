# Pillion Talk

An offline intercom for a rider and pillion. Two Android phones talk directly to each other over Wi-Fi Direct, and each phone carries the audio to and from its rider's helmet Bluetooth headset. No mobile data, no internet, no calls.

```
helmet headset ⇄ (Bluetooth) ⇄ phone ⇄ (Wi-Fi Direct, 5 GHz, UDP) ⇄ phone ⇄ (Bluetooth) ⇄ helmet headset
```

## Status

Phase 1 in progress. The app has a home screen where you pick your seat, and a ride screen:

- **I'm riding** creates a Wi-Fi Direct group (preferring 5 GHz) and waits for the pillion.
- **I'm the pillion** finds and joins the rider's group.
- Both screens show live connection status, the band in use, and connection details.
- **Voice v1:** once connected, both phones stream raw 16 kHz audio to each other over UDP, routed to a Bluetooth headset if one is connected. There's a mute button and live stats: ping, packet loss, buffer and gaps.

Next: Opus compression, a foreground service so voice keeps going with the screen off, and auto-reconnect.

## Stack

- Kotlin + Jetpack Compose, Android 12+ (minSdk 31)
- Wi-Fi Direct group on 5 GHz (avoids interfering with the headset's 2.4 GHz Bluetooth)
- UDP between the phones
- `AudioRecord` / `AudioTrack` at 16 kHz mono, routed to the headset in call mode
- Later: Opus codec (via the NDK), QR pairing, music share

## Roadmap

1. **Proof of concept:** Wi-Fi Direct group, raw voice over UDP through the headsets, debug stats
2. **Intercom MVP:** Opus, jitter buffer, foreground service, auto-reconnect
3. **Pairing:** one-time QR code pairing, automatic joining
4. **Music share:** built-in player, mixed with voice and ducked when someone talks
5. **Later:** capture from other music apps, group rides, headset button controls

## Download

Every push to `main` builds a fresh debug APK and publishes it as the
[latest-debug release](https://github.com/PrajwalHarish30/PillionTalk/releases/tag/latest-debug).
Direct link for phones:

https://github.com/PrajwalHarish30/PillionTalk/releases/download/latest-debug/PillionTalk-debug.apk

## Building

Open the folder in Android Studio, or from a terminal:

```bash
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.
