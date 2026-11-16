# Pillion Talk

An offline intercom for a rider and pillion. Two Android phones talk directly to each other over Wi-Fi Direct, and each phone carries the audio to and from its rider's helmet Bluetooth headset. No mobile data, no internet, no calls.

```
helmet headset ⇄ (Bluetooth) ⇄ phone ⇄ (Wi-Fi Direct, 5 GHz, UDP) ⇄ phone ⇄ (Bluetooth) ⇄ helmet headset
```

## Status

Phase 1, nearly done. What works today:

- **Start a ride / Join a ride:** the rider's phone creates a Wi-Fi Direct group (preferring 5 GHz) and the pillion's phone joins it.
- **Two-way voice** over UDP, routed to a Bluetooth headset when one is connected. It keeps running in the background through a foreground service.
- **Names:** each rider sets a name once, and it's shown on the other phone.
- **Ride screen** (pure mono, light and dark): speaking rings around whoever is talking, an output dropdown with a 0–200% volume (above 100% is boost), tap the other person to change their volume or silence them, and a details sheet with the link stats.
- **Fun status lines** ("Yapping at full throttle", "Mic's parked"…) shared by the screen and a call-style notification, which has a ride timer and Mute, Speaker and Hang up buttons on the lock screen.

Next: Opus compression, auto-reconnect, QR pairing.

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
