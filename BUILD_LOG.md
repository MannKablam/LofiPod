# Build Log

Running notes on what's changed and why. Newest at top.

## Backup/restore + cumulative listen tracking + notes table

- **Schema v3** (additive migration): adds `cumulativeListenMs` to `episode_state` (default 0) and a new `episode_note` table (`guid`, `text`, `updatedAt`).
- **Cumulative tracking**: `PlaybackService` ticker now bumps `cumulativeListenMs` by the tick interval (10 s) only on the periodic save while `isPlaying`. Save-on-pause / save-on-task-removed / save-on-destroy use `listenDelta = 0` to avoid double-counting. `MetricsScreen` reads from this field — hours are now exact, not an approximation of `positionMs`.
- **Backup export/import** via a single JSON file. Schema-versioned and forward-compatible: older backups missing newer fields default cleanly, newer backups are refused with a clear message. Includes everything tied to the user: episode states (favorites, ratings, positions, cumulative time) and notes. SAF picker on both ends — no FileProvider required.
- **MetricsScreen top bar** gets two new actions: 📥 export to a JSON file (default name `lofipod-backup-YYYY-MM-DD.json`, UTC date) and 📤 import from a backup file (with a confirm dialog). Snackbar reports the result counts.
- **Notes table is in place** so any user-generated note will travel with the export from day one. The notes UI ships in a follow-up commit.

## Plane shift: hardcoded podcast canon + Metrics screen

- `data/Sources.kt` is now the single source of truth for the podcast list. The runtime reads it directly. To change the canon: edit, commit, build, sideload — that friction is the feature.
- All in-app add/remove paths removed: file picker, Import button, long-press-to-remove, the empty-state file-pick prompt. The (now dormant) `podcast_source` Room table is left in place to avoid a pointless destructive migration.
- `sources.md` deleted from the repo. iTunes IDs preserved as a header comment in `Sources.kt` so the canon can be re-resolved if a host migrates.
- Top bar gains a **Metrics** button (replaces the old Import slot).
- New `MetricsScreen`: per-podcast hours-listened (decimal, 2 dp) + each podcast's favorited episodes inline. Header summary across all podcasts. Hours derive from `EpisodeStateEntity.positionMs` — approximate; exact cumulative tracking is queued.

## Player polish + mini-player + artwork fix

- **Artwork**: parser now falls back to `<itunes:image>` text content when `href` is missing/blank. Some feeds (likely the trio that didn't render) put the URL between the tags instead of as an attribute.
- **Speed slider removed** from the Player screen — too easy to brush by accident. Speed control will move to the EQ/audio screen if needed.
- **Bigger transport buttons** on the Player screen: play +50% (now 108 dp), skip-back / skip-forward +100% (now 80 dp containers, 72 dp icons).
- **Episode descriptions** now show in the EpisodesScreen rows — 3-line cap, plain-text view (HTML tags + common entities stripped).
- **Mini-player** anchored to the bottom of every screen except the Player itself when something is loaded. Shows artwork + title + artist + play/pause; tap anywhere to jump to the full Player.

## Library is now in-app, sources file is just an import

- New Room table `podcast_source` (schema v2 with additive migration — favorites, ratings, and positions are preserved). Owns the user's podcast list independently of the original sources file.
- Picking a sources file now **merges** entries into the in-app library (dedupe by feed URL) instead of replacing. Same file picked twice is a no-op.
- `LibraryViewModel` reads from Room, not from the DataStore-stored URI. One-time bootstrap: existing users with a saved URI get an automatic import on first launch of this version, so nothing has to be re-picked.
- Top bar gains an **Import** action so a new file can be loaded at any time, not just from the empty state.
- Long-press a podcast → confirmation dialog → remove from library. Episode favorites/ratings are kept.

## Fix: hang on feeds that emit both `<description>` and `<itunes:summary>`

- Sibling bug to the earlier parser hang. Channel-level `<itunes:summary>` was a no-op (no parser advance) when `<description>` had already populated `channelDesc` — infinite loop. ccmodesto.com, feeds.castos.com, and anchor.fm all emit both, in that order.
- Added `else skip(parser)` to that branch — same shape as the prior fix.
- Also switched `PodcastRepository.fetchOne` from `withContext` to `runInterruptible(Dispatchers.IO)`. The 60 s `withTimeoutOrNull` was previously unable to actually cancel a blocking `OkHttp.Call.execute()` or a tight parser loop, so a hung feed sat on its spinner forever despite the named timeout. `runInterruptible` propagates cancellation as a thread interrupt, which OkHttp respects.

## Lofi visual refresh

- **Launcher icon** redrawn as chunky pixel-art headphones (stepped arc + blocky ear cups with dark inner drivers and small highlights). Single vector, no PNG mipmaps.
- **Color scheme** swapped from warm-sepia to cool-dusky lofi: deep navy background (`#1A1B2E`), dusky indigo surfaces, warm amber/honey primary (`#E6B469`), muted teal secondary (`#7BB4C4`), warm cream text. Headband matches the amber accent. Light scheme: cream-tan backdrop, deeper bronze primary.
- Status bar / nav bar / window background swapped to match the new navy.

## Per-feed loading progress

- `PodcastRepository.fetchFeeds` now takes an `onProgress(SourceEntry, FeedStatus, errorMessage)` callback fired as each feed transitions (LOADING → OK / FAILED / TIMEOUT).
- `LibraryViewModel` seeds `feedProgress` with all feeds in LOADING state before fetch starts, then atomically updates each entry as the callback fires.
- `LibraryScreen` loading state now shows `Loading feeds (n/total)` plus a row per feed with status icon (spinner / check / error / hourglass), display name, and inline error message on failure.
- A single hung feed is now visible instead of presenting as a blank spinner.

## Standardize sources.md to iTunes-canonical feed URLs

- Each feed now matches the `feedUrl` Apple Podcasts has registered (verified via the iTunes Lookup API).
- Added an `# iTunes ID: <collectionId>` comment above each URL so the canonical can be re-resolved later.
- 7 of 8 URLs were already pointing at the iTunes-canonical destination — only Bethany Bible Church changed (host swap from `bethanybiblechurch.org` → `www.bethanyto.org`, same UUID path, same content).

## Fix: feed loading hangs forever on namespaced channel tags

- `RssParser` channel-level handlers for `<title>` and `<description>` were no-ops when the tag had a non-empty namespace (e.g. `<itunes:title>`), and crucially didn't advance the parser. One such tag in any feed → infinite loop in the parse loop → spinner never resolved.
- Added `else skip(parser)` to both branches.
- Also parallelized `PodcastRepository.fetchFeeds` (was sequential — one slow feed delayed all others) and added a 60 s per-feed timeout, so a hung or extremely slow feed can no longer stall the whole library.

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
