# Build Log

Running notes on what's changed and why. Newest at top.

## Playback position persistence — `85981e9`

- Room row created on first play of an episode (using `EpisodeStateDao.upsert`).
- 10 s tick saves position while playing; immediate save on pause / task removed / destroy.
- Outgoing episode's position written *before* switching to a new one.
- New episodes resume from saved position via `setMediaItem(item, savedPos)`.
- Episodes within 5 s of end restart from 0 instead of resuming "at the end".

## CI compile fix: `setEnableAudioOffload` — `a32f42d`

- Removed `init { setEnableAudioOffload(false) }` from `EqRenderersFactory`.
- Method doesn't exist on Media3 1.4.1's `DefaultRenderersFactory`.
- Offload stays off because we override `buildAudioSink` to return our own `DefaultAudioSink` (no offload configured).

## Initial scaffold — `d43d593`

- UI (Compose Material3), RSS ingest, custom DSP (10-band biquad peaking EQ + tanh soft-clipper), Media3 playback.
- Room for favorites / ratings / positions; DataStore for sources URI + EQ state.
- Defensive `@file:OptIn(ExperimentalMaterial3Api::class)` on `LibraryScreen` for `Card(onClick = ...)`.
- GitHub Actions workflow `build.yml` builds the debug APK on every push and uploads it as an artifact.
