# Build Log

Running notes on what's changed and why. Newest at top.

## Episode preview: tap an episode title to inspect without playing

User flow:
- Catalog → tap a podcast → Episodes list.
- Tap an episode **title** → opens the Player screen, but in *preview mode*
  — same artwork / scrubber / Notes + Details tabs / heart toggle, but the
  audio isn't playing.
- Tap **Play** in the preview → it promotes to live playback. The screen
  flips to live mode automatically because `state.currentEpisodeGuid` now
  matches `previewGuid`.
- Tap anywhere else on the row (chevron, description, meta line) → still
  expands the in-card description, same as before.

Implementation: same `PlayerScreen` composable, two routes pointing to it.

- New route `"player/preview/{guid}"` registered alongside `"player"`. Both
  drive `PlayerScreen`; the preview route passes a non-null `previewGuid`
  arg.
- `previewGuid` only activates preview mode when it doesn't match the
  currently-playing episode. Previewing what's already live just shows the
  live state — no surprise.
- Preview-mode adjustments inside `PlayerScreen`:
  - Title bar: "Preview" text instead of the GraphicEq glyph.
  - Artwork / title / artist / scrubber: pull from a `PreviewData` snapshot
    loaded once via `resolvePreviewData`. The resolver searches
    `repo.allCached()` so episodes that have never been played (no
    `episode_state` row) still display correctly; falls back to
    `episode_state` alone if no cached feed has the GUID.
  - Slider: disabled (read-only) — saved position from `episode_state` is
    shown for context.
  - Transport row collapses to a single big Play button (skip-±15/30 only
    make sense during live playback).
  - Speed chip hidden — per-podcast default-speed picker (top of the
    Episodes list) is the equivalent affordance.
  - Pending-return chip hidden.
  - "Add note" disabled (no live position to anchor against). Past notes
    still surface and remain tappable to jump-and-play.
  - Heart-cycle: ensures an `episode_state` row exists before the UPDATE,
    since previewing a never-played episode means no row yet.
- `MiniPlayer` hides on both `"player"` and `"player/preview/*"` routes —
  audio keeps playing in the background regardless; backing out reveals the
  dock again.
- Episodes list: title `Text` gets its own `.clickable` that fires before
  the card-level expand-toggle. Compose dispatches the inner clickable for
  taps within the text bounds, so the rest of the card behaves identically.

## Theme cleanup: popup backgrounds, retire DMG, default to Lowlight

Symptom: typing a note in Daylight pulled up an `AlertDialog` with a slate /
near-black background even though the rest of the app was bright white.
Same on the overflow `DropdownMenu`.

Root cause: M3 1.3+ routes dialog and dropdown backgrounds through the
`surfaceContainer*` slot ladder (`surfaceContainerLowest` /
`surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh` /
`surfaceContainerHighest`), and **none of our schemes specified those
slots**. Worse, the light-feeling themes (Daylight, Reel, Ticker) were
built with `darkColorScheme(...)`, so M3's auto-derivation produced
dark-mode container shades on a light palette — exactly the visible bug.

Fix:
- Every scheme now explicitly sets the full `surfaceContainer*` ladder
  with values picked to match each theme's intended popup feel.
