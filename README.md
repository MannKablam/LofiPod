# LofiPod

A minimalist personal podcast app for Android (designed with GrapheneOS in mind).
**No discovery. No search. No tracking. No Google Play Services.** Your podcast list is a text file you control.

## Features

- **Sources file**: pick any `.md` or `.txt` file via SAF — one feed URL per line
- **RSS ingest**: parses RSS 2.0 + iTunes namespace
- **Solid playback**: ExoPlayer (Media3), background playback, lock-screen / Bluetooth controls, ±15s / +30s skip, variable speed (0.5x–3x)
- **Custom DSP**: 10-band graphic EQ + volume boost (up to +12 dB) with tanh soft-clipping. Real-time biquad filter chain inserted into ExoPlayer's audio sink.
- **Favorites & ratings**: 5-star rating + favorite flag, persisted in Room
- **Share raw enclosure URL**: any episode → Android share sheet → send the direct audio link to anyone
- **Lofi aesthetic**: warm dark theme, large artwork

## Sources file format

Plain text or markdown. One feed per line. `#` starts a comment.

```
# My podcasts
https://feeds.simplecast.com/54nAGcIl | Hardcore History
https://feeds.megaphone.fm/darknetdiaries
https://lexfridman.com/feed/podcast/
```

The `| name` part is optional — without it, the title from the feed is used.

## Build

Standard Android build. Requires JDK 17, Android SDK 34.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
ui/        Compose screens + navigation
data/      Repository, Room DB, DataStore settings
parser/    Sources file parser, RSS parser (XmlPullParser)
audio/     EqAudioProcessor (custom DSP), Biquad, presets
player/    PlaybackService (Media3), PlayerController, EqRenderersFactory
util/      Share helper
```

The EQ is wired into the audio path via a custom `RenderersFactory` →
`DefaultAudioSink` → `EqAudioProcessor` (a `BaseAudioProcessor` subclass).
This means EQ + gain run on every supported decoded stream automatically.

## What's not in v1

- Episode downloads for offline (easy to add — Media3 has DownloadManager)
- Sleep timer
- CarPlay / Android Auto
- Sync between devices
- Chapter markers
```
