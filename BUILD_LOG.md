# Build Log

Running notes on what's changed and why. Newest at top.

## Offline downloads

- New `DownloadHolder` constructs the Media3 download stack on app start: `StandaloneDatabaseProvider`, a `SimpleCache` under `filesDir/downloads`, a `DownloadManager` (max 2 concurrent), and a cache-aware `CacheDataSource.Factory`.
- `LofiPodDownloadService` (Media3 `DownloadService` subclass) runs the actual downloads as a `dataSync` foreground service with progress notifications.
- `Downloads` exposes a `StateFlow<Map<guid, Download>>` so any UI can render per-episode state.
- `PlaybackService` now plugs the cache-aware factory into ExoPlayer via `DefaultMediaSourceFactory.setDataSourceFactory`, so downloaded episodes play locally and streamed episodes still hit HTTP.
- Episode rows show a download button that morphs through idle / downloading-with-progress / completed / failed-retry states.
- Favorites screen gets a third "Downloaded" tab listing completed downloads (resolved against the existing `EpisodeStateEntity` rows for title/artwork).
- Manifest gets the `FOREGROUND_SERVICE_DATA_SYNC` permission and the new service entry.

## Curated sources file — `sources.md`

- Lists 8 verified RSS feeds (Damian Kyle, James White / AOM, Mike Winger / BibleThinker, Piper x3, Just Thinking, Bethany Bible Church).
- Each URL was fetched and confirmed to return valid RSS 2.0 with audio `<enclosure>` items.
- Only one display-name override (Damian Kyle's series, since the feed's own title doesn't mention him).

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