- Daylight, Reel, Ticker switched to `lightColorScheme(...)` (Cassette and
  Lowlight stay on `darkColorScheme` — they're dark themes). The factory
  still matters for the small set of slots we *don't* override.
- The "surfaceTint = Transparent" hack on Daylight is preserved as belt-
  and-suspenders against future Material elevation overlays.

The styling architecture was already consolidated — `ui/theme/ThemeSpec.kt`
holds every palette + font + accent, screens read via
`MaterialTheme.colorScheme.X`. The bug wasn't scattered styling; it was an
under-specified `ColorScheme`. (Only one screen-side hardcoded color
remains: `MostExcellentGold` in `MyListsScreen`, an intentional brand
accent that doesn't tie to a theme slot.)

Theme retirement: **DMG Handheld removed.**
- Dropped from the `LofiTheme` enum, removed `DmgScheme` from
  `ThemeSpec.kt`, removed `DmgPlaceholder` from `Artwork.kt`, removed
  `Kind.Dmg` from the kind enum.
- Migration: `KEY_THEME = "DMG"` (or the older `"GAMEBOY"`) now resolves
  to Lowlight on read, so existing users on the retired theme don't get
  stranded on a missing enum value.

Default theme: **Cassette → Lowlight.** Lowlight is the most universally
comfortable; it also doubles as the migration target for retired theme
values. Existing users with an explicit choice keep it; only fresh
installs (or someone who never opened Settings) see the new default.

## EQ persistence + rename Library → Catalog

- **EQ bands + volume boost now persist across app restart.** Previously the
  `KEY_EQ_BANDS` / `KEY_GAIN_DB` slots existed in `Settings` but nobody
  collected or wrote through them, so every launch reset the EQ to flat and
  the boost to 0 dB. Fixed in two places:
  - `PlaybackService.onCreate` now reads `eqBandsCsv` + `gainDb` (alongside
    the existing skip-silence rehydrate) and applies them to `sharedEq`
    before the player starts. Malformed CSVs fall through to flat rather
    than half-load a bad config.
  - `EqScreen` writes through to `Settings` on every band change, preset
    apply, Flat reset, and on `onValueChangeFinished` for the volume-boost
    slider (release-only — no DataStore thrash mid-drag).
- **Rename: `LibraryScreen` → `CatalogScreen`, route `"library"` → `"catalog"`.**
  Same for `LibraryViewModel` → `CatalogViewModel` and `LibraryUiState` →
  `CatalogUiState`. File renames done via `git mv` so `git log --follow`
  still tracks history. Comment + user-facing string references updated
  across `PlaybackService`, `PlayerController`, `EpisodesScreen`,
  `EpisodeSearchScreen`, `FeedVisitEntity`, `PlayerScreen`,
  `PodcastSourceEntity`, `AppDatabase`. The lone "runtime library" mention
  in `Sources.kt` is generic English (not the screen name) and stays as
  is. Historical BUILD_LOG entries reference `LibraryScreen` /
  `LibraryViewModel` — those are accurate records of what was committed at
  the time and aren't rewritten.

## Skip silence, per-podcast speed, search, mark played, clear history, auto-backup

Sweep through six items off the deeper polish list.

- **Skip silence with staged aggressiveness**: new `audio/SilenceSkippingProcessor.kt` — a custom `BaseAudioProcessor` slot wired into the audio chain after EQ. Three levels (gentle / standard / aggressive) tuned for podcast voice content; level 0 = passthrough fast-path. Default off, persisted via `Settings.skipSilenceLevel`, rehydrated on `PlaybackService.onCreate`. UI in EQ screen uses the same staged-button visual language as the EQ presets via a new `StagedLevelButton` composable. Why custom (not Media3's built-in): Media3's `SilenceSkippingAudioProcessor` takes its parameters at construction and offers no setter, so changing aggressiveness at runtime would mean rebuilding the audio sink.
- **Per-podcast default playback speed**: new `podcast_state` Room table (DB v11, migration MIGRATION_10_11), new `PodcastStateDao`, new `PodcastStateEntity(feedUrl PK, defaultSpeed Float?)`. `PlayerController.playEpisode` looks up the per-feed default and calls `setPlaybackSpeed(speedOverride ?: 1.0f)` between `prepare()` and `play()` so the user never briefly hears 1.0x audio. Explicit 1.0f fallback matters for feeds without an override — an outgoing feed's 1.5x shouldn't bleed in. UI in EpisodesScreen top bar: a `Speed` icon (tinted primary when an override exists) opens a dialog with the same six speed values as the Player chip + a "Clear override" action.
- **Episode search across cached feeds**: new `EpisodeSearchScreen` reachable from the Library overflow. Live search across all cached episode titles — searches in-memory only, so typing is instantaneous. Match highlighting via `withStyle`/`SpanStyle`. Capped at 200 results so a wildcard-ish search doesn't render hundreds of cards. `PodcastRepository` gained a small `allCached()` snapshot method.
- **Mark as played / unplayed**: per-row 3-dot overflow on `EpisodeRow` (in addition to the existing visible action buttons — the visible row stays focused on high-frequency actions). Mark-played pins position to duration so the `isPlayed` check returns true and stamps `lastPlayedMillis` so the auto-archive sweep can pick the row up. Mark-unplayed zeroes position.
- **Clear playback history**: new "Data" section in Settings with a "Clear" button + confirm dialog. Shows the live checkpoint count so the user knows what they're about to erase. Position + favorites preserved; only the checkpoints (jumps / session-ends / promotions) get wiped. Added `playbackCheckpointDao.clear()` and `count()`.
- **Auto-backup (single retained file)**: new `BackupWorker` (`CoroutineWorker` via WorkManager). Writes `lofipod-backup-latest.json` to a user-picked SAF tree, overwriting on every run — single retained file by design (the manual Export in Metrics still covers dated copies). Settings UI under "Data": folder picker (persistable URI permission) + interval chips (Off / 6h / 12h / Daily / Weekly) + "Last backup" status + "Back up now" one-shot. `LofiPodApp.onCreate` re-arms the worker on every launch with the persisted interval. `Backup` schema bumped v6 → v7 to round-trip the new `podcast_state` table.

## History grouping/filter, auto-archive setting, draggable mini-player scrubber

- **History screen**: rows now bucket under day headers (Today / Yesterday / "Mon, Apr 28"), and a row of filter chips at top scopes by reason (All / Promotions / Jumps / Sessions). Each chip carries its own count, and the screen distinguishes "no checkpoints exist yet" from "no checkpoints in this category" with different empty-states. Adjacent-row reason icon (heart / undo / stop) gives a glanceable cue without reading the label.
- **Auto-archive horizon as a setting**: the previously hardcoded 3-day window in `EpisodesScreen.AUTO_ARCHIVE_MS` is now `Settings.autoArchiveDays` (DataStore int, default 3). Surfaced in Settings → Playback as a chip row (Off / 1 / 3 / 7 / 30 days). 0 = sweep skipped entirely. The `LaunchedEffect` in `EpisodesScreen` keys on the value so changing it re-runs the sweep without leaving the screen. Only finished episodes are eligible regardless of the setting — in-progress episodes never auto-archive.
- **Mini-player draggable scrubber**: replaced the read-only `LinearProgressIndicator` with a real `Slider`. Local drag state holds the thumb under the user's finger while the live position-poll keeps updating in the background; release commits a single `seekTo`. Position label flips to the dragged value during drag so the user has a numeric preview. Slider's intrinsic ~48 dp touch target adds some vertical real estate to the mini-player but is the right call — seek-anywhere from anywhere in the app is muscle-memory worth paying for.

## Bluetooth audio + most-excellent-as-checkpoint + Player/Settings/Library polish

Bundle of small refinements; nothing destructive.

- **Bluetooth-friendly audio attributes**: `PlaybackService` now flags media as `CONTENT_TYPE_MUSIC` instead of `CONTENT_TYPE_SPEECH`. Some Android device HALs apply voice-oriented post-processing or codec selection for SPEECH content over A2DP, which can produce subtle BT-only choppiness. Podcasts are nominally voice but sound more like music to the BT stack — `MUSIC` keeps full A2DP routing on every HAL we've seen.
- **"Promoted to most-excellent" checkpoint**: cycling an episode's heart up to tier 2 (most-excellent) now drops a `PlaybackCheckpointEntity` with `reason = "promoted_to_most_excellent"`. Position is the live player position when the promoted episode is the one currently playing; otherwise the saved position from `episode_state` (or 0 if the row doesn't exist yet — fine, history-tap then plays from the start). New constant on `PlayerController` + new `recordMostExcellentPromotion(guid)` method, wired into both heart-cycle call sites (`PlayerScreen` top-bar heart, `EpisodesScreen` row heart). `HistoryScreen.reasonLabel` learns "Promoted to most-excellent". A snackbar in both screens confirms the promotion so the user knows something extra happened beyond the heart filling in.
- **Player speed chip**: new `AssistChip` under the transport row that shows the current playback speed and opens a popover with the common speeds (0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0). Active speed marked with a check. Full continuous slider still lives in EQ for unusual speeds — this covers the 90% case without leaving the player.
- **Settings text-scale slider**: now uses local drag state and only commits on release (`onValueChangeFinished`), so the whole-app fontScale doesn't thrash on every drag tick. Also wires up the live preview line the doc-comment had been promising — a sample sentence rendered at the previewed scale, directly under the slider.
- **Library top bar declutter**: History moved out of the top bar and into the overflow menu (it's still one tap from the Player). Library top-bar actions are now Now-playing, Notes, My-lists, Settings, Overflow — five icons instead of six.

## Stable signing key for sideload updates

> **Hardened 2026-05-02**: the keystore password is no longer baked into the
> repo. It now lives in the `LOFIPOD_KEYSTORE_PASSWORD` env var (CI injects
> it from a GitHub Actions secret of the same name; the user keeps a copy
> in their password manager). The keystore file `app/lofipod-dev.jks` is
> still committed — that's how every machine gets the same cert — but
> without the password, the file is useless to anyone who clones the repo.
>
> **Bootstrap (one time)**: GitHub → Actions → "Bootstrap signing keystore"
> → Run workflow. The job:
>   1. picks the password — uses an existing `LOFIPOD_KEYSTORE_PASSWORD`
>      repo secret if you've pre-set one (so you can pick a memorable
>      phrase yourself), otherwise generates a strong random one,
>   2. runs `app/generate-keystore.sh` with it (CI runners have JDK
>      pre-installed, so `keytool` works), and
>   3. uploads two separate artifacts: `lofipod-dev-keystore`
>      (the .jks file, 7-day retention) and `lofipod-dev-keystore-password`
>      (a text file with the password if generated + setup instructions
>      either way, 1-day retention so it doesn't sit around).
>
> **Then, in this order**:
>   1. Download the password artifact, save the password to a password
>      manager.
>   2. Add it as a repo secret named `LOFIPOD_KEYSTORE_PASSWORD` (GitHub →
>      Settings → Secrets and variables → Actions → New repository secret).
>   3. Download the keystore artifact, drop `lofipod-dev.jks` into `app/`,
>      `git add app/lofipod-dev.jks && git commit && git push`.
>
> **One last forced uninstall** still applies: the currently-installed APK
> on the device is signed with whatever per-runner keystore CI was using
> before. Uninstall once after the first build with the committed keystore +
> secret; from then on every sideload installs in place and the Room DB +
> DataStore + downloads are preserved.
>
> **What happens without the secret**: the gradle config detects a missing
> `LOFIPOD_KEYSTORE_PASSWORD` and falls back to AGP's default debug signing
> — so forks, accidental missing-secret runs, and quick local-compile
> sanity checks still build cleanly. They just won't be installable in
> place over a stably-signed APK.
>
> **Original local-keytool path** (still works if you ever install Android
> Studio):
>   `export LOFIPOD_KEYSTORE_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"`
>   `cd app && bash generate-keystore.sh`
>   then save the env var to your password manager + repo secret as above.

Adds a repo-tracked signing config so re-installing a new build over an
existing install **preserves your data** instead of forcing an uninstall.

**Why this fixes "have to uninstall to update"**: Android's package manager
refuses to update an installed APK with a different signing certificate.
Without an explicit `signingConfig`, every machine signs builds with its own
auto-generated `~/.android/debug.keystore`, so every new APK was a "different
app" from Android's perspective. (Room migrations + DataStore both already
preserve data across version updates — uninstalling is what wipes it.)

**One-time setup** (do this once, then forget it exists):

```bash
cd app
bash generate-keystore.sh
git add lofipod-dev.jks
git commit -m "Add stable sideload signing key"
```

`keytool` ships with the JDK Android Studio bundles — easiest place to run
the script is Android Studio's built-in **Terminal** tab.

**One last forced uninstall**: the currently-installed APK on your device is
signed with the old per-machine debug keystore. The first build with the
new repo-tracked keystore will get rejected as a different signer. Uninstall
once after generating the keystore; from then on every `Run` / sideload
just updates in place and your DB / preferences / downloads are preserved.

`versionCode` bumped 1 → 2 and `versionName` 0.1.0 → 0.2.0. Future builds
should bump `versionCode` each time so the package manager recognizes them
as real updates.

## Harsh-kill EQ preset + scroll-to-bands on preset tap

- New **Harsh-kill** preset (3 levels) — cuts both sub-bass rumble *and* upper-mid harshness in one shot. L2 is the user-tuned target curve (`-12, -9, -5, -3, -6, -2, -2, -2, -5, -3` from 31 Hz to 16 kHz); L1 is gentler, L3 leans harder into the cuts (clipped at the −12 dB headroom).
- Tapping any preset (including Flat) now animates the EQ scroll position so the Graphic EQ band sliders come into view. Previously the bands sat below the fold on most phone heights, so the user got no visual confirmation that the preset actually moved them. The "Graphic EQ" header captures its y-offset via `onGloballyPositioned`; the preset cycle launches an `animateScrollTo`.
- Preset row converted from `Row(weight = 1f)` to `FlowRow(min width = 96 dp)` so the now-six buttons (Flat + 5 named presets) wrap to a second line on narrow screens instead of clipping labels like "Harsh-kill" / "Boom-kill".

## New-episodes badge on Library

- New `feed_visit` table (DB v10): `feedUrl PK, lastVisitedAt`. Stamped to NOW when the user opens that feed's `EpisodesScreen` and again when an episode from that feed starts playing — both are signals that the user is current with the channel.
- Library row shows a primary-tinted "N new" pill under the episode count when any episode's `pubDateMillis` is strictly after the feed's `lastVisitedAt`. Pill disappears as soon as the user opens the feed (or plays from it from anywhere).
- First-visit handling: when Library finds a podcast with no `feed_visit` row, it seeds the row to NOW silently. Without this, a freshly-installed app would show every previously-released episode as "new", which would be loud and useless. The first time you actually see a badge is when something *truly new* arrives after install.

## Archive flow + played-row gray-out + download progress polish

**Archive (DB v9)**
- New `episode_state.archivedAt` column (epoch ms; 0 = not archived). Auto-archive sweep runs every time `EpisodesScreen` opens: any episode that's been played to within 5 s of the end and last touched more than 3 days ago is marked archived in a single bulk update. Archived episodes also have their downloads cancelled in the same pass — keeps the download cache from filling up with content the user has clearly moved on from.
- Per-row Archive / Unarchive button (archive box icon, primary-tinted when archived) on every episode row. Manual archive also auto-removes the download.
- Top-bar action on `EpisodesScreen` toggles "show archived" (Archive ↔ Unarchive icon, with a count in the content description). Default is hidden — keeps the list focused on what's actually queued up to listen to.
- **My Lists Excellent / Most-excellent tabs intentionally ignore archive state** — favorited content stays visible regardless, so archiving doesn't hide things you said you liked.
- Backup format carries `archivedAt` (omits the field on rows where it's 0 to keep file size down). Imports default to 0 when missing.

**Played-row gray-out**
- Episode rows where `positionMs >= durationMs - 5_000` (and a known duration) now render with a softer surface (`surface` instead of `surfaceVariant`), 0.55-alpha title + meta + description, a small `CheckCircle` icon next to the title, and strike-through on the title text. The Play button label flips to "Replay". The "currently playing" tint always wins, so the active row stays prominent regardless of played state.

**Download progress**
- `DownloadButton` now renders a percent label (`12%`, `…`) next to the spinner during the active download states. On fast connections the spinner used to teleport from "Download" to "DownloadDone" without ever showing intermediate progress; the percent text gives the user something to read.
- Tapping "Download" surfaces a "Download started" snackbar so the action is visibly acknowledged immediately, even on connections fast enough that the spinner state is brief.

## Plain themes: Daylight + Lowlight

Two new practical themes in addition to the four directional ones. Unlike the directional themes (which are stylized — pixel fonts, monospace, decorative palettes), these are about readability:

- **Daylight** — pure white background, near-black text, crisp blue accent. Tuned for outdoor and direct-sunlight reading. System default sans for both display and body.
- **Lowlight** — near-black charcoal background (not pure #000 — avoids OLED smear), warm off-white text, desaturated amber accent. Low-blue-light palette for nighttime use. Same default sans.

Old DataStore values still migrate (`TWILIGHT/FOREST/CORAL` → Cassette, `GAMEBOY` → DMG); the new themes appear in the Settings picker alongside the directional ones.

## Queue, My Lists, in-Player tabs, heart tiers, multi-level EQ, buffering fix

A meaty round of player + library work, organized by area.

**Player screen redesign**
- Bottom of the Player is now a tabbed container: **Notes** (inline log with Add button + per-entry jump/delete) and **Details** (podcast title/author, episode title, duration, full description, per-episode EQ override switch). New tabs slot in by extending the `tabs` list in `BottomTabs`.
- Artwork bumped to 260 dp with a 3 dp `outline`-tinted border so it visually anchors against the chassis on every theme. Transport buttons rebalanced (88 dp main, 64 dp side) so the whole page fits with the new tab strip.
- The "Return to X:XX" resume chip auto-flips to "Listened to X:XX" once the user catches up to the saved position organically; auto-dismisses 5 s later.

**Queue + auto-play next**
- New `queue_entry` Room table (DB v6) with positions kept sortable via dense steps so insert-at-front and reorder are O(1). Surfaced on PlayerController: `enqueue`, `enqueueNext`, `removeFromQueue`, `clearQueue`, `reorderQueue`.
- Episode rows in the per-podcast list have a new "Add to queue / Remove from queue" toggle (`PlaylistAdd` / `PlaylistAddCheck` icons).
- ExoPlayer's `Player.STATE_ENDED` now removes the just-finished episode from the queue and plays the lowest-position remaining entry. Falls back to a queue-row snapshot when the feed isn't in the in-memory cache, so post-cold-start queue play still works.

**My Lists consolidation**
- `FavoritesScreen` deleted; replaced with `MyListsScreen` — same shape, but tabs are now **Queue · Most-excellent · Excellent · Downloaded**. Top-bar icon switched from `Star` to `FormatListBulleted` (the bullet-list glyph).
- The Queue tab supports up/down reorder per row + remove. Tap a row to play immediately.

**Heart tiers replace 5-star ratings (DB v7)**
- Schema migration drops `rating` (0..5) and `isFavorite` (bool); replaces them with a single `favoriteTier` (0 = none, 1 = Excellent, 2 = Most-excellent). Backfill: rating == 5 → 2; rating >= 4 OR isFavorite → 1; else 0. Migration recreates the table since SQLite can't drop columns directly.
- `EpisodeStateDao.observeFavorites/observeRated` collapsed into `observeAtTier(tier)` and `observeAllHearted()`.
- UI: `StarRow` replaced everywhere with `HeartTierButton` — single icon that cycles 0 → 1 → 2 → 0; tier 2 shows a tiny second pip beside the main heart so the distinction is glanceable.
- Metrics screen + backup file format updated. Backup schema bumped to v6 (writes `favoriteTier`); v5 imports auto-convert old `rating`/`isFavorite` to the new tier.

**Multi-level EQ presets + Boom-kill**
- New **Boom-kill** preset (2 levels): rolls off low-end mud for male-voice podcasts where bass overshadows vocal qualities. L1 gentle, L2 aggressive.
- All non-Flat presets are now multi-level: Voice (2), Bass (3), Bright (2), Boom-kill (2). Tapping the same preset cycles `0 → 1 → … → max → 0`. Tapping a different preset switches and starts at level 1. Manual band edits drop you off the preset rail.
- Preset buttons render the level visually: their background is split into N evenly-sized vertical slices, the leftmost active-level slices fill with `primary`, the rest sit on `surfaceVariant`. When a different preset is active, this preset's slices render dimmed (gray) so it reads "only one preset can be lit at a time" without needing a separate disabled state.

**Audio: choppy-playback fix**
- `EqAudioProcessor` had no fast-path: even with the FLAT preset (all bands at 0 dB) it ran a per-sample biquad chain in pure Kotlin on the audio thread — the cause of choppy playback even on downloaded files. Added an effective-passthrough check: when global gain is 0 dB AND every band is 0 dB, skip the processing loop entirely and copy the input buffer to the output. The default user pays zero per-sample cost.
- Tuned `DefaultLoadControl` for podcast streaming: 60 s min / 180 s max buffer, 2 s start, 4 s after-rebuffer, `prioritizeTimeOverSizeThresholds` enabled. Defaults are tuned for video and were under-buffering for long-form audio.

**Per-episode EQ override (DB v8)**
- Added `episode_state.eqDisabled` boolean column. When set, EQ is forced off for that episode regardless of global enable. Toggle lives in the Player → Details tab.
- `PlayerController.applyEqOverrideFor(guid)` re-evaluates and writes through to `PlaybackService.sharedEq.setEnabled` on item transitions and after the user toggles. Per-podcast EQ profiles (the larger half of the original ask) is the next step — currently EQ is global except for these per-episode overrides.

**Notes export option**
- Backup export now opens an "Include notes" choice dialog before the file picker. The exported filename gets a `-no-notes` suffix when notes are omitted, and the snackbar reports which mode was used.

**Known follow-ups**
- The Alpha and Omega Ministries (SermonAudio) feed caps at 100 items server-side regardless of the URL parameters we tried. Lifting that cap will require either using SermonAudio's REST API (api.sermonaudio.com) for older items or finding the documented pagination parameter — neither was confirmed in this round.

## Theme directions: B/D/E/F selectable in Settings

- **Theme model** rebuilt around the design-spec "directions" (`LofiPod Design/design theme specs/specs/`). The old four palette-only schemes (Twilight/Forest/Coral/Game Boy) collapsed into the **Cassette** direction (B, the original look) plus three new directions ported from the specs: **Reel-to-Reel** (D, cream + brass + oxblood, mono type), **DMG Handheld** (E, olive LCD + magenta accent, pixel display font), **Ticker Tape** (F, newsroom paper + courier + spot red).
- New `LofiThemeSpec` (in `ui/theme/ThemeSpec.kt`) carries each direction's Material `ColorScheme`, display + body `FontFamily`, accent color, placeholder fill/ink, and a `Kind` enum (`Cassette` / `Reel` / `Dmg` / `Ticker`) for decorative-chrome dispatch. Exposed via `LocalLofiThemeSpec` CompositionLocal and a `lofiTheme` accessor for screens.
- `LofiPodTheme` now wires the spec into Material via a per-direction `Typography` (display roles use the spec's display font, body keeps Default sans for legibility — pixel/mono at body sizes is unreadable).
- **Settings → Theme** redrawn: each direction renders as a card with a 4-stripe palette swatch (background / surface / primary / secondary, pulled from its own `specFor`), name, tagline, and a check mark when active. Old preference keys (`TWILIGHT/FOREST/CORAL/GAMEBOY`) migrate to `CASSETTE`/`DMG` so existing installs keep working.
- **Real artwork wins.** New `ThemedArtwork(artworkUrl, size)` is the single entry point for podcast/episode art across Library, Episodes, Player, Favorites, and the mini-player. Uses Coil `AsyncImage` whenever a URL exists; only falls back to a per-direction placeholder (twin tape spools / spoke-3 reel / 8×8 pixel cassette sprite / `[ EP ]` ticker stamp) when there is no artwork URL at all. The placeholders scale with the parent size, so the 280 dp Player hero looks the same as the 48 dp favorites row.
- **Wordmark font** in the Library top-bar now reads from `lofiTheme.displayFont` instead of hard-coded `PressStart2P` — Cassette and DMG keep the pixel wordmark, Reel and Ticker switch to monospace.

## Tap-to-expand episode rows

- Tapping anywhere on an episode card (outside the inner buttons, which consume their own taps) toggles `expanded`. When expanded, the title is no longer truncated and the full description is shown instead of the 3-line preview. Inner buttons (Play / Download / Share / Favorite / star row) continue to behave as before — playback only starts when you tap Play.
- Right-side chevron flips between `ExpandMore` and `ExpandLess` as a visual affordance.

## Settings: Fonts attribution

- Added a tiny "Fonts" section at the bottom of Settings crediting Press Start 2P (Cody Boisclair) under the SIL Open Font License 1.1, with a pointer to the bundled `assets/PressStart2P-OFL.txt`.

## Visual overhaul: themes, pixel font, fatter mini-player, artwork fixes

- **Artwork** now resolves for the three feeds it had been missing on:
  - **ccmodesto**: host blocks non-browser User-Agents with HTTP 406. Set a Mozilla-style UA on the feed-fetch OkHttp client via interceptor — XML now reaches the parser. (PodcastRepository.BROWSER_UA)
  - **Castos / Anchor**: artwork URLs are present in the parsed feed but Coil's default OkHttp UA was being rejected by the CDN. Configured a global Coil ImageLoader using the same browser UA so artwork downloads succeed.
  - **Defensive fallback** in RssParser: if channel-level artwork is missing for any reason, fall back to the first episode's artwork.
- **Press Start 2P** font (Google Fonts, OFL — license at `assets/PressStart2P-OFL.txt`) bundled and applied to the "LofiPod" wordmark in the Library top bar at 14 sp.
- **Theme picker** with 4 schemes: Lofi Twilight (current default), Forest Floor (deep green + sage), Coral Reef (deep sea + coral), Game Boy (DMG green palette riff). Persisted in DataStore (`Settings.theme`). System status bar / nav bar follow the chosen background via Compose `SideEffect`.
- **Settings screen** (new top-bar action on Library): theme picker + "Pause playback while writing a note" toggle + pointer to EQ for audio settings.
- **Speed slider** restored on the EQ screen (was removed from PlayerScreen for accidental-touch reasons; now lives where it can't be brushed).
- **Library top bar** restructured: Now-playing (visible when episode loaded) · Notes · Favorites · Settings · 3-dot overflow (Metrics, EQ & speed, Refresh feeds). All icons bumped to 28 dp with 4 dp spacing between actions.
- **Now-playing on episode list**: rows tint to `primaryContainer` when their episode is the loaded one, with a small equalizer-icon badge next to the title. Play button on the row reflects state — "Play" / "Resume" / "Pause" with matching icon — and toggling it on the current episode pauses/resumes without leaving the screen.
- **Mini-player redesigned** — roughly 2× the previous height. Now has artwork (56 dp), title, artist, position/duration timecode, a 3 dp progress bar, and skip-back / play-pause / skip-forward buttons. Container colored with `primaryContainer` so it visually separates from the surrounding episode/podcast cards (which use `surfaceVariant`).
- **PlayerScreen** time text bumped from `bodySmall` to `bodyLarge`. Top-bar icons bumped to 28 dp.
- **Back-arrow icons** bumped to 28 dp across Episodes / Notes / NotesBrowser / Metrics / Favorites / History / Settings / EQ.
- **Activity survives config changes** (orientation, screen size, keyboard, ui mode) via `android:configChanges` on `MainActivity` — fixes mini-player flicker when the system would otherwise recreate.

## Playback checkpoints: Return chip + global history

- **Schema v5** (additive migration). New `playback_checkpoint` table: `id`, `guid`, `positionMs`, `recordedAt` (UTC ms), `reason`. Globally capped at 200 rows; oldest evicted on every insert.
- **Triggers** (per design call):
  - `jump_from`: any time the player jumps to a different position via `PlayerController.jumpToPosition` (notes browser jump, history jump, etc.). Captures the FROM position.
  - `session_end`: when `playEpisode` switches to a different episode while one was loaded, OR when resuming an episode whose `lastPlayedMillis` is more than 30 min stale. Captures the previous `(positionMs, lastPlayedMillis)`.
  - Manual scrubs do NOT record a checkpoint (would noise up the history).
- **`PlayerController.pendingReturn`** StateFlow: set to the FROM position whenever `jumpToPosition` fires; cleared on next jump, on user action (`consumePendingReturn` or `dismissPendingReturn`), or on `release()`.
- **PlayerScreen Return chip** (Material3 AssistChip with an Undo icon): visible only when `pendingReturn != null` and matches the currently-loaded episode. Tap = jump back. Trailing X = dismiss.
- **History icon** in PlayerScreen top bar opens a new **`HistoryScreen`**. Full screen (not bottom sheet) so each row can show podcast title + episode title + UTC citation + reason ("Before a note jump" / "End of a listening session"). Tap card or play-circle = jump and pop back to player. Trash = delete the checkpoint.
- **Backup format bumped to schema 5**: adds `playbackCheckpoints` array. Schema-4 imports get an empty checkpoints set. Import snackbar reports counts for episodes, notes, AND checkpoints.

## Touch targets + emoji audit

- Small IconButtons (`28 dp` containers I'd shipped on Notes / NotesBrowser cards) bumped to `40 dp` with appropriately sized icons (20–22 dp). StarRow on EpisodesScreen bumped from 28 → 36 dp (still compact enough to fit five in a row).
- Audited all source files (`*.kt`, `*.xml`) for emoji glyphs — none present. Established as a standing rule.

## Notes: timestamped entries, jump-to-position, browser, search

- **Schema v4** (additive migration). Replaces the unused single-row `episode_note` table with `episode_note_entry`: composite PK on `(guid, createdAt)`, plus `playbackPosMs` and `text`. Each note now records the wall-clock UTC moment it was logged AND the playback position at that moment. Multiple entries per episode accumulate like a journal.
- **`NotesScreen`** (per-episode): list of entries with citation header `2026-05-01 14:23 UTC · 00:14:23`, plus inline edit + delete. "Add" action top-right. Toggle at top: "Pause playback while writing a note" (default on, persisted in DataStore). On open: auto-pauses if playing; on save/cancel: resumes if it had been paused for the dialog.
- **Jump-to-position** button on every note card. If the note's episode is currently loaded, just seeks to the captured position. Otherwise looks up the episode in Room, builds a MediaItem, and starts playback at that position (added `forcedStartMs` parameter to `PlayerController.playEpisode`).
- **`NotesBrowserScreen`** (global): default view loads `max(25, count-within-2-weeks)` most-recent notes, paginates +50 as you scroll near the bottom. Search icon flips the top bar to a live search field that LIKE-queries note text across all episodes. Tapping a card opens that episode's NotesScreen; the play button on the card jumps directly to the position.
- **Top-bar wiring**: Library gets a Notes browser button (left of Favorites). PlayerScreen gets a Notes button that takes you to the current episode's NotesScreen.
- **Backup format bumped to schema 4**: `notes` legacy key replaced by `noteEntries` (multi-entry shape). Schema-3 backups containing a `notes` array still import — each legacy single-text becomes one entry with the original `updatedAt` as `createdAt` and `playbackPosMs` of 0.

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
