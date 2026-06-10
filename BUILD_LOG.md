# Build Log

Running notes on what's changed and why. Newest at top.

## v0.10.20 — Tune-up pass: tone filters, shared-note deep links, Kabod parity (2026-06-09)

Broad pass across the four asks: Kabod/RSS feature parity, richer audio
tuning, shareable notes, and a round of bug fixes.

### Tone filters (FabFilter-lite corrective stage)

New zero-latency IIR stage in `EqAudioProcessor`, upstream of the EQ in
**every** phase mode (it runs as min-phase biquads outside the FIR engine,
which is exactly how zero-latency mode works in desktop parametrics):

  - **Low cut** — 12 dB/oct Butterworth high-pass at 40/60/80/120 Hz
    (rumble, plosive thumps).
  - **High cut** — 12 dB/oct low-pass at 8/10/12 kHz (tape hiss on the
    older sermon archives).
  - **Tilt** — ±6 dB complementary shelf pair pivoting at 700 Hz; one
    slider from darker to brighter.

Biquad grew `setHighpass` / `setLowpass` / `setLowShelf` / `setHighShelf`
(RBJ cookbook, same Nyquist guards as `setPeaking`, so 16 kHz-mono Kabod
sources stay well-behaved). Global like the DC blocker; persisted in
DataStore; rehydrated by PlaybackService; included in the diagnostics
screen's "Reset audio to defaults"; UI in Audio Fine-tuning above Phase
mode. Off = bit-identical passthrough (folded into the
isPassthroughEffective check).

### Shared-note deep links

Share icon on every note card (per-episode Notes screen, Player Notes tab,
global notes browser). Shares the note text + episode title + position +
a `lofipod://note?feed=…&guid=…&t=…` link. On a receiving LofiPod:

  - Direct link tap (ACTION_VIEW, new `lofipod` scheme intent-filter), or
  - share the message INTO LofiPod (ACTION_SEND text/plain — the URI is
    extracted from the surrounding prose, for messengers that don't
    linkify custom schemes).

Resolution: in-memory cache → disk hydration → kabod asset loader → live
one-off feed fetch. Plays the episode at the note's position and routes to
the Player. Misses surface a toast instead of failing silently.

### Kabod / RSS parity

  - **Autoplay advances by part order.** `advanceToNextInQueue` now
    consults `episode_kabod.partNumber` when the finished episode belongs
    to a pack: next unplayed part wins, end-of-pack stops cleanly. The
    pubDate walk was wrong for multi-preacher packs where dates aren't
    monotonic with the passage order — this was the biggest "packs play
    differently" gap.
  - **Part + scripture on episode rows.** "Part N · <passage>" meta line
    in EpisodesScreen for pack episodes (was buried in the Player Details
    tab).
  - **`enclosure length` parsed** by KabodPackParser (RSS parser already
    did) so future packs can ship sizes without the HTTP probe.
  - **EpisodesScreen survives cold entry.** Was a one-shot cache read that
    rendered a permanent "Feed not loaded." if the screen was reached
    before the catalog hydrated (deep links, process-death restore). Now
    awaits disk hydration and falls back to the kabod asset loader.

### Bug fixes

  - **Canon-order autoplay never armed the confirmation window** —
    `tryAdvanceToNextInCanon` didn't set `lastPlayWasAutoplay`, so canon
    advances skipped the 3:10 beep/auto-pause guard AND the delayed
    auto-download path. Both now engage.
  - **Blank artist line after retry / note-jump / canon advance** — those
    reconstruct-from-state paths passed `podcastTitle = ""`. New
    `podcastTitleFor(feedUrl)` resolves from the live cache, then the
    hardcoded sources list.
  - **Cold-start restore ignored per-podcast default speed** — resuming
    via the restored mini-player ran at 1.0× until manually re-set.
  - **Stale legacy global EQ rehydrate removed** from PlaybackService —
    it read the pre-v0.6.12 `eq_bands` DataStore key (which nothing
    writes anymore), so an ancient curve could audibly flash between
    service start and the first track transition.
  - **EqScreen copy** claimed "EQ + master gain are global" — wrong since
    the v0.6.12 per-podcast reshape. Rewritten.
  - SCREEN_MAP.md refreshed (CatalogScreen/catalog, missing routes, deep
    link section).

## v0.10.19 — Kabod playback fixes: cleartext HTTP whitelist + Nyquist guard (2026-05-15)

Two field-bug fixes from the v0.10.18 content drop. Both shipped together
because both surface the same way (Kabod Pack episode fails to play) on
different code paths.

### 1. William Still pack: cleartext HTTP block

**Symptom.** `monergism-still-leviticus` and `monergism-still-hosea`
episodes fail with:

```
HttpDataSourceException: java.io.IOException:
  java.util.concurrent.ExecutionException:
  java.net.UnknownHostException ...
```

The `UnknownHostException` is a misleading surface error — DNS for
`tapesfromscotland.org` resolves fine on every other client. The real
cause is Android's API 28+ default that rejects cleartext (HTTP)
network traffic unless explicitly whitelisted. Both Still packs source
their audio from `http://tapesfromscotland.org/Audio*/N.mp3`; the host's
HTTPS endpoint serves an expired certificate (verified via curl —
`SEC_E_CERT_EXPIRED`) so we can't simply rewrite the URLs.

**Fix.** New file `app/src/main/res/xml/network_security_config.xml`
declares HTTPS-only as the global default plus a single domain-config
exception for `tapesfromscotland.org` (and subdomains) marked
`cleartextTrafficPermitted="true"`. The other six kabod packs use HTTPS
already and are unaffected by the global default. Manifest's
`<application>` element gets `android:networkSecurityConfig=
"@xml/network_security_config"`.

### 2. Kabod packs only worked in PURE_IIR mode (FIR/MIN_FIR/MIXED broken)

**Symptom.** With phase mode set to anything other than PURE_IIR
(`MIN_FIR`, `LINEAR_FIR`, `MIXED`), Kabod Pack episodes failed silently
to produce audio while standard RSS-feed podcasts played fine. The user
was effectively pinned to PURE_IIR for sermon listening.

**Root cause.** Kabod packs ship a far wider audio-format range than
typical RSS podcasts:

  - `sermonaudio-chanski-leviticus` — 16 kHz mono
  - `monergism-still-*`             — 22.05 kHz mono
  - `desiringgod-piper-romans`      — 32 kHz stereo
  - `citieschurch-parnell-leviticus`— 44.1 kHz joint-stereo
  - `sermonaudio-allen-ezekiel`     — 48 kHz stereo

LofiPod's 10-band EQ has fixed centers `31, 62, 125, 250, 500, 1000,
2000, 4000, 8000, 16000` Hz. At 16 kHz source rate the Nyquist limit
is 8 kHz — so the **8 kHz band sits AT Nyquist** and the **16 kHz band
sits ABOVE Nyquist**. At 22.05 kHz the 16 kHz band is above Nyquist.
The RBJ peaking-EQ cookbook formulas degenerate above Nyquist:

  - `w0 = 2π · centerHz / sampleRate` exceeds π
  - `cos(w0)` and `sin(w0)` wrap to negative or zero
  - `alpha = sin(w0) / (2Q)` flips sign or vanishes
  - resulting biquad coefficients describe a non-physical response

**Why PURE_IIR survived but FIR broke.** At LofiPod's default FLAT
preset (`gainDb = 0` on every band) the cookbook math has a happy
coincidence: `A = 10^(0/40) = 1`, so `b·A == b/A` exactly, the
numerator and denominator polynomials cancel coefficient-for-coefficient,
and the biquad reduces to identity (1·x + 0·z⁻¹ + 0·z⁻²). The FIR
kernel synthesis (`FirEq.biquadMagSquared`) **also** returns 1.0 in
this degenerate case, so FLAT-preset users wouldn't have noticed.

But the FIR kernel synthesis evaluates the magnitude across the
entire `[0, π]` frequency axis (not just at `w0`). For any non-flat
band whose centerHz exceeds Nyquist, the cookbook coefficients produce
finite but extreme magnitude values across the audible band — those
fold into the kernel via the IFFT and propagate as silence-or-noise
through the UPC convolver. PURE_IIR's biquad cascade sees the same
ill-conditioned coefficients but its time-domain feedback path tends
to converge to a near-passthrough state when state isn't being driven
hard, so it sounded "fine" even when mathematically wrong.

**Fix.** Both `Biquad.setPeaking` (the IIR coefficient setter) and
`FirEq.biquadMagSquared` (the FIR kernel-synthesis magnitude target)
now early-return identity / unity respectively when
`centerHz · 2 >= sampleRate`. The check is a single multiplication
and one branch — immeasurable cost. Bypassed bands carry zero
information about the source's spectrum since the source has no
content above its own Nyquist limit anyway, so this is a defensive
guard with no audible side-effect on the legitimate response.

### Files touched

  - `app/src/main/res/xml/network_security_config.xml` (new)
  - `app/src/main/AndroidManifest.xml` (added `networkSecurityConfig`
    attribute on `<application>`)
  - `app/src/main/java/com/lofipod/app/audio/Biquad.kt` (Nyquist guard
    in `setPeaking`)
  - `app/src/main/java/com/lofipod/app/audio/FirEq.kt` (Nyquist guard
    in `biquadMagSquared`)

## Content — Kabod packs: 6 new packs + Romans v2 + schema v2 (2026-05-14)

Content drop, no code-path changes beyond a 6-line additive list extension
in `Sources.kt`. Catalog grows from 1 bundled Kabod Pack to 7.

### New packs (six pastors × three Old-Testament books)

  - `monergism-still-leviticus` — William Still, *Expositions on Leviticus*,
    Gilcomston South Church of Scotland (Aberdeen). 23 expositions covering
    Leviticus 1–27. Audio courtesy of Tapes from Scotland; Monergism index.
  - `monergism-still-hosea` — William Still, *Exposition of Hosea*, same
    pulpit. 11 expositions covering Hosea 1–14.
  - `sermonaudio-chanski-leviticus` — Mark Chanski, *Leviticus Series*,
    Harbor Reformed Baptist Church (Holland, MI). 37 verse-by-verse sermons,
    Aug 1994 – Dec 1995. Per-sermon scripture refs mapped from the
    chronological cadence (the SermonAudio catalog doesn't annotate
    individual passages); two banner entries dropped, "Laws Concerning
    Birth"/"Laws Concerning Leprosy" picked up under chronological numbering
    rather than the title's mis-leading `#1` suffix.
  - `sermonaudio-allen-ezekiel` — Joe W. Allen Jr., *Ezekiel*, Grace Family
    Fellowship Reformed Baptist (Russell Springs, KY). 43 English sermons,
    May 2020 – May 2021, covering Ezekiel 1–48. The source page advertises
    77; that count includes 33 ASL duplicates, filtered out via
    `language=eng` in the enclosure URL. The 43-vs-77 audit signal is
    captured in `<kabod:partTotal>` / `<kabod:advertisedTotal>` per the v2
    schema below.
  - `twojourneys-davis-leviticus` — Andy Davis, *Leviticus: God's Demand for
    Holiness in a Sin Defiled World*, First Baptist Church (Durham, NC).
    Single-session whole-book class rather than a verse-by-verse series;
    bundled as a one-item Kabod Pack so the catalog UI carries the same
    metadata richness as multi-part packs.
  - `citieschurch-parnell-leviticus` — Jonathan Parnell + 4 guests
    (Joe Rigney, Mike Schumann, David Mathis, Kenneth Ortiz), *Leviticus
    (Fall 2022)*, Cities Church (St. Paul, MN). 8 sermons across the whole
    book, 2 Oct – 20 Nov 2022. Per-item `<kabod:speaker>` overrides flag
    each guest preacher.

All six wired into `Sources.KABOD_PACKS` (additive list extension; no
behaviour change). `KabodAssetLoader.installBundled()` picks them up at
next launch (idempotent on `packId`).

### Romans pack v1 → v2

`desiringgod-piper-romans.kabod` shipped at 223 sermons in v1, but the
canonical desiringgod.org series page lists 225. The two missing:

  - **Does James Contradict Paul?** (1999-08-08, James 2:14–26) — preached
    inside the Romans series as an excursus on faith vs. works; the source
    page indexes it under Romans even though its primary text is James.
    Inserted at chronological position #45.
  - **Treasuring Christ Together: The Vision and Its Cost** (2004-12-05,
    Romans 15:14–21) — Bethlehem's multi-site / church-plant vision-cast,
    preached from Romans 15. Inserted at chronological position #178.

After insertion, all `partNumber`s renumbered 1..225 in document order.
Pack-level `<kabod:packVersion>` bumped to 2; `<kabod:contentNotes>`
records the v1→v2 delta inline.

### Kabod schema v2 (additive, parser-safe)

The KabodPackParser uses `else -> XmlUtils.skip(parser)` for unknown
namespace elements (KabodPackParser.kt:97, 206), so additive `kabod:` tags
are silently ignored by the current parser and safe to author today.

New channel-level fields, all under the same `https://lofipod.app/ns/kabod/1`
namespace (no breaking bump to `/2`):

  - `<kabod:packSchemaVersion>` — `"2"` once any v2 field is present.
  - `<kabod:packVersion>` / `<kabod:packRevisionDate>` — content revision +
    last-edit date so backups can detect drift.
  - `<kabod:church>` / `<kabod:churchLocation>` — where the series was
    preached. Surfaces context the bare `<itunes:author>` line can't.
  - `<kabod:partTotal>` / `<kabod:advertisedTotal>` — the audit pair. A
    mismatch records that the source claims more (or fewer) entries than
    the pack actually bundles. This is exactly the signal that would have
    flagged Romans v1's 223-vs-225 gap from the start.
  - `<kabod:audioHost>` — primary audio CDN/host (informational; helps
    users plan offline downloads on metered networks).
  - `<kabod:contentNotes>` — free-form maintenance notes (v1/v2 history,
    why counts diverge, dating placeholders, etc.).

The parser doesn't read these *yet* — they're authoring-time documentation
+ a forward-compatible foundation. A future parser pass could surface the
v2 fields (e.g. show `<kabod:church>` as a subtitle in the catalog, or
warn when `partTotal != advertisedTotal`) without any pack edits.

`KABOD_SCHEMA.md` (gitignored, on-disk reference) updated to document the
v2 fields, the namespace-vs-application versioning split, and an
authoring checklist.

### Why `partTotal` matters

Single-source fact: a series is verse-by-verse complete iff every chapter
of its bookOfBible has at least one item whose `<kabod:scriptureRef>`
covers it. With `<kabod:partTotal>` and `<kabod:advertisedTotal>` both
present, an offline auditor can cross-reference the actual `<item>` count
against the source's claim and flag drift before users notice. The Romans
223-vs-225 gap survived a previous build pass; the v2 audit pair is the
fence to keep it from happening again.

### Files touched

  - `app/src/main/assets/kabod/desiringgod-piper-romans.kabod` (v1 → v2,
    +2 sermons, partNumber renumbered 1..225)
  - `app/src/main/assets/kabod/monergism-still-leviticus.kabod` (new)
  - `app/src/main/assets/kabod/monergism-still-hosea.kabod` (new)
  - `app/src/main/assets/kabod/sermonaudio-chanski-leviticus.kabod` (new)
  - `app/src/main/assets/kabod/sermonaudio-allen-ezekiel.kabod` (new)
  - `app/src/main/assets/kabod/twojourneys-davis-leviticus.kabod` (new)
  - `app/src/main/assets/kabod/citieschurch-parnell-leviticus.kabod` (new)
  - `app/src/main/java/com/lofipod/app/data/Sources.kt` (+6 SourceEntry
    rows in KABOD_PACKS — purely additive list extension)
  - `KABOD_SCHEMA.md` (gitignored; v2 fields + versioning policy + author
    checklist)
  - `tools/_kabod-gen/generate_packs.py` (gitignored; one-off generator
    script that produced the pack outputs from cached source-site HTML)

## v0.10.17 — Kabod chip: real Stam font (Culmus Stam Ashkenaz CLM) (2026-05-14)

User feedback on v0.10.16: the hand-drawn Compose Canvas glyph looked
amateurish. Fair call — a single-purpose geometric reconstruction of
four letters can't match a font designed by a sofer. Swapping in a
real Stam font.

### Vendored Stam Ashkenaz CLM from Culmus

`app/src/main/res/font/stam_ashkenaz_clm.ttf` — pulled from Culmus
0.133 (https://culmus.sourceforge.io/), unmodified, ~15 KB. Designed
by Yoram Gnat 2007–2010.

License: GNU GPL v2 with the standard font embedding exception that
explicitly permits embedding unmodified fonts in any document/app
without GPL contamination. See `STAM_LICENSE.md` in the repo root for
the full attribution + license text.

The font ships authentic tagin (תגין) — the three-pronged kether
crowns on the canonical "shatnez gatz" letters (שעטנז גץ), plus the
identifying thorns / kotzin on dalet, bet, etc. Exactly the
sofer-quill aesthetic the Kabod Pack chip was trying to reach.

### Code changes

`CatalogScreen.kt`:
  - Added `stamHebrewFamily = FontFamily(Font(R.font.stam_ashkenaz_clm))`
    at module scope so the FontFamily is constructed once.
  - The kabod chip's `Text("כבוד")` now passes `fontFamily =
    stamHebrewFamily` and bumps `fontSize` from 14 → 16 sp to give the
    detail in the Stam letterforms room to read.
  - Removed v0.10.16's `KabodStamGlyph` composable + `drawKaf` /
    `drawBet` / `drawVav` / `drawDalet` / `drawTagin` helpers and
    their `Color` / `Path` / `Stroke*` / `DrawScope` / `Canvas`
    imports. ~200 lines net deletion despite gaining the font.

### Files touched

  - `app/src/main/res/font/stam_ashkenaz_clm.ttf` (new — vendored font)
  - `STAM_LICENSE.md` (new — attribution at repo root)
  - `app/src/main/java/com/lofipod/app/ui/screens/CatalogScreen.kt`

## v0.10.16 — Kabod chip: hand-drawn Stam glyph with tagin (2026-05-14)

The Kabod Pack badge previously rendered the Hebrew word כבוד via the
Android system font fallback (Noto Sans Hebrew). Clean print typography
but missing the tagin (תגין / kether crowns) that mark Stam (סת"ם)
sofer-style sacred-text writing — the visual tradition the Kabod Pack
naming gestures toward.

Android ships no Stam font, and no permissively-licensed Hebrew Stam
font is available via Google Fonts / Material's font path, so we draw
the four letters and their crowns as Compose Canvas paths. Stylised,
not authentic ksav ari — but unmistakably "this is a sacred-text word,
not chip filler."

### Drawing details

`KabodStamGlyph` composable in CatalogScreen.kt. Single Canvas, RTL
positioning (kaf rightmost, dalet leftmost), per-letter helper
functions for the four shapes:

  - **drawKaf** — open on the left, smooth bottom corner (StrokeJoin.Round)
  - **drawBet** — same C-shape as kaf but with the bottom-right kotzo
    thorn and miter joins for the sharp bet corners
  - **drawVav** — narrow single vertical stroke with small flag at top
  - **drawDalet** — Γ-shape (top + right side only) with the top-right
    kotzo thorn that distinguishes dalet from resh

`drawTagin` paints three small zigzag crowns across each letter's top
edge — vertical stem + filled triangular flag curling up-left, matching
the traditional sofer-quill ornament. Vav gets 1 crown instead of 3
since it's narrow.

Stroke weights, gap proportions, and crown sizes are all derived from
the Canvas height so the glyph scales cleanly if the chip is resized.

### Why not a font file

Considered: Culmus's Stam Ashkenaz CLM (GPL with font exception, safe
for a sideloaded app) and Stam Sefarad CLM. Both would render proper
ksav and authentic tagin. Trade-off: ~150-300 KB per font file in the
APK + a font-loading code path. For a single 4-letter glyph on one
chip in the catalog, the Canvas approach is the simpler answer.

If the Hebrew vocabulary expands beyond this chip in a future tag,
revisit the font-file path — the Canvas hand-drawing scales poorly.

### Files touched

  - `app/src/main/java/com/lofipod/app/ui/screens/CatalogScreen.kt`

## v0.10.15 — UI: Home to far-left, EpisodesScreen + NotesBrowser bulk-select (2026-05-14)

Three user-driven UI changes. No playback-pipeline changes.

### 1. PlayerScreen: Home icon moves to navigationIcon (far left)

Previously Home (one-tap-to-Catalog, added v0.10.9) sat in the actions
row between Heart and My Lists. The down-chevron back button held the
navigationIcon slot. Per user direction:

  - Home now occupies the navigationIcon slot (leftmost in the top bar).
  - The down-chevron back button is removed entirely.
  - Tap Home → Catalog. Back gesture → episodes/{currentFeed} (feed-aware).

To preserve the v0.10.12 feed-aware-back behavior without the visible
chevron, MainActivity gains a second BackHandler block scoped to the
"player" route that calls `feedAwareBackFromPlayer(nav,
playerState.currentFeedUrl)`. The existing BackHandler for
non-primary routes stays. The two `enabled` predicates are mutually
exclusive so exactly one handler fires per back press.

### 2. EpisodesScreen: speed icon removed; long-press bulk-select added

  - Per-podcast default-speed icon removed from the top bar. The
    `DefaultSpeedDialog` composable is also removed (it was the only
    consumer). Stored override in `podcast_state.defaultSpeed` is still
    respected by `PlayerController.playEpisode` if previously set —
    there's just no in-app UI to edit it now. Restore from git history
    if a future entry point is wanted.
  - Long-press any episode row → enters selection mode. Long-press
    additional rows or tap-with-selection-active → toggles each row's
    membership. Selected rows show a check glyph in the title row + a
    `tertiaryContainer` background tint.
  - Top bar transforms while selection non-empty:
    `[X clear] "N selected" | [Archive] [Download] [Mark played]`
  - Bulk actions (one-direction; no toggle):
    - **Archive** — sets `archivedAt = now` for each selected; also
      removes any extant downloads (matches the per-row archive behavior).
    - **Download** — starts a download for each selected episode that
      isn't already QUEUED/DOWNLOADING/COMPLETED. Manual user intent,
      so we use the inline-start path (no autoplay 15s delayed-fire);
      also clears any auto_download flag so the finished-TTL sweep
      doesn't auto-remove the user's explicit choice.
    - **Mark played** — pins `position = duration` so the isPlayed
      check returns true and rows fade / line-through.
  - System back gesture exits selection mode (BackHandler) rather than
    popping the screen.
  - The card's interaction model uses Compose's `combinedClickable` —
    one onClick + onLongClick path replaces the previous two-step
    expand-only behavior. Title tap still independently routes to
    preview when not in selection mode.

### 3. NotesBrowserScreen: long-press bulk-select + bulk-delete

The global notes browser previously had only a "play" affordance per
card (the play_circle_24 icon → jumpToNotePosition). Per-episode
NotesScreen and PlayerScreen's Notes tab already had play / edit /
delete per row. The global browser had no delete path at all — to
delete a note the user had to drill into the episode's NotesScreen
and find the row.

Added long-press selection + bulk-delete:

  - Long-press a card → enter selection mode.
  - Tap-with-selection-active or long-press additional cards → toggle
    membership.
  - Top bar transforms while selection non-empty:
    `[X clear] "N selected" | [Delete (red)]`
  - Tap Delete → confirmation dialog ("Delete N notes? This can't be
    undone."). Confirm → loops `EpisodeNoteEntryDao.delete(guid,
    createdAt)` for each selected key. The per-row dao has no
    multi-delete; the loop is fine for typical bulk-select counts.
  - Local `entries` list is filtered after the delete completes so the
    LazyColumn reflects the change immediately (no DAO observe path
    here — it's a snapshot list).
  - System back exits selection mode.

Selection key shape: `"guid@createdAt"` (matches the LazyColumn key
+ the composite primary key in `episode_note_entry`). Decomposed at
delete-time via `lastIndexOf('@')`.

The per-episode NotesScreen + PlayerScreen Notes tab still have their
existing per-row delete affordance and weren't touched in this pass.

### Files touched

  - `app/src/main/java/com/lofipod/app/ui/screens/PlayerScreen.kt`
  - `app/src/main/java/com/lofipod/app/ui/MainActivity.kt`
  - `app/src/main/java/com/lofipod/app/ui/screens/EpisodesScreen.kt`
  - `app/src/main/java/com/lofipod/app/ui/screens/NotesBrowserScreen.kt`

## v0.10.14 — Auto-download from autoplay: delayed-fire instead of deferred (2026-05-14)

User report: after v0.10.12 / v0.10.13 stabilised playback, the
autoplay-induced auto-download wasn't firing for screen-off sessions.

**Root cause.** v0.10.12 introduced a defer-on-screen-off branch in
playEpisode that upserted the auto_download row but skipped the inline
`app.downloadsApi.start(ep)`, relying on `fireDeferredAutoDownload` to
pick it up on next track change or STATE_ENDED. On a 30-minute episode
played screen-off, that meant the download didn't START until the
episode ENDED — the user finished listening to an episode they never
had offline. The intent of auto-download (offline for scrub / re-listen)
was defeated.

**Fix.** Replace the "defer entirely" branch with a "delay slightly"
branch. Autoplay-induced plays now schedule the download for
`AUTOPLAY_DOWNLOAD_DELAY_MS` (15s) after `playEpisode` — long enough for
the streaming socket to ramp + DSP chain to settle past the initial-
buffer cost spike, short enough that a typical podcast episode is
downloaded well before the user finishes listening. Manual user-tap
plays remain inline-start with no delay.

The scheduled job is cancel-and-replace per controller: a fast skip-next
cancels the pending fire and reassigns to the new episode. The previous
episode's `auto_download` row stays put and is picked up by
`fireDeferredAutoDownload(outgoingId)` on the transition or by
`fireDeferredAutoDownloadOrphans` on next connect.

Re-check at fire time: if the user manually started / completed / removed
the download during the 15s window, the scheduled fire is a no-op
(`auto_download_delayed_skip` instead of `auto_download_delayed_fire`).

`pendingAutoDownloadJob` + `pendingAutoDownloadGuid` are torn down in
`PlayerController.release()` so a leftover schedule from a destroyed
activity can't fire against a stale `app.downloadsApi`.

### New diagnostics events

  - `auto_download_delayed_fire` — autoplay-induced download started after the 15s settle.
  - `auto_download_delayed_skip` — scheduled fire bailed because download was already in flight / completed / manually started in the interim.

The v0.10.12 `auto_download_deferred` identifier is removed from active
code; HELP_TEXT keeps a one-line historical note so anyone reading
older logs can still decode it.

### Files touched

  - `app/src/main/java/com/lofipod/app/player/PlayerController.kt`
  - `app/src/main/java/com/lofipod/app/ui/screens/AudioDiagnosticsScreen.kt`

## v0.10.13 — Field-log-driven playback fixes (2026-05-14)

User-provided logcat (`LofiPod log 7d4b926806be.txt`) exposed three
distinct bugs the v0.10.12 diagnostics pass would have caught but
weren't directly addressed. Fixing them all here, plus codec-selector
mitigation for the kernel-layer audio HAL stalls observed in the same
log.

### Fix #1: Autoplay-confirmation timer was silently broken by its own beep

**Symptom** (log lines 619-660): at T=60s into an autoplay window, the
first beep fired, BeepPlayer.duckedBeep called `player.pause()` to
silence the podcast under the beep tone, the pause traversed
MediaController IPC to MediaSession, and our own
`AutoplayConfirmCallback.onPlayerCommandRequest` intercepted it as if
it were a remote BT/notification press — fired
`confirmAutoplayContinuation`, cancelled the timer job, which
propagated into the in-flight beep coroutine as JobCancellationException
("Piezo beep failed; skipping audio strike"). The pause was denied
(RESULT_INFO_SKIPPED), Media3's SessionResult Bundle construction failed
its internal assertion ("Ignoring malformed Bundle for SessionResult"),
and the podcast continued playing under a half-played beep.

**Net effect since the autoplay-confirmation feature shipped:** every
autoplay-induced episode was uninterruptible after the first beep at
T=60s. Beep #2 / #3 and the T=190s auto-pause never fired. Battery
drained overnight on long episode chains.

**Fix** (PlaybackService.kt): gate the autoplay-confirm intercept on
`controller.uid != Process.myUid()`. Our own MediaController's pauses
(BeepPlayer, togglePlay, the timer's own auto-pause, any future
internal path) now always pass through to default Media3 handling.
Only true remote controllers (BT, vehicle, system notification) trigger
the autoplay-confirm intercept.

Also logs `autoplay_confirm_remote` to AppDiagnostics when a remote
intercept fires, so back-end triage can see what remote controller
triggered a confirmation (uid + package name).

### Fix #2: FATAL crash on activity destroy ("Service not registered")

**Symptom** (log lines 9-25): on activity destroy after a particular
playback session, `PlayerController.release()` called
`controller?.release()`, which inside Media3's MediaControllerImplBase
called `ContextImpl.unbindService`, which threw
`IllegalArgumentException: Service not registered` — known Media3 race
on the service binding teardown path.

**Fix** (PlayerController.kt): wrap both `controller?.removeListener` and
`controller?.release()` in try/catch. We're tearing down the activity
anyway; the framework's accounting is already screwed at that point.
Log to AppDiagnostics under `controller_release_failed` so we can see
how often this still fires post-fix.

### Fix #3: AudioTrack underruns invisible to the diagnostics surface

**Symptom** (log lines 607-775, 90+ seconds of sustained underruns): the
user's reported 3-5s playback loop showed up at the kernel audio HAL
layer as repeated `AudioTrack: getTimestamp_l device stall time
corrected using current time` messages at 3-9 second intervals, plus
MP3 decoder bookkeeping confusion. None of this surfaced on our
diagnostics screen — we were only watching ExoPlayer's BUFFERING<->READY
state at the renderer layer.

**Fix** (PlaybackService.kt): added an `AnalyticsListener` to the
ExoPlayer instance with overrides for `onAudioUnderrun`,
`onAudioSinkError`, `onAudioCodecError`, and `onAudioDecoderInitialized`.
Underruns are aggregated into 30-second windows (matching arm C's window
+ wake-lock oscillation window) so a chronic storm produces one entry
per window with count + worst-elapsed-feed-gap, not one per underrun.

New event identifiers:
  - `audio_underrun_window`: N underruns in 30s, worst gap M ms, buffer K ms.
  - `audio_sink_error`: DefaultAudioSink threw.
  - `audio_codec_error`: MediaCodec threw.
  - `audio_decoder_init`: which decoder Media3 picked (lets us verify Fix #4 took effect).

### Fix #4: Software MP3 decoder preference (`c2.android.mp3.decoder` bookkeeping bugs)

**Symptom** (log lines 683-691, 708-715, 728-732, ...): repeated
`CCodecBuffers: Client returned a buffer it does not own according to
our record` + `MediaCodec: keep callback message for reclaim` +
`CCodecConfig: query failed after returning 8 values (BAD_INDEX)` from
`c2.android.mp3.decoder`. Google's Codec 2 software MP3 decoder is
known buggy on Android 13+ when ExoPlayer rapidly transitions states
(seek + speed change + resume). Co-occurs with the AudioTrack underrun
storm.

**Fix** (EqRenderersFactory.kt): override `setMediaCodecSelector` to
prefer non-`c2.android.*` software MP3 decoders. The selector partitions
available decoders into preferred (`softwareOnly && !c2.android.*`) and
fallback (everything else, preserving relative order). On Pixel devices
this typically picks `OMX.google.mp3.decoder` — the older OMX software
decoder that hasn't shown the same accounting issues. For non-MP3 codecs
we use the default selector unchanged.

If a device has no non-Codec2 MP3 decoder registered, the fallback path
still picks the Codec 2 one — better than refusing playback. The
`audio_decoder_init` AppDiagnostics event reports which decoder actually
got picked so we can confirm the override took effect.

### Files touched

  - `app/src/main/java/com/lofipod/app/player/PlaybackService.kt`
  - `app/src/main/java/com/lofipod/app/player/PlayerController.kt`
  - `app/src/main/java/com/lofipod/app/player/EqRenderersFactory.kt`
  - `app/src/main/java/com/lofipod/app/audio/BeepPlayer.kt` (comment only)
  - `app/src/main/java/com/lofipod/app/ui/screens/AudioDiagnosticsScreen.kt` (HELP_TEXT)

## v0.10.12 — Playback hardening pass (2026-05-14)

Five long-standing playback issues addressed in one pass, with diagnostics
wiring so the next regression report has hard data instead of "it loops."
Untagged so each fix can be verified together on device before the next
release tag.

### 1. Reverse download handoff (file removed → stream)

`PlayerController.observeDownloadCompletion` had only the forward direction
(stream → downloaded file when a download completes mid-playback). The
inverse — removing a download while the player is reading from `file://`
— left a dangling MediaItem pointing at a now-deleted file; the next
seek/read produced `ERROR_CODE_IO_FILE_NOT_FOUND` and the user lost
playback.

Added `triggerReverseDownloadHandoff(c, guid, httpUrl)`: when the byId
collect sees the current episode's entry vanish (or transition out of
COMPLETED) while we're on a `file://` MediaItem, reconstruct the HTTP URL
from `episode_state`, build a new MediaItem with the original metadata
(title/artist/artwork/extras preserved), `setMediaItem(item, savedPos);
prepare(); play()` if we were playing. Snackbar: "Download removed —
resuming from stream."

Also clears `handoffTriggeredForGuid` on remove so a future re-download
of the same episode re-arms the forward handoff. Both handoff directions
now log to AppDiagnostics under `handoff_forward` / `handoff_reverse`.

### 2. Feed-aware back from Player

`MainActivity` previously walked the back stack from Player using a
`naturalParent` inferred at entry time. Two failure modes:
  - Mini-player / "Now Playing" / system notification entry to Player from
    Catalog produces stack `[catalog, player]`. Back lands on Catalog.
  - Queue / autoplay-in-feed advance across feeds: stack still says
    `[catalog, episodes/{feedA}, player]` but the playing episode is now
    from feedB. Back lands on feedA — wrong feed.

Fix: PlayerController now carries the current episode's `feedUrl` through
`MediaMetadata.extras` (key `EXTRA_FEED_URL`), `pushState` surfaces it on
`PlayerState.currentFeedUrl`, and the Player route's `onBack` calls a new
`feedAwareBackFromPlayer(nav, currentFeedUrl)` which pops back to Catalog
and pushes `episodes/{currentFeed}`. Result: back from Player always
lands on the episodes screen of whatever's currently playing, regardless
of how the user got to Player.

The preview route (`player/preview/{guid}`) keeps its existing smartBack
— its back stack is always correct because it's only entered by tapping
an episode title in EpisodesScreen.

Falls back to plain smartBack when `currentFeedUrl` is null (legacy
MediaItem from a session restored against an older binary). Logged as
`back_nav_fallback` vs. `back_nav_feed_aware` in AppDiagnostics.

### 3. Sticky stall watchdog (arm C — oscillation-proof)

The screen-off-autoplay 3-5s playback loop reported by the user is the
DSP chain underrunning under thermal/clock pressure, with ExoPlayer
oscillating between BUFFERING and READY every few seconds. Neither
existing watchdog arm caught it:
  - Arm A resets `lastForwardProgressAtMs` every time `onIsPlayingChanged`
    flips back to true → never crosses the 6s threshold.
  - Arm B resets `bufferingStartedAtMs = 0` the moment state leaves
    BUFFERING → never crosses the 8s threshold within a single window.

Result: stalls log was empty even while playback was visibly stuck.

Added arm C: ring buffer of `(wallclockMs, currentPositionMs)` sampled
every poll tick (1s) while `playWhenReady=true`, REGARDLESS of isPlaying
or playbackState. After 20s of accumulated samples, compares actual
position advance to speed-adjusted wall-clock advance over the last 30s
window. Trips when ratio < 30%. Survives BUFFERING↔READY oscillation
because the ring is only cleared on `pause` / watchdog stop / seek-
backward (discontinuity guard).

Trigger uses the same `triggerStallRecovery` as arms A and B (seek back
100ms force-flush + snackbar + diagnostics breadcrumb under
`renderer_stall` with kind `sticky_Npct_over_Ms`).

### 4. Wake-lock oscillation detector

When the DSP chain underruns at 3-5s cadence, `isPlaying` flip-flops and
the wake lock acquires/releases on the same cadence. Added a corroborating
signal in `PlaybackService.acquirePlaybackWakeLock`: ring of recent
acquire timestamps, trimmed to a 30s window. When ≥5 acquires accumulate
within the window, drop a `wake_lock_oscillation` AppDiagnostics entry
(throttled to 1/min). Pairs with arm C — when both fire in the same
window, the user has clear evidence of underrun thrashing.

### 5. Deferred auto-download on screen-off autoplay

`playEpisode` was firing `app.downloadsApi.start(ep)` inline on every
play, including autoplay-induced ones. With the screen off the kernel
downclocks aggressively; opening a second HTTP socket to the same CDN
while the streaming socket is still ramping compounded with DSP under-
budget to produce the loop.

Fix: when `wasAutoplay=true` AND `PowerManager.isInteractive == false`,
upsert the `auto_download` row (so `fireDeferredAutoDownload` picks it
up at the next track change / wake) but SKIP the inline `start()` call.
Manual user-tap plays and autoplay with the screen on keep the inline
start. Logged as `auto_download_deferred` in AppDiagnostics.

### 6. Artwork fallback chain hardened

Two call sites collapsed the fallback chain into a single stored value:
  - `restoreLastEpisodeIfNeeded` only read `state.artworkUrl` (set at
    first play time; can be null or stale).
  - `playEntity` (MyLists promoted-play) only read
    `EpisodeStateEntity.artworkUrl`.

Both now refresh against `app.repo.cached(feedUrl)` — the in-memory
podcast cache hydrated on startup — and resolve as
`liveEpArt ?: livePodArt ?: storedArt` (or `livePodArt ?: liveEpArt ?:
storedArt` for podcastArt), restoring the full episode↔podcast fallback
in both directions.

### Diagnostics surface

New `AppDiagnostics.Category.PLAYBACK` with `recordPlayback(identifier,
detail)`. AudioDiagnosticsScreen renders a new "Playback events" section
(also included in the clipboard dump) showing newest-first breadcrumbs:

  - `track_change` — episode swap with from/to guids, autoplay flag, feed.
  - `handoff_forward` / `handoff_reverse` — download swap direction.
  - `auto_download_deferred` — screen-off+autoplay skip.
  - `wake_lock_oscillation` — N acquires in 30s.
  - `back_nav_feed_aware` / `back_nav_fallback` — back-from-Player target.

HELP_TEXT updated with one-line definitions for every new event kind +
the three stall-watchdog arm signatures (`no_progress_Ns`, `buffering_Ns`,
`sticky_Npct_over_Ms`).

### Files touched

  - `app/src/main/java/com/lofipod/app/diagnostics/AppDiagnostics.kt`
  - `app/src/main/java/com/lofipod/app/player/PlayerController.kt`
  - `app/src/main/java/com/lofipod/app/player/PlaybackService.kt`
  - `app/src/main/java/com/lofipod/app/ui/MainActivity.kt`
  - `app/src/main/java/com/lofipod/app/ui/screens/AudioDiagnosticsScreen.kt`

## v0.10.11 — Revert v0.10.10's icon swap; keep wording fix (2026-05-13)

I misread the user's v0.10.10 request. They were pointing out that the
SETTINGS COPY used the word "plunger" while the actual icon we ship is
a valve — i.e., the WORDING was inconsistent with the icon, not that
the icon needed changing. v0.10.10 swapped the icon (wrong fix) AND
updated the wording (correct fix).

This tag reverts the icon swap:
  - Restored `res/drawable/valve_24.xml` (Material Symbols valve glyph,
    same as v0.10.2-v0.10.9).
  - Deleted v0.10.10's custom `flush_valve_24.xml` (the dual-flush
    button design).
  - PlayerScreen reference back to `R.drawable.valve_24`.

The "plunger" → "flush-valve" wording changes from v0.10.10 are KEPT.
They were the actual fix the user asked for — the Settings copy now
matches the icon we ship. No "plunger" wording anywhere in the
codebase post-v0.10.10.

## v0.10.10 — Manual-flush icon redesigned (REVERTED in v0.10.11) (2026-05-13)

[Reverted — see v0.10.11 above. v0.10.10 incorrectly swapped the icon
when only the wording needed updating. The custom flush_valve_24.xml
from this tag is no longer in the tree.]

Original change was a custom dual-flush button vector drawable
replacing the Material Symbols `valve` glyph. Reverted because the
user's actual request was a wording correction, not an icon change.

## v0.10.9 — Stale-while-revalidate catalog UX + home-icon nav (2026-05-13)

Two user-reported polish items:

**1. Catalog appeared to hang during feed refresh.**

The Catalog's loading state was an all-or-nothing takeover: if `loading
== true` (any feed still refreshing), the entire catalog body was hidden
behind the FeedProgressList, even when cached entries were available
from disk. On a fresh launch where the disk cache had ~13 of 14 feeds
but one was slow, the user couldn't interact with any of the 13 ready
podcasts until the laggard finished (or timed out at 60s).

**Fix:** stale-while-revalidate UX. The catalog body now always renders
cached entries immediately if any exist. During an in-flight refresh,
a thin `LinearProgressIndicator` + `"Refreshing feeds X / N …"` banner
appears above the LazyColumn. Full-screen `FeedProgressList` only fires
on cold start when nothing is cached yet.

Result: subsequent launches render catalog instantly; even partial
disk-cache coverage gets the user to a usable list immediately.

**2. No quick way to jump to Catalog from the Player.**

Previously: tap back-chevron on Player → goes to Episodes → tap back
again → Catalog. Two taps.

**Fix:** new Home icon in the Player top-bar actions row (left of
MyLists). One tap pops the back stack all the way to `"catalog"`
without popping the catalog itself. Wired via
`nav.popBackStack("catalog", inclusive = false)` from MainActivity.

Icon: `res/drawable/home_24.xml` — Material Symbols `home`, fetched
from google/material-design-icons. First addition to the drawable set
since the v0.10.4 mass migration; follows the convention documented in
`ICON_PHILOSOPHY.md`.

The Player's actions row now has 5 IconButtons: Heart, Home, MyLists,
EQ, Overflow. Fits on standard 360dp+ phone widths.

## v0.10.8 — HTTP HEAD/Range probe for missing episode sizes (2026-05-13)

v0.10.7 fixed the "<1mb" display bug by hiding the size when the RSS
feed didn't supply one. v0.10.8 goes further: it actually probes the
audio URL to learn the real size when the RSS doesn't have it. Most
podcast apps don't bother; LofiPod doing this well is a small but real
differentiator.

**Strategy (cheap and reliable):**

  1. **HTTP HEAD** request to the audio URL → Content-Length header.
     ~500 bytes per probe.
  2. **HTTP GET with `Range: bytes=0-0`** as fallback when HEAD doesn't
     return Content-Length (some CDNs strip it on HEAD, some return 405
     to HEAD entirely). The `Content-Range: bytes 0-0/<total>` response
     header contains the size. ~1 KB per probe; works on essentially
     every audio CDN because Range requests are universal for
     resumable downloads.

**New file:** `EpisodeSizeProber.kt` (~180 LOC). OkHttp-backed
HEAD/Range prober. Caches results in an in-memory
`StateFlow<Map<String, Long?>>` (Long? value distinguishes
"probed and got size" from "probed but no size — don't retry").
Persists to `filesDir/episode_size_cache.json` so subsequent launches
render instantly from the cache. Concurrency-capped at 4 parallel
probes (Semaphore) so a fast scroll doesn't trigger hundreds of
simultaneous HTTP requests.

**Lazy + on-demand:** the prober is called from `LaunchedEffect` in
`EpisodeRow` and `PlayerScreen` only when a row composes AND the
RSS-supplied size is null. Idempotent — re-rendering the same row is a
no-op (the prober's in-session dedup `Set<String>` of attempted URLs
short-circuits). As probes complete, the StateFlow emits and visible
rows recompose with the resolved size.

**Bandwidth cost:** roughly 1 MB across an entire podcast library
(~1000 episodes × ~1 KB per probe), once. Then the JSON cache covers
it forever.

**Wired in:** `LofiPodApp.episodeSizes` (lazy singleton),
`EpisodesScreen.EpisodeRow` (per-row probe + display merge),
`PlayerScreen` (player-screen size readout). Both display sites read
`resolvedSize = ep.audioByteSize ?: probedSizes[ep.audioUrl]` —
RSS-supplied value wins when present, prober result is the fallback.

**For The Pour Over specifically** (the canonical Megaphone-hosted
"all length=0" offender): episodes will now show real sizes
(typically 15-30 MB for a 12-minute episode at 256 kbps) once the
prober has had a moment to run after the user opens the episode list.

## v0.10.7 — Hide misleading "<1mb" badge for podcasts that emit `length="0"` (2026-05-13)

User-reported bug: every episode in The Pour Over (and Simple Farmhouse
Life) was displaying `<1mb` in the episode-row size badge. Investigation
showed that some podcast hosting platforms emit
`<enclosure ... length="0" type="audio/mpeg"/>` for all episodes,
either because they can't pre-compute size before CDN redirects or
because their RSS generator doesn't bother filling it in.

Megaphone's feed for The Pour Over is the canonical offender; checked
directly via the live RSS XML:
```
<enclosure url="..." length="0" type="audio/mpeg"/>
```
for every `<item>` in the feed. Castos does it correctly for most
shows but presumably has shows like Simple Farmhouse Life with
similar gaps.

Our `RssParser.kt` was reading `"0".toLongOrNull()` → `0L` →
`audioByteSize = 0L`. The `EpisodeRow`'s display block then ran the
size through `formatMb(0L)` which returned `"<1mb"` (per the
`bytes / 1_048_576L == 0` branch). Result: every episode showed
"<1mb" as if it were a tiny clip.

**Fix:** `RssParser.kt` now treats `length="0"` (and any non-positive
value) as missing data, returning `null` for `audioByteSize`. The
`EpisodeRow`'s display block already uses `ep.audioByteSize?.let { ... }`,
so a null value hides the size badge entirely — cleaner than showing
misleading data.

**Self-healing cache:** `FeedDiskCache.kt` (which deserializes cached
feeds from disk) also applies the `takeIf { it > 0L }` filter, so
existing users with cached feeds containing `audioByteSize = 0` will
have those values silently treated as null on the next launch. No
force-refresh needed.

## v0.10.6 — Address compile warnings (2026-05-13)

Clean-up tag. v0.10.5 built green with four non-blocking warnings;
this addresses all four. No functional changes.

1. **`kotlinOptions { jvmTarget = "17" }` → `kotlin { compilerOptions
   { ... } }`.** The `kotlinOptions` DSL inside `android { }` is
   deprecated as of the Kotlin Gradle plugin 2.0.x. Migrated to the
   top-level `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17)
   } }` block (placed between `android { }` and `dependencies { }` in
   `app/build.gradle.kts`). Per https://kotl.in/u1r8ln.

2. **`Divider()` → `HorizontalDivider()`** at `NotesScreen.kt:102`.
   Material3 renamed `Divider` to `HorizontalDivider`; the old name is
   a deprecated alias. Mechanical replacement.

3. **`window.statusBarColor` / `window.navigationBarColor`** in
   `Theme.kt`. Deprecated in API 35+ in favor of edge-to-edge +
   `WindowInsetsControllerCompat`. LofiPod's `targetSdk` is still 34
   (we deliberately stayed at 34 in v0.9.4 — bumping targetSdk would
   opt into Android 15 runtime behavior changes that need their own
   audit). On targetSdk 34 the deprecated setters still apply colors
   correctly. Wrapped in `@Suppress("DEPRECATION")` with a comment
   pointing at the proper edge-to-edge migration for when targetSdk
   eventually bumps to 35.

4. **`MediaSession.Callback.onPlayerCommandRequest`** override in
   `PlaybackService.kt`. Media3 1.5 deprecated this signature. The
   functional behavior (intercepting `COMMAND_PLAY_PAUSE` during the
   autoplay-confirmation timer) is still called correctly by Media3's
   internal dispatch. Wrapped in `@Suppress("DEPRECATION")` with a
   comment pointing at the eventual migration to the non-deprecated
   overload.

## v0.10.5 — v0.10.4 build fixes + PFFFT NEON SIMD enabled (2026-05-13)

CI failure on v0.10.4 surfaced four build errors + an unrelated SIMD
issue I noticed in the same build log. This tag fixes all five.

**v0.10.4 build errors (4):**

1. `MainActivity.kt` — missing `painterResource` + `R` imports. My
   PowerShell import-fix script's regex anchored on
   `androidx.compose.ui.platform.LocalContext`, which MainActivity
   doesn't import. The fallback (insert after package line) didn't fire
   either. Manually added the two imports.

2. `AudiophileNotesScreen.kt` — same root cause, manual fix.

3. `NonAudiophileLofiNotesScreen.kt` — same root cause; without the
   `import com.lofipod.app.R`, Kotlin's resolver saw two candidate
   `R` classes (likely `android.R` and the implicit module R) and
   reported "None of the following candidates is applicable." Adding
   the explicit import disambiguates.

4. `HistoryScreen.kt` — the `reasonIcon` helper function returns an
   icon based on a `when` over reason strings. After the migration its
   body became `when (...) { -> painterResource(...) }`. But
   `painterResource()` is `@Composable`, and `reasonIcon` itself was
   declared as a plain (non-composable) `private fun`. Added the
   `@Composable` annotation. Both call sites of `reasonIcon` are
   inside Composable contexts (`leadingIcon = { ... }` lambdas), so
   they accept the annotated helper.

**PFFFT SIMD fix — separate but important:**

The v0.10.4 build log showed:
  - `arm64-v8a`: `building float with simd disabled !` warning from
    `pf_float.h:68`
  - `armeabi-v7a`: same warning + `unsupported CMAKE_SYSTEM_PROCESSOR
    'armv7-a'` from marton78's `target_optimizations.cmake:71`

Investigation: PFFFT requires `PFFFT_ENABLE_NEON=1` to be defined to
activate its NEON SIMD code paths (it's opt-in, not auto-detected from
the standard `__ARM_NEON` compiler macro). marton78's CMake has logic
to set it when `CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64"`, but
on Android NDK builds the processor variable can be `armv7-a` (the
architecture name) which doesn't match, AND for arm64-v8a the
detection isn't firing reliably either. Result: PFFFT compiled in
SCALAR mode, no NEON. The whole point of v0.9.6 was to get the 3-5×
NEON speedup; without it, we have all the JNI complexity for ~zero
gain over the original JTransforms.

Fix: force-define `PFFFT_ENABLE_NEON=1` on the PFFFT static-lib target
from MY CMakeLists, gated on `ANDROID_ABI` being one of the two ARM
ABIs we build for. Bypasses marton78's auto-detection entirely.

**Net of v0.10.5:** build green (assuming no further failures
surface), and the JNI PFFFT path actually runs NEON-accelerated as
intended.

## v0.10.4 — Full icon migration to Material Symbols (2026-05-13)

User-driven: after v0.10.3 documented the design philosophy for future
icons, user asked "go ahead with the full icon swap." So we did.

**Scope:** 51 unique icons across 23 .kt files migrated from
`Icons.Filled.X` / `Icons.AutoMirrored.Filled.X` to
`painterResource(R.drawable.<name>_24)`. The deprecated
`androidx.compose.material:material-icons-extended` dependency removed
from `app/build.gradle.kts`.

**Execution path:**
  1. Bulk-fetched 51 Material Symbols vector drawables via PowerShell
     Invoke-WebRequest from
     `https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<icon>/materialsymbolsoutlined/<icon>_24px.xml`.
     Saved under `app/src/main/res/drawable/<snake_case>_24.xml`.
     Three special cases: `error_outline_24.xml` from
     `error_24px.xml`, `favorite_border_24.xml` from
     `favorite_24px.xml` (default outlined), `favorite_24.xml`
     from `favorite_fill1_24px.xml` (filled variant).
  2. Post-processed each drawable: stripped
     `android:tint="?attr/colorControlNormal"` so Compose's
     `LocalContentColor` tint is the sole source. Ensured
     `android:autoMirrored="true"` on the four RTL-aware drawables
     (arrow_back, format_list_bulleted, list, playlist_add).
  3. Bulk PowerShell replace across all .kt files: longest-key-first
     literal substitution from each `Icons.X.Y` to
     `painterResource(R.drawable.snake_case_24)`. Resolved prefix
     collisions (PlaylistAddCheck before PlaylistAdd, FavoriteBorder
     before Favorite, etc.).
  4. Imports cleanup: added
     `import androidx.compose.ui.res.painterResource` and
     `import com.lofipod.app.R` to all 23 affected files; removed
     all `androidx.compose.material.icons.*` imports
     (Icons base, filled.*, filled.Specific, automirrored.filled.X).
  5. Removed `material-icons-extended` from
     `app/build.gradle.kts`.

**Verification:** Grep across all .kt files post-migration:
  - 0 references to `Icons.`
  - 0 references to `import androidx.compose.material.icons.`

**Updated docs:**
  - `ICON_PHILOSOPHY.md` reflects the completed migration + migration
    history section documenting the special cases.

**APK size impact:** material-icons-extended (the entire ~2000-icon
set) was ~25 MB compiled into the APK because R8 is OFF. The 51 vector
drawables we now ship are ~10 KB total. Net APK shrinkage in the
several-megabyte range.

**Licensing reminder:** Material Symbols are Apache-2.0 from Google.
`LICENSE-MATERIAL-SYMBOLS.txt` already shipping in
`app/src/main/assets/licenses/` (added v0.10.3). About-screen
attribution credits "Material Symbols (Apache-2.0, Google)".

## v0.10.3 — Icon design philosophy + Material Symbols license attribution (2026-05-13)

Documentation tag. No code-behavior changes.

Background: while swapping the plunger icon to a Material Symbols
valve glyph (v0.10.2), we surfaced that `material-icons-extended` is
officially deprecated and no longer published in the latest Compose
Material 3 release. A full migration of the ~50 existing icon
references is high-churn and low-benefit (the existing icons render
fine; the visual style difference is subtle). The pragmatic call: keep
the existing icons on the deprecated library for now, establish the
Material Symbols convention for all FUTURE icons, and document it.

**New file: `ICON_PHILOSOPHY.md` at repo root.** Codifies:
  - Source: Google's Material Symbols (Apache-2.0, fonts.google.com/icons
    + google/material-design-icons on GitHub)
  - Style: Outlined (matches valve_24)
  - Form: Vector drawables in `res/drawable/<snake_case_name>_24.xml`
  - Compose usage: `Icon(painter = painterResource(R.drawable.x_24),
    contentDescription = "...")`
  - When to add as Material Symbols (new icons, refreshes) vs not
    (bulk-rewriting existing icons that work fine)
  - Step-by-step fetch + integration recipe for adding a new icon
  - Eventual full-migration path once R8 minification can be re-enabled

**New file: `app/src/main/assets/licenses/LICENSE-MATERIAL-SYMBOLS.txt`.**
Apache-2.0 text bundled in the APK. Settings → About attribution now
mentions Material Symbols.

**Profitability / licensing summary** (since this was the user's
concern):
  - Apache-2.0 is the most permissive viable license for an
    icon set. Fully compatible with closed-source, commercial,
    sideloaded, or any distribution model. Zero royalties. No
    copyleft. Patent grant included. The only requirement is
    attribution (NOTICE / LICENSE file inside the APK, which we
    have).
  - PFFFT (BSD-3-Clause) and JTransforms (BSD-2-Clause), used by the
    audio chain, are equally permissive. LofiPod has zero licensing
    or profitability constraints from its current dependencies.

## v0.10.2 — Swap plunger icon to Material Symbols "valve" (2026-05-13)

v0.10.1's manual flush button used `Icons.Filled.Plumbing` from
material-icons-extended (visually a wrench, since "Valve" isn't in the
older Material Icons set that Compose's extended library uses). Swapped
to the Material Symbols valve glyph proper, bundled as a vector
drawable at `res/drawable/valve_24.xml` (path data from Google's
material-design-icons GitHub repo, Apache-2.0).

PlayerScreen renders it via `painterResource(R.drawable.valve_24)`
inside the existing Icon composable — Compose still applies its
`LocalContentColor` tint over the white fill, so theme color follows
automatically.

No behavior change. Just visual.

## v0.10.1 — Min FIR Kaiser fix + auto-flush on mode switch + manual flush plunger (2026-05-13)

Three fixes / features in response to first real-device testing of the
v0.9.0 → v0.10.0 audio rebuild:

**Bug: Min FIR ~5% of original volume.** Root cause: I was applying the
symmetric Kaiser window (β=6) to the min-phase kernel. Kaiser's value is
~1.0 at the center of the array and ~0.015 at the edges. The linear-phase
kernel has its impulse energy at the center, so Kaiser there is a no-op
(multiply by ~1). The min-phase kernel has all its energy at the START
of the array (causal, energy-front-loaded), so Kaiser at index 0 ≈ 0.015
multiplied straight against the dominant taps was a -36 dB attenuation
of the kernel. Combined with energy spread over the first few hundred
samples this surfaced as ~5% audible volume. Fix: removed the Kaiser
multiplication from synthMinPhaseKernel — the cepstrum kernel naturally
tapers to near-zero in its tail, so truncation alone is fine. Mixed
mode escaped the bug because its min-phase contribution is time-shifted
to the center where Kaiser ≈ 1.0, and the magnitudes are crossover-scaled.

**Bug fix: auto-flush on phase-mode switch.** Toggling phase mode
called chainReset() which cleared the chain's internal state — but the
AudioTrack already had 1.5-3 s of pre-switch PCM queued ahead, which
continued playing through the now-cleared chain. For Linear FIR
specifically this could surface as "0% volume at 1× speed" or
"buffering loop until a manual flush." Now EqScreen's mode-switch chips
call PlayerController.flushAudio() after the chain state change, which
issues a Media3 seek to currentPosition − 50 ms — guaranteed-real
flush (same-position seeks are sometimes deduped) with a ~50 ms audible
rewind. Acceptable cost for a mode switch.

**Feature: manual flush plunger icon (opt-in).** Settings → "Show
manual flush button on Player" (off by default). When enabled, a
plunger icon appears to the RIGHT of the Speed chip in the player.
Speed chip stays horizontally centered (via Box layout with
contentAlignment=Center for the chip, Alignment.CenterEnd for the
plunger). Tap → calls PlayerController.flushAudio() with the same
50 ms rewind. Useful when something sounds contaminated and a clean
restart is the fastest fix, without disrupting position much.

Icon: Icons.Filled.Plumbing (material-icons-extended). Closest match
to "plunger" semantically in the Material set; visually a wrench /
plumbing tool.

## v0.10.0 — Audio rebuild endpoint: content + diagnostics polish (2026-05-13)

Content/polish tag closing out the audio rebuild arc that started at
v0.9.0. No engineering changes — just documentation of what shipped.

**Audiophile Notes page** (Settings → Notes for audiophiles): full
content rewrite. New sections:
  - Signal chain diagram now shows the four-way EQ-stage dispatch (Pure
    IIR / Min FIR / Linear FIR / Mixed).
  - Float64 / Float32 boundaries — explains where the audio thread runs
    in single precision (the FIR convolution via PFFFT) and where it
    stays in double (everything else).
  - Phase modes (four-way lineup) — full descriptive section for each
    mode + "when to use each."
  - UPC: uniform partitioned convolution — algorithm description, Wefers
    + Gardner refs, why it's cheaper than monolithic OLA.
  - Min-phase kernel via real cepstrum — Mian & Nainer 1982 /
    Oppenheim-Schafer §10.5 derivation, step-by-step recipe.
  - Cross-fade on band changes — describes both biquad parallel-path
    and FIR dual-kernel crossfade (v0.9.8).
  - Latency-compensated transport — pro-DAW-style position reporting.
  - Zero-allocation audio-thread discipline — DoubleRing fix, why
    autoboxing on the audio thread is dangerous, the v0.9.0 sweep.
  - Updated CPU footprint section with PFFFT NEON timings + ADPF.
  - Updated licenses + attribution with PFFFT BSD-3 + Wefers/Mian-Nainer
    algorithmic credits.

**Plain-language Lofi Notes page** (Settings → Audio guide): phase
modes section rewritten for the 4-mode lineup. Friendly tone, no jargon.
"If you're not sure which to pick — Pure IIR is genuinely the best
default."

**Audio Diagnostics chain spec** (Settings → Audio diagnostics): new
per-mode block under the chain spec section. Surfaces the active
phase mode + (for FIR modes) kernel length, kernel synthesis method,
UPC partition count, FFT library (PFFFT arm64 NEON SIMD), and
pre-ringing characteristic. The "Mode chip" tooltip style the brief
described (long-press for tooltip) is deferred — these diagnostics
lines serve the same purpose with less UI complexity. Audio chain
latency line is also now live + mode-aware (reads
EqAudioProcessor.getChainLatencyUs() instead of the post-EQ-only
totalLatencyFrames1x baseline that was here pre-v0.10.0).

**What v0.10.0 does NOT bring:** no engineering changes. The audio
chain itself is unchanged from v0.9.8. This tag is purely the
documentation + telemetry surface that completes the rebuild arc.

**v1.0.0 status:** still reserved. The audio rebuild from v0.9.0 →
v0.10.0 is the engineering arc. v1.0.0 is the user-triggered
public-release push when the app is judged suitable for general use.

## v0.9.8 — Band-change crossfade in UpcConvolver (2026-05-13)

Brief #10 lands: zipper-free EQ slider drags in FIR modes. Adds a
parallel-path dual-kernel mix during a ~93 ms fade window after each
`setKernelTaps` call. With UPC's input-history FDL, the same FDL drives
both old and new kernel multiply-accumulates — no need for separate
input buffers; just one extra IFFT and ~3 extra MACs per channel per
block during the fade.

**How it works:**
  - `setKernelTaps` now captures the current live `kernelPartitionSpectra`
    as `prevKernelPartitionSpectra` before publishing the new one, and
    arms `fadeChunksRemaining = FADE_CHUNKS` (= 4 blocks ≈ 93 ms at
    1024 @ 44.1k).
  - `processBlock` checks the fade counter at the top of each block.
    When fading, it runs the same FDL against BOTH kernels (new in
    `accumSpec`, old in `accumSpecOld`), IFFTs both, then mixes the
    time-domain outputs via a linear α ramp:
      `out[n] = newOut[n] * α + oldOut[n] * (1 - α)`
    where α grows from `fadeIdx/FADE_CHUNKS` at the start of the chunk
    to `(fadeIdx + 1)/FADE_CHUNKS` at the end.
  - After `FADE_CHUNKS` blocks, the prev kernel reference is dropped
    and the dual-path machinery goes dormant again.

**Rapid slider drag handling:** each `setKernelTaps` captures whatever
the current live kernel is (which might itself be a mid-fade new kernel)
as the new "prev" and restarts the fade from α=0. The user always hears
a smooth ramp from the immediately-prior live state to the new state,
regardless of drag speed.

**CPU cost during fade:** one extra IFFT and one extra set of P MACs
per channel per block. With PFFFT's NEON SIMD, that's well within budget
even at 2× playback on mid-range ARM. After the fade window the cost is
identical to v0.9.7 — no permanent overhead.

**Reset behavior:** `UpcConvolver.reset()` (called from chainReset on
seek / flush / mode toggle) clears the prev kernel reference and zeros
the fade counter, so the chain comes back from a flush without an
unwanted ramp-from-pre-flush-state.

**Affects modes:** Min FIR, Linear FIR, Mixed — all three FIR modes
inherit the crossfade for free since they all go through the same
UpcConvolver. PURE_IIR has its own biquad cross-fade machinery
(separate code path).

This was the brief's last unfinished engineering item. The remaining
deferred item is #12 (off-thread DSP worker with SPSC ring) — pure
optimization that's mostly mooted by PFFFT's 3-5x speedup in v0.9.6.
Skipping for now.

## v0.9.7 — PFFFT integration fixes (preempting v0.9.6 CI failure) (2026-05-13)

Web-search-driven preemptive fix tag — v0.9.6's PFFFT integration had
three wrong assumptions about marton78/pffft's repo layout that would
have failed the CI build at CMake configure / compile time:

**1. Source-file path was wrong.** v0.9.6's CMakeLists tried to compile
`${pffft_src_SOURCE_DIR}/pffft.c` directly. marton78/pffft's source is
split across multiple .c files under `src/` (e.g., `src/pffft_common.c`,
`src/pffft_priv_impl.h`) and `pffft.c` at the repo root doesn't exist.
Switched to `FetchContent_MakeAvailable(pffft)` + linking the
`PFFFT::PFFFT` target so their CMake takes care of source assembly.

**2. Header include path was wrong.** marton78/pffft places the public
header at `include/pffft/pffft.h` (under a `pffft/` subdir for namespace
hygiene). v0.9.6's JNI bridge had `#include "pffft.h"`. Fixed to
`#include "pffft/pffft.h"`. The `PFFFT::PFFFT` target exports the
right include directory.

**3. Subproject uninstall conflict.** v1.1.0 (released Feb 2026) had
an unconditional uninstall target that conflicts when PFFFT is consumed
as a subproject. Commit a4b0359 (2026-04-22) restricted it to top-level
projects only. Pinned `GIT_TAG` to that specific commit SHA for both
reproducibility and to pick up the fix.

**Other tightenings while we're here:**
- Disabled `PFFFT_USE_TYPE_DOUBLE` (we only use single precision).
- Disabled `PFFFT_BUILD_TESTS / _BENCHMARKS / _EXAMPLES` and
  `INSTALL_PFFFT` (we're a subproject; we don't need their install rules
  or testing infrastructure).
- Set `BUILD_SHARED_LIBS=OFF` so PFFFT compiles as a static lib that
  links into our single JNI .so instead of shipping a separate
  libPFFFT.so alongside.
- Removed `GIT_SHALLOW TRUE` — shallow clones don't reach pinned commit
  SHAs reliably (only HEAD).

**Function-name verification.** Confirmed from
`include/pffft/pffft.h` that `pffft_new_setup`, `pffft_transform`,
`pffft_zconvolve_accumulate`, `pffft_aligned_malloc/free`, and
`pffft_zreorder` all use the bare `pffft_` prefix (no `pffftf_` or
`pffftd_` variants when only `PFFFT_USE_TYPE_FLOAT=ON`). v0.9.6's JNI
bridge already used these names — no change needed there.

**Risk note.** v0.9.6 + v0.9.7 are both untested first-NDK-build tags.
v0.9.7 fixes the three issues I caught preemptively via marton78/pffft
docs + source inspection. There may be more issues I missed; expect at
least one more iteration (v0.9.8+) if CI fails.

## v0.9.6 — PFFFT (NEON-SIMD FFT) replaces JTransforms on audio thread (2026-05-13)

Brief #15 lands: replaces the pure-JVM JTransforms FFT on the audio-
thread hot path with PFFFT (BSD-3-Clause, single-precision, NEON-SIMD).
~3-5× speedup on ARM at the FFT sizes we use (fftSize=2048 for UPC),
which compounds with the brand-new use of `pffft_zconvolve_accumulate`
as the multiply-accumulate inner loop. Net effect: substantially more
thermal-throttle headroom on the audio thread, especially relevant at
2× playback with screen-off-in-pocket scenarios.

**First-ever NDK build for this project.** Introduces:

- `app/src/main/cpp/CMakeLists.txt` — CMake project that fetches PFFFT
  source from `marton78/pffft` (pinned to master, will pin to a commit
  hash once the build is proven green) and compiles `pffft.c` directly
  into the shared library. NDK ABIs: `arm64-v8a` + `armeabi-v7a` (skip
  x86_64 / x86 — emulator-only for a sideloaded app).
- `app/src/main/cpp/lofipod_fft.cpp` — minimal JNI bridge exposing
  setup/destroy/transform/zconvolveAccumulate/reorder. Holds its own
  16-byte-aligned scratch buffers (allocated via `pffft_aligned_malloc`)
  so the JNI boundary doesn't need to worry about ART's primitive-array
  alignment.
- `app/src/main/java/com/lofipod/app/audio/PffftBridge.kt` — Kotlin
  wrapper. Two instances per UpcConvolver: one for the audio thread
  (transforms + zconvolveAccumulate), one for the kernel-synth worker
  thread (transforms only). Native scratch isn't thread-safe, hence
  separate bridges per thread.

**UpcConvolver: Double → Float refactor.** PFFFT is single-precision.
All internal arrays (FDL, prevInput, workspace, accumSpec, kernel
partition spectra) move to FloatArray. Hand-rolled
packedComplexMultiplyAccumulate is GONE — replaced by PFFFT's NEON-SIMD
`pffft_zconvolve_accumulate`. Net code shrinks; speed grows.

**FirEq: Double → Float at the UPC boundary.** Kernel synthesis stays
Double (worker thread, off the audio path — keeps the cepstrum log/exp
recipe at full precision where it matters). At
`UpcConvolver.setKernelTaps()` taps convert to Float. blockIn/blockOut
arrays inside `processBlockForChannel` switch to FloatArray; conversion
loops bracket the `conv.processBlock()` call.

**Numeric precision note.** 16-bit PCM input has 16-bit resolution;
single-precision float has a 24-bit mantissa. The PFFFT FFT path
preserves audio precision with ~48 dB of headroom above what's
audible. No quality loss vs the previous Double precision path.

**BSD-3-Clause compliance.** Bundled in the APK at:
  - `app/src/main/assets/licenses/LICENSE-PFFFT.txt` — full BSD-3-Clause
    text with copyright attributions (Julien Pommier 2013 + NCAR/UCAR
    2004 for the FFTPACK pieces PFFFT builds on).
  - `app/src/main/assets/licenses/LICENSE-JTRANSFORMS.txt` — JTransforms
    BSD-2-Clause attribution (still used for kernel-synthesis FFTs on
    the worker thread).
Settings → About section credits both libraries with a short attribution
paragraph pointing at `assets/licenses/`.

**Known unknowns (first NDK build for this project — may need follow-up
iterations):**
  - CMake's FetchContent against marton78/pffft master could pull a
    breaking change. Pin to a specific commit hash in a follow-up tag
    once the build is verified green.
  - PFFFT layout vs JTransforms packed-complex layout differ. Internal-
    layout spectra (what UPC uses) are opaque blobs — we never inspect
    bins so the layout difference is invisible to UpcConvolver, but if
    diagnostic code or a future mode needed canonical packed-complex
    output, [PffftBridge.reorderToCanonical] is available.
  - Net `.so` size: PFFFT compiled is ~30-60 KB per ABI. Two ABIs ≈
    100 KB extra in the APK. Negligible vs the existing ~10 MB.

## v0.9.5 — Mixed-Phase mode (4-mode phase lineup complete) (2026-05-13)

Adds the 4th phase mode: hybrid min-phase / linear-phase split at a
~120 Hz crossover. Completes the audio rebuild roadmap's user-visible
work; v0.10.0 will be a content/polish tag.

**FirEq.Mode.MIXED** — new synthesis path inside FirEq:

  1. Build complementary cosine-ramp crossover masks `H_low(f)`,
     `H_high(f)` with transition 80 → 180 Hz centered roughly at 120 Hz.
     The two sum to 1.0 everywhere (real-valued), so applying them to
     the EQ magnitude target preserves the EQ shape across the spectrum
     with no transition-band dip.
  2. Synthesize a linear-phase kernel from `mag · H_high` (above-
     crossover content gets the symmetric-impulse, transient-preserving
     path).
  3. Synthesize a min-phase kernel from `mag · H_low` (below-crossover
     content gets the energy-front-loaded, no-pre-ringing path —
     critical for bass transients like kicks, organ pedal, low rumble).
  4. Time-align: the linear-phase kernel peaks at KERNEL_LENGTH/2 (its
     natural group delay), the min-phase kernel peaks at index 0
     (causal). Shift the min-phase contribution forward by KERNEL_LENGTH/2
     samples so both reach the listener simultaneously through the
     convolution.
  5. Sum the two aligned kernels → single hybrid impulse response of
     length KERNEL_LENGTH. UPC sees just another 4096-tap kernel; same
     convolution cost as either pure mode.

**PhaseMode.MIXED + Settings.PHASE_MODE_MIXED** — added to the existing
enum + DataStore string. Legacy Boolean sync now treats MIXED as a FIR
mode (sets `phase_mode_linear = true`) so downgrade paths see a usable
value.

**EqAudioProcessor** dispatches MIXED through firEq.setMode(FirEq.Mode.MIXED).
Algorithmic latency for MIXED matches LINEAR_FIR (~70 ms total — the
linear-phase contribution dominates the group delay).

**EqScreen** grows from 3 chips to 4: "Pure IIR" / "Min FIR" /
"Linear FIR" / "Mixed". Per-chip description text updated.

**PlayerDiagnosticsTab** renders the MIXED label in the live readout:
"Mixed-Phase (min-phase < 120 Hz, linear-phase > 120 Hz, UPC)".

**Skipped (intentionally):** Brief #15 (PFFFT JNI migration) was the
brief's planned v0.9.5. Skipped for now — invisible-to-user CPU
optimization that adds JNI build complexity to the gradle pipeline.
Current UPC + JTransforms envelope is comfortable for podcasts at 2×
on modern devices. PFFFT lands as a dedicated future tag if profiling
shows need.

**Net at v0.9.5:** 4-mode phase lineup complete, UPC convolution
shipped, latency-honest transport, downloader hardened, ADPF clamped.
The audio subsystem's engineering arc per `_LOFIPOD_V1_BRIEF.md` is
substantially done. v0.10.0 will be content/polish: Audiophile Notes
rewrite, plain-language audio guide refresh, About-screen technique
credits.

## v0.9.4 — Toolchain bump for Media3 1.5.x compileSdk 35 requirement (2026-05-13)

Build fix tag. v0.9.3 (and v0.9.1, v0.9.2 since they inherited the bump)
failed CI `checkReleaseAarMetadata` because Media3 1.5.1 (introduced in
v0.9.1) requires `compileSdk 35` to consume — and the project was on
compileSdk 34 with AGP 8.5.2 (which can't go higher than 34). v0.9.0 was
green because Media3 was still 1.4.1 there. v0.9.3 inherited the broken
state; the v0.9.3 features themselves are fine.

Path chosen: bump the toolchain (not revert Media3). The 1.5.x VBRI and
audio-sink retry fixes are worth keeping; the toolchain bump is also
overdue regardless.

**Coordinated bumps:**

- Gradle wrapper 8.7 → 8.10.2 (AGP 8.7 minimum is Gradle 8.9; pick 8.10.2
  for stability)
- AGP 8.5.2 → 8.7.3 (first AGP line that supports compileSdk 35)
- compileSdk 34 → 35 (required by Media3 1.5.x)

**Held unchanged (deliberately):**

- targetSdk stays at 34. Bumping compileSdk lets us link against
  Android 15 SDK symbols; bumping targetSdk would also opt into
  Android 15 runtime behavior changes (notification permission tighter
  enforcement, partial photo picker default, etc.). That's a separate
  decision with its own risk surface — left for a later deliberate
  bump.
- Kotlin 2.0.20, KSP 2.0.20-1.0.25, Compose plugin 2.0.20 — all
  compatible with AGP 8.7.x.
- Compose BOM 2024.09.02 — compatible with compileSdk 35. Could be
  bumped for hygiene; no force-bump signal yet.
- R8 stays OFF.

If the build now compiles cleanly, the v0.9.3 features (UPC + 3-mode
phase lineup + Min-Phase FIR) are downstream and untouched here.

## v0.9.3 — UPC convolution + 3-mode phase lineup (2026-05-13)

Fourth tag of the audio rebuild roadmap. Combines the brief's planned
v0.9.3 (DSP foundation) and v0.9.4 (FIR resurrection + new mode) into one
release, per user direction — single-user alpha cadence makes the
brief's "dormant infrastructure then activation" split unnecessary.

**Brief #13 — UpcConvolver.kt** (new file, ~290 LOC). Uniform partitioned
convolution engine. Splits a 4096-tap kernel into P=4 partitions of L=1024
samples each, stored as packed-complex spectra; per input block of 1024
samples, runs ONE forward FFT at 2L=2048, accumulates a frequency-domain
delay-line multiply-accumulate against the partitions, runs ONE inverse
FFT, emits the second half (overlap-save). ~3× cheaper per output sample
than the v0.8.0 monolithic 8192-pt OLA, and the FDL stores INPUT history
(not output) so mid-stream kernel swaps yield a clean LTI switch — fixes
the "soft chuff per slider tick" symptom from brief §A2. Reference:
Wefers 2015 / Gardner 1995.

**FirEq.kt** (new file, ~470 LOC). Wraps UpcConvolver for both linear-
phase and min-phase FIR modes. Two kernel synthesis paths:

  - **Linear-phase:** same recipe as v0.8.0 (sample biquad cascade
    magnitude, zero-phase IFFT, circular shift, truncate, Kaiser
    window).
  - **Min-phase (NEW):** real-cepstrum derivation per
    Mian & Nainer 1982 / Oppenheim-Schafer §10.5. Same target
    magnitude → causal energy-front-loaded impulse response. No
    pre-ringing, sub-ms group delay across the audio band at typical
    EQ Qs (0.7–1.4). Best for transient-heavy speech.

DoubleRing primitive output ring moves here from v0.9.0's LinearPhaseEq
(no autoboxing on the audio thread).

**PhaseMode.kt** (new file). Three-value enum: PURE_IIR (default),
MIN_FIR, LINEAR_FIR. Replaces the v0.8.0 `phaseModeLinear: Boolean`.
Serializes as a string in DataStore (string key, future-proof for a
v0.10.0 Mixed-Phase fourth value).

**Settings — new `phase_mode` key + Boolean migration.** Reads:
prefer the new string key; fall back to the legacy `phase_mode_linear`
Boolean if absent (true → "LINEAR_FIR", false → "PURE_IIR"). Writes:
keep the legacy Boolean in sync so any future downgrade still sees a
usable value. No one-shot migration job — the read-side fallback
handles existing installs transparently.

**EqAudioProcessor — 3-mode dispatch.** `setPhaseMode(PhaseMode)` is the
new entrypoint; `setPhaseModeLinear(Boolean)` is kept as a deprecated
wrapper. `queueInputFir()` replaces `queueInputLinearPhase()` and routes
to FirEq for both FIR modes. `getChainLatencyUs()` reflects the new
mode-dependent algorithmic delays: ~6 ms (PURE_IIR), ~29 ms (MIN_FIR),
~70 ms (LINEAR_FIR). Chain reset still triggers on mode toggles.

**EqScreen — 3 chips restored.** "Pure IIR" / "Min FIR" / "Linear FIR"
with the per-mode descriptions visible. The v0.9.0 hide-and-explain
banner is gone.

**PlaybackService rehydrate** reads `settings.phaseMode.first()` (new
flow) and applies `setPhaseMode(savedMode)`. Pre-v0.9.0 users with a
saved `phase_mode_linear=true` get migrated to LINEAR_FIR on first
boot.

**PlayerDiagnosticsTab** carries the full mode label
("Pure IIR (biquad cascade)" / "Min-Phase FIR (UPC + cepstrum kernel)" /
"Linear-Phase FIR (UPC + symmetric kernel)") in the live readout.

**Deleted:** `LinearPhaseEq.kt` — superseded entirely by `UpcConvolver`
+ `FirEq`. Git history preserves the original implementation.

**Not in v0.9.3** (deferred to a later tag): off-thread DSP worker with
SPSC ring (brief #12) — pure optimization, not required for correctness;
explicit band-change crossfade ramp (brief #10) — UPC's natural LTI
switching already resolves the band-change click symptom; PFFFT JNI
(brief #15); Mixed-Phase mode (brief #17, v0.10.0). The new min-phase
FIR path replaces the brief's expected band-change crossfade benefit
by giving users a no-pre-ringing option that's already free of zipper
artifacts under UPC.

## v0.9.2 — Latency-honest transport (2026-05-13)

Third tag of the audio rebuild roadmap. Brief #7: the audio chain's
algorithmic delay is now subtracted from the position reported to the UI,
so the scrubber matches what the user is audibly hearing. Pro-DAW
behavior; sets up the larger v0.9.4 cut where linear-phase returns and the
chain-latency number grows from ~6 ms to ~52 ms when linear is engaged.

**Brief #7 — `EqAudioProcessor.getChainLatencyUs()`.** Public method
returns the chain's total algorithmic delay in microseconds:
linear-phase FIR group delay (when active — always 0 in v0.9.x while the
chip is hidden) + oversampler combined up/down group delay + limiter
look-ahead. Returns 0 when the chain is disabled or in passthrough
(those paths copy bytes through untouched). At v0.9.2 with the chain
engaged in minimum-phase: ~6.4 ms total; v0.9.4+ with linear-phase
restored: ~52 ms when linear is on.

**`PlayerController.currentPositionMs()` now subtracts chain latency.**
All UI consumers (scrubber polls, note-creation position, diagnostics
display, mini-player) automatically pick up the compensated reading.
Stall watchdog, DB persistence, checkpoints, and `jumpToPosition()`
deliberately keep reading `controller.currentPosition` directly — their
forward-progress / save-restore invariants are about raw frames advancing
through the audio sink, not about audible alignment.

**`PlayerController.seekTo(positionMs)` now compensates the target.**
A tap on "1:00" in the scrubber lands AUDIBLE-1:00 instead of
RAW-1:00 (which would be audible ~46 ms before 1:00 in linear mode,
~6 ms in min-phase). Sub-perceptible per-seek, but compounds visibly when
seeking repeatedly to a chapter or saved-note position.

Built-in `seekBack()` / `seekForward()` (the ±15 s / ±30 s buttons that
go through Media3's `Player.seekBack()`/`seekForward()`) are NOT
explicitly compensated — both endpoints are raw, so the audible offset
is the same constant on both sides and the relative seek is exact.

## v0.9.1 — Hardening: Media3 upgrade + downloader integrity + ADPF floor (2026-05-13)

Second tag of the audio rebuild roadmap. Three small-to-medium fixes from
`_LOFIPOD_V1_BRIEF.md`; no user-visible feature changes, all interior plumbing.

**Brief #8 — Media3 1.4.1 → 1.5.1.** Picks up the MP3 VBRI table-of-contents
fix (#1904), which the brief flagged as a direct candidate for "downloaded
MP3 stops short of the actual end" symptoms. Also includes audio-output
retry improvements in DefaultAudioSink that 1.4.x lacked.
`EqRenderersFactory` uses only public APIs (`DefaultAudioSink.Builder`,
`DefaultAudioProcessorChain`, `DefaultAudioTrackBufferSizeProvider.Builder`)
so the upgrade is a drop-in. R8 stays OFF — the v0.3.0 silent-audio
regression was a Media3-internal reflection issue and the keep-rules set
hasn't been authored yet.

**Brief #9a — 64 KB boundary truncate on resume.** If a SIGKILL lands
between `source.read()` and the next `raf.write()`, the row's
`bytesDownloaded` (last persisted at the prior 500 ms progress tick) can
sit ahead of the file's actual durable tail. Worse, page-cache flushes
aren't deterministic post-kill, so the file's apparent length can drift
relative to what was successfully fsync'd. Resume now truncates to a
BUFFER_SIZE-aligned offset (64 KB), so re-fetch on resume costs at most
one buffer's worth and never starts from the middle of a write boundary.

**Brief #9b — Verify Content-Length post-download.** A "drained" body
isn't always a complete body: some intermediaries close the stream cleanly
mid-transfer, and some servers return 200 short of the advertised
Content-Length. Verify `target.length() == totalLen` before flipping the
row to COMPLETED; mismatch throws and the worker marks FAILED instead.
Skipped when the server omits Content-Length (totalLen=-1) — nothing to
compare against.

**Brief #16 — Clamp `audioNs` floor in ADPF reporting.** Defensive 1 ms
floor on `audioNs` before the speed scale + before passing to
`PerformanceHintManager.Session`. Avoids passing 0 / near-0 targets that
downstream ADPF / governor smoothing code can divide by. Floor is well
below any plausible real per-buffer budget (~5 ms = 256 frames at 1×).

**Not in v0.9.1** (deferred): rest of brief — v0.9.2 latency-honesty,
v0.9.3 UPC infrastructure + off-thread DSP worker, v0.9.4 FIR rebuild +
new min-phase FIR mode, v0.9.5 PFFFT, v0.10.0 mixed-phase mode.

## v0.9.0 — Audio pest-control + linear-phase chip hidden for rebuild (2026-05-13, untagged on dev)

First tag of the multi-release audio rebuild captured in `_LOFIPOD_V1_BRIEF.md`.
Stability cut: silent bug fixes + one user-visible regression (linear-phase
chip removed) paired with a release note explaining the v0.9.4 return. Tracks
toward v0.10.0 (the brief calls that endpoint "v1.0.0" but v1.0.0 is reserved
for the user-triggered public launch).

**Brief #1 — Eliminate ArrayDeque<Double> autobox GC on the audio thread.**
`LinearPhaseEq.outputQueue` was `Array<ArrayDeque<Double>>`. Every
`addLast(value: Double)` autoboxed to `java.lang.Double` (Kotlin generic
collections force primitive boxing). At FRAME_SIZE=1024, stereo, 44.1 kHz
that's ~88,200 transient heap allocations/sec on the audio thread plus the
same on pop — sustained ~1.4 MB/s of ART garbage on the worst possible thread.
Symptom: sporadic clicks every 5–30 s on long sessions correlated with GC
activity. Replaced with a primitive-backed `DoubleRing` (power-of-two
DoubleArray + head/tail/mask), nested inside LinearPhaseEq. No autoboxing on
push/pop; ~64 KB/channel for the 8192-cap ring, negligible vs the existing
~80 KB working set.

**Brief #6 — Precompute FLAT kernel; remove sync FFT from configure().**
`configure(rate, channels)` previously called `synthesizeKernelSync(FLAT)` —
an 8192-pt FFT + IFFT + window + FFT on the audio/format-change thread
(~2–15 ms cold). Hoisted to a `FLAT_KERNEL_SPECTRUM` companion val
computed once at class load. For FLAT the magnitude response is unity at every
bin → impulse is rate-independent, so the cached spectrum is shared across
all sample rates.

**Brief #4 — Hoist chainReset(); call from both onFlush() and
setPhaseModeLinear().** Previously `onFlush` cleared filters / biquads / DC
blocker / limiter / oversampler / linear-phase EQ, but
`setPhaseModeLinear(on)` only reset the linear-phase EQ — the limiter's
look-ahead, oversampler delay lines, and biquad cross-fade state held the
previous mode's audio and produced a brief level burst at the mode-toggle
boundary. Extracted shared `chainReset()` private; both call paths now reset
the full chain.

**Brief #2 — Tune DefaultLoadControl for local files.** Was
`min=180s / max=600s / prioritize-time=true / no byte ceiling`. On local
`file://` sources the Loader read continuously up to the time threshold
(potentially ~106 MB decoded PCM at 256 kbps stereo), pressuring ART and
opening underrun windows during long GCs. New config: `min=30s / max=60s /
playbackMs=2/5 / prioritize-time=false / target-buffer-bytes=8 MB`. Plenty
for podcasts at any speed; engages the byte ceiling on local sources.

**Brief #5 — fsync downloaded file before markCompleted.** The DB row flip to
COMPLETED is the visibility barrier for handoff / completedFile()
consumers. Without fsync, an OS crash between fclose() and SQLite commit
could leave a COMPLETED row pointing at partial bytes. Added
`runCatching { raf.fd.sync() }` immediately before the RAF closes. Tolerated
silently if the FS doesn't support it (some FUSE passthroughs).

**Brief #11 — Hide linear-phase chip in EqScreen, suppress rehydrate, preserve
saved pref.** Linear-phase mode is removed from the user-facing chip set in
v0.9.0 while the convolution path is rebuilt. The DataStore key
`phase_mode_linear` is left untouched — v0.9.4 picks the user's preference
back up when the chip is restored (rebuilt on UPC + crossfade alongside a
new Min-Phase FIR mode). PlaybackService.onCreate rehydrate suppresses the
saved value (always boots into minimum-phase regardless). EqScreen displays
an explanatory note that surfaces the user's preserved preference when
they had linear on previously.

**Kept unchanged (deliberately):** Mid-playback streaming→downloaded handoff
(brief #3). The handoff is part of how the app is used in practice (start
streaming → drive into a connectivity-poor area → switch to local file
seamlessly). The chainReset on flush (#4) + LoadControl tightening (#2)
quietly harden the swap path; tweaks land per-issue rather than wholesale
disable.

**Not yet in v0.9.0 (queued for later tags):** UPC infrastructure (#13,
v0.9.3), off-thread DSP worker (#12, v0.9.3), band-change crossfade (#10,
v0.9.4), Min-Phase FIR mode (#14, v0.9.4), PFFFT migration (#15, v0.9.5),
Mixed-Phase mode (#17, v0.10.0). Brief covers all of them; this is the
stability cut only.

## v0.8.0 — In-Player Diagnostics tab + full-screen mode + snapshot-to-note

Bigger than a patch release. Adds a fast-path for the user to inspect
audio-chain health from inside the listening experience itself rather
than navigating out to Settings → Audio Fine-tuning → Diagnostics every
time something sounds wrong.

**Settings → "Show Diagnostics tab on Player"** (default off). When on,
PlayerScreen's bottom tab strip grows a 4th tab "Diagnostics" alongside
Notes / Details / Transcript. Conditional rendering — turning the
setting off restores the original three-tab strip without leaving any
ghost tab UI behind.

**The tab itself** is structured top-down to mirror the triage workflow
("at a glance → in detail → forensic"):

  1. **Watchpoints ribbon.** Six color-coded chips (Green OK / Yellow
     warning / Red bad / Neutral) for the failure modes the v0.7.5
     pest-control sweep cared about: audio-thread priority, PerfHint
     session state, wake lock + isPlaying, load factor avg/max, recent
     renderer stalls (60 s window), player state + error. Each chip
     carries a one-line tooltip explaining what the value means.

  2. **Action chips.** "Save as note", "Copy", "Full-screen". The first
     two pin or transcribe the current diagnostic state for later;
     the third toggles in-Player full-screen mode (see below).

  3. **Live readout.** Same compact selectable text block the existing
     AudioDiagnosticsScreen surfaces — but only the rows that matter
     while live: audio_enhancement, phase_mode, speed, passthrough,
     audio_thread (tid + name + priority + demotions), perf_hint
     (supported + active + targetUs — note this is the *speed-scaled*
     target after the v0.7.5 D2 fix), wake_lock, load_factor,
     player state, last_error.

  4. **Load-factor sparkline.** Rolling 30-second bar chart of
     avgLoadFactor (60 samples at the 500 ms tick). Bars colored OK /
     warn / bad against the 1.0 deadline; a thin axis line marks the
     deadline itself. Trend toward 1.0 is visible at a glance —
     point-in-time readings hide trends.

  5. **Recent events.** Newest-first slice of the breadcrumb log (top
     12 shown in-tab; full log on the dedicated AudioDiagnosticsScreen).

**Snapshot to note.** Tapping "Save as note" builds a timestamped text
block — header line + reason + the Live readout + the load-factor
history sequence + the most-recent 25 events — and writes it to the
existing `episode_note_entry` table for the currently-playing episode
at the current playback position. Notes browser, episode timeline,
backups, and search all light up for free; the diagnostic dump
naturally lives alongside the user's manual notes for the same moment.
Pragmatic anchor: `Toast` confirms; no SnackbarHost plumbing needed
into the tab.

**Auto-snapshot on bad events.** When the audio thread emits one of
the high-signal kinds (`priority_demoted`, `renderer_stall`,
`no_progress`), the tab auto-fires a snapshot-to-note with the
triggering event's name + detail as the reason. Rate-limited to one
per 60 s total so a sustained demotion can't spam the Notes browser
with dozens of duplicates. Detection uses event timestamps not buffer
size, so the underlying 50-slot ring's wrap-around evictions don't
fool the detector into thinking nothing new happened. Surfaces a
transient banner ("Auto-snapshot saved (...)") in the tab so the user
knows forensics were captured even if they were looking at the
Diagnostics tab when the event hit.

**Full-screen mode.** Tapping the active tab toggles the existing
bottom-tabs full-screen flag (Notes / Transcript already had this).
In full-screen the player chrome above the strip — artwork, title,
scrubber, transport — hides; the tab content fills the screen below
the top app bar. New in v0.8.0: **the mini-player stays anchored at
the bottom of the screen during full-screen on the player route**.
That keeps transport (play / pause / scrub / ±15s / ±30s) reachable
while the Diagnostics tab is scrolling live; works for the other
fullscreen tabs (Notes / Transcript) too — a pre-existing UX gap that
this work happens to close as a side effect. State propagates via a
hoist into AppNav: `PlayerScreen` invokes
`onPlayerTabsFullscreenChange(Boolean)` on every change, and a
`DisposableEffect` resets the host's flag to false on disposal so a
back-navigation while still in fullscreen mode can't leave the host
with a stale override.

**Wake-lock exposure.** `PlaybackService.wakeLockHeld: Boolean`
@Volatile companion field, written by `acquirePlaybackWakeLock` /
`releasePlaybackWakeLock` right after the actual acquire/release.
Read by the in-Player diagnostics tab so the "Wake lock: held /
missing / lingering / idle" watchpoint reflects what's actually on
the kernel side, not what the code intended.

**EqAudioProcessor.isPhaseModeLinear() accessor.** Read-only accessor
for the phase-mode flag so the diagnostics tab can render "Minimum
(biquad)" vs "Linear (4096-tap FIR)" without a Settings round-trip
on every recompose. The existing setter (`setPhaseModeLinear`)
remains the single writer; the getter pairs with it cleanly.

Files affected:
- `app/src/main/java/com/lofipod/app/data/Settings.kt`
  (`showDiagnosticsTabInPlayer` flow + setter + DataStore key)
- `app/src/main/java/com/lofipod/app/ui/screens/SettingsScreen.kt`
  (new `DiagnosticsTabToggleRow` slotted into the Audio diagnostics
  section)
- `app/src/main/java/com/lofipod/app/ui/screens/PlayerDiagnosticsTab.kt`
  (new — the entire tab + watchpoints + sparkline + snapshot logic)
- `app/src/main/java/com/lofipod/app/ui/screens/PlayerScreen.kt`
  (conditional 4th tab plumbing + fullscreen-state hoist)
- `app/src/main/java/com/lofipod/app/ui/MainActivity.kt`
  (mini-player override during full-screen on the player route)
- `app/src/main/java/com/lofipod/app/player/PlaybackService.kt`
  (wake-lock state companion field)
- `app/src/main/java/com/lofipod/app/audio/EqAudioProcessor.kt`
  (`isPhaseModeLinear()` getter)

ai_contamination: true # claude opus 4.7

## Audio-thread hardening: wake lock + speed-aware PerfHint + re-elevation + TID swap

Pest-control sweep against the screen-off-DSP-chop pattern reported on
S7 plus the post-auto-download freeze observed 2026-05-11. Four
independent fixes targeting the audio-thread / scheduling surface; the
playback state-machine fixes that explain the autoplay-style beep on
manual playback land in a separate commit.

**PARTIAL_WAKE_LOCK acquired during `isPlaying`.** `WAKE_LOCK`
permission was declared but never acquired anywhere in `app/src/main`.
Foreground service (`mediaPlayback` type) keeps the process alive
across Doze but doesn't constrain the CPU governor from downclocking
when the screen goes off. PARTIAL_WAKE_LOCK acquired on
`Player.Listener.onIsPlayingChanged(true)` and released on `false`;
also released in `onDestroy` as a safety net. `setReferenceCounted(false)`
because acquire/release follow the listener strictly. `isHeld` guards
on both sides tolerate accidental double-call without the under-locked
warning. Try/catch around acquire so a hostile SELinux denial can't
crash playback.

**PerformanceHintBridge target now scaled by playback speed.** Bridge
was passing `targetNs = frameCount * 1e9 / sampleRate` — the audio
duration of the buffer, not the wall-clock budget. At 2× playback the
budget is half that. The governor was choosing a CPU frequency for a
deadline twice as generous as reality; screen-on the compositor masked
the slack, screen-off the DSP path started missing deadlines (the
S7-reported chop pattern). Added `@Volatile var playbackSpeed: Float`
on `AudioChainTelemetry`, written by `PlayerController` from
`onPlaybackParametersChanged`, `setSpeed`, and the per-podcast default
override branch in `playEpisode`. `recordBufferTiming` divides
`audioNs` by the current speed before calling `bridge.ensureSession`.

**PerformanceHintBridge invalidates session on TID change.**
`ensureSession` previously had create + retarget branches only; a TID
swap (sink rebuild on format change, low-power audio path swap on some
OEMs) left the hint session pinned to a dead thread and the new audio
thread ran unhinted forever. Added `currentThreadId` tracking and a
TID-swap branch that closes the existing session before the create
path runs. AudioChainTelemetry already detected the swap and logged
the `audio_thread` breadcrumb — now the hint follows the breadcrumb.

**Active priority re-elevation on demotion detection.** When
`Process.getThreadPriority(tid) >= 0` (audio thread no longer above
normal scheduler priority), the previous code only logged + counted.
Now it calls `Process.setThreadPriority(tid, THREAD_PRIORITY_AUDIO)`
once per demotion transition and reads back the new priority. The
breadcrumb message reflects whether re-elevation succeeded
("re-elevated to AUDIO") or didn't ("re-elevation failed; likely
cgroup-bound"). Free mitigation for pure-nice-value demotions
(e.g. Media3 didn't elevate after a thread recreate); inert on OEM
cgroup migrations (cpuset/cpu group caps survive nice changes — the
"D1" blind spot still warrants direct `/proc/self/task/<tid>/cgroup`
reading, not addressed in this sweep).

Files affected:
- `app/src/main/java/com/lofipod/app/player/PlaybackService.kt`
- `app/src/main/java/com/lofipod/app/audio/AudioChainTelemetry.kt`
- `app/src/main/java/com/lofipod/app/audio/PerformanceHintBridge.kt`
- `app/src/main/java/com/lofipod/app/player/PlayerController.kt` (speed
  mirror writes; the rest of this file's changes land in the
  state-machine commit)

ai_contamination: true # claude opus 4.7

## Playback state-machine: autoplay-flag leak + already-downloaded handoff misfire

Two narrow defects in the playback startup path observed during the
Pattern B repro 2026-05-11. Both produced user-visible "weird beep at
play start, then freeze ~5 s later" without an obvious cause because
each defect on its own was silent — they only made noise when they
compounded.

**`lastPlayWasAutoplay` leaked across a controller-null bail.**
`playEpisode` used to consume the flag immediately AFTER the
`controller == null` early-return — so a call from
`advanceToNextInQueue` (which sets the flag) that arrived while the
MediaController was still binding stored the play in `pendingPlay` and
returned without consuming the flag. A subsequent user-initiated
`playEpisode` (a manual tap) consumed the stale flag and armed the
autoplay-confirmation timer; the user heard the 60-second countdown
beep on a play they perceived as manual. Fix: consume the flag
unconditionally at function entry, pass it through `PendingPlay` so
the drain in `connect()` can restore it on the replayed call.

**Auto-download fired for already-downloaded episodes during
`_byId` hydrate window.** The `needsStart` check at the auto-download
trigger gated on `app.downloadsApi.byId.value[guid] == null ||
state == FAILED`. `_byId` is hydrated asynchronously at app launch
(`LofiPodDownloader.init { cleanupScope.launch { hydrate() } }`), so a
fast-play on cold start saw `null` even when the DAO row existed and
the file was fully on disk. The auto-download start emitted a
COMPLETED transition on `byId`, which `observeDownloadCompletion`
interpreted as "download just finished, mid-playback handoff!" —
firing `playHandoffCue` (two unducked quick beeps) plus a
`setMediaItem` swap right at the moment audio finally started. The
`setMediaItem(newItem, savedPosMs); prepare()` cycle dropped the
player back into STATE_BUFFERING for `bufferForPlaybackAfterRebufferMs
= 8 s` — the observed "playback froze 5 seconds after the beeps."
Toggling Audio Enhancement off didn't help because the freeze was in
the rebuffer cycle, not in DSP. Fix: use the already-suspending
`completedFile()` helper (DAO-fallback aware) as the authoritative
"is this on disk?" check; only fire start() when no file exists AND
the in-memory state agrees.

Files affected:
- `app/src/main/java/com/lofipod/app/player/PlayerController.kt`

ai_contamination: true # claude opus 4.7

## EqScreen layout polish: tooltip-hint + reference-menu collapse

Two UX cleanups on Audio Fine-tuning, both followups to the v0.7.3
tooltip work.

**Tooltip-discoverability hint beside "Graphic EQ" heading.** Long-press
on the band Hz labels surfaces the per-band tooltip — but long-press is a
hidden gesture and users had no way to discover the feature without
documentation diving. Inline ancillary copy ("long-press a frequency for
more info") sits to the right of the "Graphic EQ" section heading,
visible exactly when the bands come into view. Smaller / quieter type
(`bodySmall`, `onSurfaceVariant`) so it reads as a hint, not a control.

**Reference links collapsed into a TopAppBar overflow menu.** The three
right-justified TextButton rows ("Audio guide (plain language)",
"Notes for audiophiles", "Audio diagnostics") at the top of the screen
body left a wide blank stripe on the left of each row and ate three rows
of vertical real estate before the actual EQ controls started. Replaced
with a single kebab (`MoreVert`) icon in the app bar's `actions` slot
that opens a `DropdownMenu` with all three destinations.

Trade-off: each link is now two taps away (kebab + menu item) instead
of one tap. Acceptable because:
  - The actual EQ controls are now the first thing visible in the body
    on every visit.
  - The right-edge blank-stripe problem is gone entirely.
  - It's the standard Material pattern for "secondary screen actions" —
    discoverability stays high (kebab icon is universally recognized).
  - Settings still exposes both notes pages directly via the "Notes
    about audio" section, which is the primary discovery surface.

Order in the dropdown matches the previous body order: plain-language
guide first (gentlest entry), audiophile spec second, live diagnostics
third.

ai_contamination: true # claude opus 4.7

## EQ tooltips + Settings discoverability for the lofi notes

Two followups to the lofi-notes shipping in the previous entry, addressing
the discoverability + tooltip flags I'd noted there.

**Tooltips on the EQ screen.** Material3 `TooltipBox` + `PlainTooltip` —
long-press a label to see a one-line explanation. Two locations:

  - **Volume boost label.** Tooltip distills the dB intuitions: "+6 dB is
    roughly 2x linear amplitude; +10 dB is roughly 2x perceived loudness;
    the limiter catches peaks so cranking stays clean." Lifted from the
    lofi-notes glossary, condensed for the tooltip width budget.
  - **Each band-row Hz label** (31, 62, 125, 500, 2k, 8k). Each tooltip
    is the band's plain-language one-liner from the lofi notes screen
    (e.g. 125 Hz: "Low-mid / warmth — where boomy rooms live. Cut 2 to
    4 dB if a podcast sounds like a small kitchen."). New
    `bandTooltipText(centerHz)` helper next to `formatHz` so future band
    changes can extend the table without touching the layout.

Long-press is the standard Material3 tooltip trigger and doesn't conflict
with vertical scroll (different gesture) or the band slider's drag (only
the thumb has a pointerInput; the label is a sibling Text). Q tooltip
deliberately omitted — Q isn't exposed in the EqScreen UI, so there's
nothing to attach to.

**Discoverability — Settings restructure.** The "Notes for audiophiles"
button used to live inside the "Audio diagnostics" section, which made it
look like another diagnostic surface and meant a non-audiophile reader
might never click it. Restructured:

  - **Audio diagnostics** section now holds just the inline mini-readout,
    "Open full audio diagnostics", and "App diagnostics (bugs)".
  - **New "Notes about audio"** section sits below it. Both notes pages
    exposed equally as parallel options:
      - "Audio guide (plain language)" -> lofi notes
      - "Notes for audiophiles" -> audiophile notes
    Section subtitle: "Two takes on the same audio chain. Pick the one
    that matches how you want to think about it."

**EqScreen also gets the lofi link.** The Audio Fine-tuning screen
already had right-justified links to "Notes for audiophiles" and "Audio
diagnostics" at the top. Added "Audio guide (plain language)" above
those two, so a curious slider tweaker can reach the friendly guide
without first having to land on the audiophile-flavored page.

Both new entry points wire to the same `lofiNotes` route added in the
previous entry. Cross-links between the two notes screens still work as
before.

ai_contamination: true # claude opus 4.7

## Non-audiophile Lofi notes: plain-language companion to the spec page

New Settings reference page for the curious-but-not-yet-trained listener
who's noticed the EQ sliders and wants to know what they do without having
to learn what a biquad is first. Written as a sister screen to the existing
Notes for audiophiles — both pages cover the same audio chain, but in
different registers for different audiences.

**New screen** `NonAudiophileLofiNotesScreen.kt`. Sections (with the
ordering chosen to walk a reader from definitions out to recipes):

  1. Who this page is for
  2. Words to know — Lofi, Audiophile, DSP, Hz/kHz, dB, dBFS, PCM, sample
     rate, EQ, latency. First mention of every abbreviation expanded
     inline; each entry is one short technical sentence followed by a
     friendly expansion.
  3. What LofiPod does to your audio — high-level chain diagram with the
     defaults-are-passthrough story up front so a reader knows the chain
     is opt-in.
  4. Master gain — what it is vs master volume; when to use; how the
     limiter keeps boosts safe.
  5. Equalizer — three sub-sections: introduction, ASCII frequency map
     anchored to real-world content (male / female voice fundamentals,
     sibilance, phone-call bandwidth), band-by-band tendencies for each
     of the six bands.
  6. Q (band width) — explained as "you probably don't need to touch it."
  7. Phase modes — minimum vs linear with the "leave it on minimum"
     guidance up front.
  8. DC blocker — what it is, when to flip on (low-bitrate MP3 sermons,
     etc.).
  9. Skip silence — levels and the trade-off (clipping thoughtful pauses).
 10. Limiter — always-on safety net; explained without slider.
 11. Pass-through and Hold to A/B — the comparison workflow.
 12. Recipes — six "I want to..." goals with concrete starting EQ moves
     (clarity, boom, thinness, sibilance, low-volume listening, FLAT
     reset, just-make-it-louder).
 13. How to tell if it's actually working — Hold to A/B + Audio
     diagnostics screen.
 14. If you want to go deeper — pointer back to Notes for audiophiles +
     diagnostics.

Style: hand-written original prose, no external sources cited (every
concept covered is generic audio knowledge). Concise technical line
followed by friendlier expansion, per the audience brief. ASCII frequency
spectrum + band-purpose grid in the EQ section. Subtle horizontal
dividers between sections for visual breath.

**Polish on `AudiophileNotesScreen.kt`.** Same content; restructured
visually to match the new sister page:

  - New `CrossLink` helper renders a right-justified TextButton row, used
    at the top AND bottom of the page to navigate to the lofi-notes
    screen ("Non-audiophile Lofi notes"). Mirrored on the lofi-notes
    side with the inverse label.
  - Subtle `SectionDivider` (1 dp horizontal rule at 25% outline alpha)
    inserted between every top-level section. Quieter than a hard
    section break, gives the eye a place to land between dense
    technical paragraphs.
  - Tightened import list (no more wildcard imports for foundation.layout
    + material3) so the dependencies are explicit.

**Navigation wiring** in `MainActivity.kt`. New `lofiNotes` route plus an
`onOpenLofiNotes` callback into the audiophile-notes composable; both
routes use `launchSingleTop` so flipping back and forth between them
doesn't accumulate stack entries. Plain `popBackStack` for back so the
user returns to whichever screen sent them in (Settings, EqScreen, or
the sister notes page).

**Discoverability note for future work.** Both notes pages are reachable
only via Settings -> "Notes for audiophiles" (the existing entry) and
then the cross-link. A reader who self-identifies as a non-audiophile
might skip the audiophile-flavored entry and never find the lofi-notes
page. If usage data later suggests this is a real problem, the right
fix is probably renaming the Settings entry to something more inviting
("Notes about audio") with both pages exposed equally; left for a
future pass.

ai_contamination: true # claude opus 4.7

## Performance Hint API + audio-thread priority diagnostics

Two more upstream attacks on the same scheduling-jitter problem v0.7.0
absorbed downstream. Both surface in Settings -> Audio diagnostics under a
new "Scheduler" section so any future stutter report includes the OS-level
facts (priority, hint session state) right next to the existing wallclock
load factor.

**1. Performance Hint API (Android 12+).** New
`audio/PerformanceHintBridge.kt` wraps `android.os.PerformanceHintManager`.
On API 31+ devices it creates a hint session keyed to the audio sink's TID
with target = wall-clock budget per buffer (= frame_count / sample_rate),
and reports actual elapsed wallclock after each DSP pass. The OS uses both
to keep the CPU at the right frequency for the workload — eliminates the
"governor downclocked, then had to spin back up under the FIR convolution"
pattern that dominated the high-load tail at 2x speed.

   - All API 31+ types stored as `Any?` so the class loader never has to
     resolve `PerformanceHintManager` on lower-SDK devices. Methods gate on
     `Build.VERSION.SDK_INT >= S` and cast at call sites.
   - Defensive try/catch around every API call; some OEM builds ship broken
     implementations. Failure drops the session silently and remembers the
     last error for diagnostics — never disrupts audio.
   - Wired through `AudioChainTelemetry.installPerformanceHintBridge` from
     `LofiPodApp.onCreate` (new `perf_hint_install` startup phase). The
     audio thread reaches it via the existing per-buffer
     `recordBufferTiming` call — one TID compare + a JNI hop into the perf
     hint service per buffer when supported.

**2. Audio-thread identity + priority readout.** Same
`recordBufferTiming` path now also captures `Process.myTid()` +
`Thread.currentThread().name` on the first buffer (and on the rare event of
the sink swapping threads), and re-reads `Process.getThreadPriority(tid)`
every buffer. The diagnostic surfaces the priority with a label:
  - **-19 URGENT_AUDIO** = best
  - **-16 AUDIO** = expected (Media3's `AudioSink` callback thread)
  - **0 DEFAULT** = WRONG; the chain would be competing with normal app
    threads and underruns expected on any CPU contention
  - **>0 BELOW normal** = WRONG; thermal demotion or aggressive power
    saving has clobbered audio prioritization

If the readout ever shows DEFAULT or positive, that's a real bug to chase.
Re-reading every buffer (cheap — one syscall) catches OEM kernels that
demote the audio thread under thermal pressure mid-session.

   - **Auto-detect + log on demotion.** Every buffer compares the live
     priority against `>= 0`. On the transition into a wrong state, logs
     a `priority_demoted` breadcrumb (with TID + observed priority) and
     bumps a `priorityDemotions` counter. On transition back to good,
     logs `priority_recovered`. Logged on transitions only, not per
     buffer — otherwise a sustained demotion would flood the 50-slot
     event ring within a second. The Scheduler section surfaces the
     cumulative `demotions` count; tap into Recent Events to see the
     per-event timestamps.

**Surface.** New "Scheduler" section in `AudioDiagnosticsScreen` between
Performance and Counters: shows TID + name + priority label + perf-hint
state (unsupported / active / target ms). Clipboard dump includes it so
bug reports carry the full OS-level picture. HELP_TEXT updated.

For the primary user (Android 14, recent Pixel-class device) this should
read "perf_hint = active, target = ~23 ms / buffer" and "thread_priority
= -16 AUDIO" once playback starts. Watching how often the load factor's
max stays bounded vs. spikes will be the empirical test of whether the
hint is actually moving the needle.

ai_contamination: true # claude opus 4.7

## Battery-optimization opt-out prompt in Settings

Followup to v0.7.0's playback-hang fix. The buffer expansion + watchdog
hardening absorbs scheduling jitter downstream; this attacks one of the
upstream causes for users on devices with aggressive Doze / App Standby
defaults (Xiaomi, OnePlus, Samsung's "deep sleep" lists, etc.).

**Manifest:** added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Required for
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to fire — without it the
system rejects the intent with SecurityException. Declaring the permission
only enables the *request*, not the exemption itself; the user still
confirms the system dialog.

**Settings:** new "System" section (between Audio and Data) with a
`BatteryOptimizationRow`. Reads `PowerManager.isIgnoringBatteryOptimizations`
on every recomposition triggered by a tick, and bumps the tick from a
`rememberLauncherForActivityResult` callback so status auto-refreshes when
the user returns from the system prompt. Two states:
  - **Optimized** (default): "Allow unrestricted" button fires
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  - **Unrestricted**: "Open settings" button fires
    `ACTION_APPLICATION_DETAILS_SETTINGS` so the user can revert.

No first-launch nudge — surfaced as a discoverable Settings entry rather
than a modal interrupt. Users who already manually disabled battery opt
(e.g. the primary user) see the unrestricted-confirmation state and won't
be re-prompted.

For the primary user this is zero-impact (already unrestricted, manual fix
didn't move the needle for the 2x hang — that was the buffer/watchdog
issue, fixed in v0.7.0). The value is for sideload recipients on stock
Android 12+ and especially on aggressive-Doze vendor skins.

ai_contamination: true # claude opus 4.7

## Playback hang at 2x: bigger AudioTrack buffer + harder watchdog flush

User report (continued): "playback often hangs and needs to be flushed.
episode is downloaded. playback speed is x2. after a minute or so
playback is volatile and can stop." Watchdog landed in v0.6.16 + tuned
in v0.6.18 wasn't fully solving it.

Three compounding causes, all addressed:

**1. AudioTrack output buffer too small at 2×.** Media3's default
`DefaultAudioTrackBufferSizeProvider` gives a 250 ms minimum PCM
buffer with a 4× multiplier of the OS minimum. Two ways the 2× speed
case lands in trouble:
  - When AudioTrack is doing the speed natively (modern phones,
    `enableAudioTrackPlaybackParams=true`), the 250 ms buffer drains
    in **125 ms wall-clock** at 2×. Any GC pause, thermal throttle,
    or kernel scheduling jitter longer than 125 ms starves the
    AudioTrack.
  - When Sonic in the chain is doing the speed, our DSP chain has to
    feed Sonic at 2× source rate, so per-buffer wallclock load is
    doubled — small jitter that's a non-event at 1× becomes an
    underrun at 2×.

Fix in `EqRenderersFactory.buildAudioSink`: pass a
`DefaultAudioTrackBufferSizeProvider` configured with 1.5 s
minimum / 3.0 s max / 8× multiplier. ~576 KB of extra audio
buffer at 48 kHz stereo int16 — negligible memory cost, ~6×
larger headroom against jitter. Trade-off: EQ-tweak responsiveness
drops from ~250 ms to ~1.5 s of "old EQ before new takes effect."
Acceptable — the hang was a real bug, EQ-while-listening is a
niche workflow.

**2. Watchdog recovery seek-to-self can be deduped to a no-op.** The
v0.6.16 / v0.6.18 watchdog called `seekTo(currentPosition)` to flush
the audio sink. Media3 / DefaultAudioSink may dedup a same-position
seek and skip the flush — which is why the user kept observing
stalls even though the watchdog log said "force-flushed at Xs". A
100 ms backward offset (`seekTo(currentPosition - 100)`) guarantees
a real seek + flushes the renderer chain + replays the last 100 ms
of audio the user likely missed during the stall. New
`STALL_RECOVERY_REWIND_MS` constant = 100.

**3. Watchdog stopped during BUFFERING.** The previous lifecycle was
"start when isPlaying flips true, stop when it flips false." When
the AudioTrack underruns badly enough, ExoPlayer transitions out of
`STATE_READY` into `STATE_BUFFERING` with isPlaying=false — which
killed the watchdog right when arm B should have been catching it.

Lifecycle moved from `onIsPlayingChanged` to `onPlayWhenReadyChanged`
so the watchdog tracks user intent, not moment-to-moment isPlaying.
Cold-launch path (already-playing session) explicitly starts the
watchdog after addListener since Media3 doesn't replay state-change
callbacks for the listener's initial state. New arm B in the
watchdog body: when `playbackState == STATE_BUFFERING && playWhenReady`,
track a separate buffering-start timestamp. After
`BUFFERING_STALL_THRESHOLD_MS = 8 s` of buffering, fire the same
recovery as arm A. 8 s is longer than arm A's 6 s because legitimate
buffering on a far scrub or first remote-stream play can take
several seconds — but on a downloaded local file 8 s of buffering
means the renderer chain is wedged.

`triggerStallRecovery` extracted as a shared helper so both arms
emit the same UI/diagnostics surface.

ai_contamination: true # claude opus 4.7

## Episode size readout: simpler format + bottom-right placement

Two refinements on v0.6.23's `s=Nmb; d=Nmb` design:

- **Drop the `s=`/`d=` prefixes.** Streaming and downloading both pull
  the full enclosure, so the dual readout was redundant — just `Nmb`
  reads cleanly and trusts the user to know what it means.
- **Move it to the bottom-right corner.** On EpisodeRow it sits below
  the action row (Play / Download / Queue / Heart) right-aligned; on
  PlayerScreen it sits below the playback bar's position/duration
  pair, right-aligned. Both screens treat it as a passive vital stat
  rather than competing with primary controls.

Plumbing on PlayerScreen: new `episodeSizeBytes` state loaded by a
`LaunchedEffect(displayedGuid, isPreview, previewData)` that pulls
from `previewData.episode` in preview mode and via
`episode_state -> repo.cached(feedUrl) -> Episode` for live. Refreshes
on track transitions + live ⇄ preview swap. Hidden when the feed
didn't publish an enclosure length.

ai_contamination: true # claude opus 4.7

## Episode size readout + stale APK cache cleanup

Two fixes prompted by user reports:

**Per-episode size readout.** New `audioByteSize` field on `Episode`,
parsed from RSS `<enclosure length="...">` and persisted through
`FeedDiskCache`. Rendered on each episode row in the meta line as
`s=Nmb; d=Nmb` so the user can see bandwidth + disk cost at a
glance before tapping play or download. Both numbers are the same
(streaming and downloading both pull the full enclosure file) — the
dual readout matches the user's two distinct mental questions
("how much data?" vs "how much disk?") legible at a glance.
Sub-megabyte sizes show as `<1mb` rather than rounding to `0mb`.

**Stale APK update cache cleanup.** User report: app data hit 214 MB
despite having only a few episodes played. Root cause was
`UpdateChecker` accumulating cached APKs at
`cacheDir/updates/lofipod-<versionCode>.apk` — one ~57 MB file per
shipped tag the user had downloaded, never cleaned up. After 4 tags
shipped, that's ~228 MB of stale APKs alone.

`LofiPodApp.onCreate` now runs a one-shot startup cleanup alongside
the existing legacy-cache wipe: lists `cacheDir/updates/`, keeps the
APK matching the currently-installed `versionCode` (in case the user
is mid-install when this fires), deletes everything else. Idempotent.
Combined with the v0.6.22 orphan sweep on `episode_audio/`, app data
should now stay roughly proportional to actually-downloaded
episodes + one current update APK at most.

ai_contamination: true # claude opus 4.7

## Mid-playback handoff to local file + orphan sweep + audiophile reflow

Three improvements bundled:

**Mid-playback handoff to local file.** When an episode is being
streamed over HTTP and its auto-download completes mid-listen, the
player now swaps the MediaItem to the local `file://` URI without
making the user reset the episode. Mechanics:
- New `observeDownloadCompletion` collector in `PlayerController` —
  watches `downloadsApi.byId` for the currently-playing guid.
- Trigger conditions: state flips to COMPLETED + current MediaItem
  URI scheme is HTTP + we haven't already triggered for this guid.
- Snapshot position + playWhenReady, build a new `file://` MediaItem
  with the same metadata, fire a quick double-beep cue in parallel
  via `BeepPlayer.playHandoffCue` (two 80 ms tones, 40 ms gap, no
  player ducking — fills the brief silence from setMediaItem +
  prepare so the swap reads as intentional rather than a glitch),
  setMediaItem(item, savedPosMs), prepare(), play() if was playing.
- Snackbar: "Playback branched to downloaded file."

**Orphan-file sweep.** `LofiPodDownloader.cleanupOrphans()` runs once
at startup after `hydrate()`. Lists files in `episode_audio/` and
deletes any whose path doesn't appear in the `lofi_download` DAO.
Safety net for the rare leak (delete failure during `remove()`, app
crash mid-cleanup, etc.). The single-file-per-episode invariant is
already enforced by the deterministic SHA256-derived filename + the
delete-on-remove path; this is the catch-all.

**Audiophile notes — natural-width prose reflow.** User report:
"the text on this page is poorly formatted. lines are split to next
lines in what appears to be arbitrary." Root cause: the `"""`-style
raw-string constants are hard-wrapped at fixed column widths in the
source, and Compose's `Text` honors those line breaks at render. New
`reflowProse()` joins prose paragraph lines with single spaces (so
they re-wrap to actual device width) while preserving indented
blocks (the signal-chain ASCII art, latency math) by detecting
leading-2-spaces lines and leaving those paragraphs verbatim.

ai_contamination: true # claude opus 4.7

## Fix: completedFile() saw stale empty StateFlow on cold start

User report: 2x on a downloaded episode hit "waiting on network"
within a minute, even though the file was on disk. Streaming over
HTTP, not playing the local file.

Root cause: `LofiPodDownloader.completedFile(guid)` only checked the
in-memory `byId` StateFlow. That map is hydrated from Room
asynchronously via `init { cleanupScope.launch { hydrate() } }`. If
the user taps an episode early in the app lifecycle (before hydrate
completes), the fast path returns null and `playEpisode` falls back
to the HTTP URL. ExoPlayer locks onto that URI for the lifetime of
the MediaItem — so even after hydrate finishes, the player keeps
streaming the remote source. At 2x speed that races the buffer empty
within a minute and surfaces the misleading "waiting on network"
snackbar despite the file being right there on disk.

Fix: `completedFile` is now suspend with a two-tier lookup:
1. Fast path reads `byId.value` (steady-state hit).
2. Cold-start fallback queries `downloadDao.get(guid)` directly. A
   row with state=COMPLETED + file present on disk is safe to play
   even before the StateFlow catches up.

The PlayerController call site is already inside a `scope.launch`
coroutine, so the suspend signature change just works at the call
site — no plumbing changes elsewhere.

(Open issue not addressed here: if a download FINISHES during
playback of the same episode, the player keeps streaming HTTP
because MediaItem URI is fixed at construction time. That's a
separate "swap MediaItem mid-playback" feature; would cause a brief
audible interruption when swapping. Hold off until we see if it
matters in practice.)

ai_contamination: true # claude opus 4.7

## Stall watchdog: tighter, surfaced to the UI, snackbar feedback

User report: at 2x on a downloaded episode the cycling-position
freeze hit "almost immediately" with no UI signal — no buffering ring
around the play button, no snackbar, just frozen audio for ~10 s
until the v0.6.16 watchdog's silent recovery seek.

Three fixes:

1. **Surface the stall to UI.** New `PlayerState.isStalled` flag,
   owned by the watchdog (Media3 keeps the player in STATE_READY
   during DSP-side stalls so `isBuffering` stays false — the audio
   data is loaded, the audio thread just can't keep up). When the
   watchdog detects a stall, it sets `isStalled = true` and clears
   it 2 s after the recovery seek. PlayerScreen + MiniPlayer treat
   `isBuffering || isStalled` as the loading-ring trigger.

2. **Snackbar feedback on stall.** Watchdog emits a one-shot via
   `transientMessages` at first detection: "Audio chain stalled at
   2.00× — recovering. Try a lower speed or disabling linear-phase
   EQ if this keeps happening." Throttled to once per 30 s so a
   chronic-stall cycle doesn't spam the user every recovery.

3. **Tighter detection.** Stall threshold dropped from 10 s → 6 s
   (the cycling-position bug exhibits ~5 s cycles, so 6 s catches
   the first complete cycle without false-positive on legitimate
   buffering). Poll period dropped from 2 s → 1 s so the indicator
   + snackbar appear within a second of the watchdog's decision.

`pushState()` updated to `_state.update { ... }` and to PRESERVE
`isStalled` across calls — without that, a routine
`onIsPlayingChanged` after a stall recovery would silently flip
`isStalled` back to its default mid-recovery.

ai_contamination: true # claude opus 4.7

## Text settings + bundled Garamonds (EB + Cormorant)

New Settings → Text & font screen. Substantive typography control:

**Body-font choice.** Six options:
- Theme default *(active theme's bodyFont — current behavior)*
- System sans (`FontFamily.Default`)
- System serif (`FontFamily.Serif`)
- System monospace (`FontFamily.Monospace`)
- **EB Garamond** *(bundled, OFL)*
- **Cormorant Garamond** *(bundled, OFL)*

Both Garamonds are variable TTFs from Google's `google/fonts` repo
(EBGaramond[wght].ttf ~830 KB, CormorantGaramond[wght].ttf ~1.2 MB).
Single file per family covers Regular through Bold via the `wght`
axis — Compose handles the variable-axis selection automatically.

When the user picks anything other than "Theme default," `Theme.kt`
folds the choice into Material's typography body slots
(titleSmall through bodyLarge + all labels). Display slots
(displayMedium / displayLarge / headlines) stay on the active
theme's `displayFont` so the visual character of each theme
direction (Cassette serif, Reel/Ticker monospace) survives.

**Notes-specific size sliders.**
- `notesTextSizeSp` (default 14, range 10–28) — applied to the body
  text on each `NoteCard` row.
- `notesPopupTextSizeSp` (default 16, range 10–28) — applied to the
  typing surface in `NoteEditorDialog`.

Two sliders because typing benefits from a larger size than reading
the resulting card. Both read in real time via `Settings`, so the
slider drag updates the live preview AND any open notes UI.

**Live preview.** Top of the screen renders a representative slice
(title + body line + label + faux note card + faux editor row)
in whatever's currently selected. No "apply" button — every change
propagates Settings → Theme → recomposition immediately.

**OFL compliance.** License files at `assets/EBGaramond-OFL.txt` +
`assets/CormorantGaramond-OFL.txt`. Per SIL OFL 1.1, that's the full
requirement — license must ship with the software, not be visible
in the UI. No "Fonts" attribution panel reintroduced; the user
removed that explicitly in v0.6.17 and the OFL doesn't need it.
**Commercial use is fine** — OFL only prohibits selling the font
as a font product, not bundling it in software.

ai_contamination: true # claude opus 4.7

## UI roundup: Tune icon, MyLists tab hearts, history filter, tab fullscreen

Five small UX cleanups shipped together:

**Audio Fine-tuning ↔ Now Playing icons split.** Both used `GraphicEq`,
which made the Player top-bar's "Audio Fine-tuning" button visually
collide with the "Now Playing" affordance in Catalog overflow + Player
top-left. Fine-tuning now uses `Tune` (slider knobs); Now Playing keeps
`GraphicEq` (pulsing-bar live-audio glyph) and stays consistent across
Catalog overflow and Player top-bar. Catalog overflow row also relabeled
"EQ & speed" → "Audio Fine-tuning" to match the page title.

**MyLists tabs no longer cut off.** "Excellent" and "Most-excellent"
text labels were long enough on phone widths that the strip needed to
scroll. Replaced text with the established heart vocabulary:
- Queue → "Queue (n)" *(unchanged)*
- Excellent → ♥ + "(n)"
- Most-excellent → ♥ + small pulsing-gold ♥ + "(n)" *(mirrors
  PlayerHeartIcon's tier-2 visual elsewhere)*
- Downloaded → "Downloaded" *(unchanged)*

The whole strip now fits without horizontal scroll on standard phone
widths.

**Playback history "All" filter chip got a leading icon.** Was
text-only while the per-reason chips had icon+text — broke the row's
visual rhythm and made the wider-counted Sessions chip look oddly
weighted. Added an `AutoMirrored.List` icon to All; uniform shape
across the row regardless of count digits.

**Player tabs: tap active tab → fullscreen.** Stylish move for
focused reading. Tapping the currently-selected tab on the Player
screen's bottom tabs (Notes / Details / Transcript) collapses the
upper Player UI (artwork, title, scrubber, transport) entirely and
gives the tab content the whole screen below the top bar. Tap the
active tab again to collapse back. Tap a different tab to switch
content (stays in fullscreen if currently fullscreen). Per-screen
state — resets when navigating away.

**Transcript tab hidden when no transcript.** Most podcasts don't
ship a transcript URL in their kabod metadata. Surfacing an empty
"Transcript" tab for the 90% case was clutter. The Transcript tab
is now driven by `episode_kabod.transcriptUrl` non-blank, falling
back to a 2-tab strip (Notes / Details) otherwise.

ai_contamination: true # claude opus 4.7

## EqScreen header links + watchdog visibility + drop the pixel font

Three small UX cleanups bundled:

**Audio Fine-tuning header.** Two right-justified text links at the
very top of the screen, above the master "Audio enhancement" toggle:
"Notes for audiophiles" first, "Audio diagnostics" below it. Reads as
ancillary navigation rather than primary controls. Both targets were
already reachable from Settings; this just adds a second on-ramp from
the screen audiophiles actually live on.

**Nested-parents map split.** `audioDiagnostics` and `audiophileNotes`
used to be pinned to "settings" as their back parent in
`NESTED_PARENTS`. With EqScreen now another entry point, that pin
would teleport the user past EqScreen on back when they arrived from
there. Both moved to plain `popBackStack()` so back returns to whoever
actually navigated in. `appDiagnostics` and `notes/{guid}` stay pinned
since they each still have one canonical parent.

**Stall watchdog visibility on the diagnostics screen.** Renderer-
stall events (the v0.6.16 watchdog firings) are now surfaced as their
own section between "Recent events" and "Startup" on Audio
diagnostics, with each entry showing time-ago + position + speed at
recovery. They're also included in the "Copy to clipboard" dump so a
bug report carries them inline. No more digging through Settings →
App diagnostics → Other to see whether the chain is healthy.

**Pixel font dropped.** The Press Start 2P bundle (TTF + OFL license
text + `PixelFont.kt` Composable + Settings → Fonts attribution
section) is gone. The user noted the attribution surface didn't
earn its UI footprint; the underlying motivation ("give a nod to
fonts we use") was the only reason it existed. Cassette theme's
display font is now `FontFamily.Serif` — system serif renders as a
clean Garamond-ish family on all Android versions, no binaries to
ship, no attribution to maintain. (Adobe Garamond Pro proper is a
paid Adobe typeface; bundling EB Garamond would have re-introduced
the same OFL footprint we just removed, so not worth it.)

**Phase mode latency / audiophile notes**: NOT affected by the recent
playback robustness work (v0.6.15 buffer bumps, v0.6.16 stall
watchdog). The ~6.4 ms minimum-phase / ~52 ms linear-phase chain
latency numbers are unchanged — those are EQ-chain timings, not
player buffer timings.

ai_contamination: true # claude opus 4.7

## Stall watchdog: auto-flush when the renderer cycles its last decoded buffer

User correction on the v0.6.15 buffer-bump theory: the episodes
exhibiting the 2x cycling-position bug were already downloaded —
playback was reading from local files via FileDataSource, so source-
side network buffer was never the constraint. The cycling 43:31..43:36
loop was a **renderer underrun**: the DSP chain (probably linear-phase
EQ + the rest of EqAudioProcessor) couldn't feed Sonic fast enough at
2x source consumption rate, the audio sink emptied, and ExoPlayer
recovered by replaying the last decoded ~5 s of buffer indefinitely
until something forced a flush.

The bigger source buffers from v0.6.15 don't fix this case (source is
already fully available). The DSP path needs a watchdog.

Add a stall watchdog to PlayerController:
- Started by `Player.Listener.onIsPlayingChanged` when isPlaying flips
  to true; stopped on the inverse.
- Polls `Player.currentPosition` every 2 s.
- Tracks the running max position. If max hasn't advanced in 10 s
  while the player still claims to be playing, force a recovery via
  `seekTo(currentPosition)` — that flushes the audio sink and re-syncs
  the renderer chain (the same recovery the user observed naturally).
- Logs each stall to `AppDiagnostics.recordOther("renderer_stall", ...)`
  with the playback speed at the time, so we can see how often this
  fires across builds. Frequent fires mean the DSP is the root issue
  and the watchdog is a band-aid.

10 s threshold chosen because the cycling cycles were ~5 s of decoded
buffer; by 10 s of no-max-advance we're confidently in stall
territory and not just buffering.

ai_contamination: true # claude opus 4.7

## Playback robustness: bigger buffers + snackbar feedback on stuck taps

Three user reports, one root cause + one UX gap:

1. **2x speed for ~7:30 → playback lost; position cycled 43:31→43:36
   then repeated until a flush reset it.** Renderer underrun loop —
   the source-side buffer drained, the audio sink kept playing the
   last decoded ~5s of buffer over and over until the renderer
   flushed and re-synced.

2. **1.75x for ~10 min → severe stuttering.** Same fingerprint, less
   severe — buffer drained more slowly so underruns were brief.

3. **Scrubbing far from the play head took 1-2 min before playback
   resumed; play button felt like a silent no-op.** Long HTTP
   Range fetch to fill the buffer at the new position, with no
   explicit user feedback that anything was happening.

Root cause for (1) and (2): `DefaultLoadControl` buffer durations are
in MEDIA TIME (the duration of audio held), not wall-clock. At 2x
playback, source is consumed 2x faster wall-clock, so a 60s media-time
buffer drains in 30s wall-clock. Any network slowdown longer than that
starves the renderer.

**Fix for (1) and (2)**: bump `DefaultLoadControl` buffer thresholds:
- `minBufferMs`: 60s → 180s
- `maxBufferMs`: 180s → 600s
- `bufferForPlaybackMs`: 2s → 5s
- `bufferForPlaybackAfterRebufferMs`: 4s → 8s

10 min media-time max gives 5 min wall-clock headroom at 2x.
Memory: ~19 MB at 256 kbps stereo — fine on any modern phone.

**Fix for (3)**: new `transientMessages: SharedFlow<String>` on
`PlayerController`. `togglePlay()` emits a one-shot string when the
tap hits a no-op-feeling state:
- Controller not yet bound → "Player isn't connected yet — try again."
- IDLE with no MediaItem → "No episode loaded — pick one from the
  catalog."
- BUFFERING (already trying) → "Buffering — waiting on the network."

`PlayerScreen` collects the SharedFlow in a `LaunchedEffect` and
surfaces each message via the existing `snackbarHostState`. The
buffering ring around the play button stays as the primary visual
"I see your tap" signal; the snackbar is the second signal for cases
where the ring isn't obviously connected to the user's action.

Also: yes, all three were related — (1) and (2) are different
intensities of the same underflow, and (3) is the same network-bound
slowness expressed during a seek. Larger buffer reduces (1)/(2) and
shortens (3) in cases where the seek target falls within an already-
loaded range.

ai_contamination: true # claude opus 4.7

## EqScreen: thumb-only band sliders + "Audio Fine-tuning" title

User report: scrolling up/down the EQ screen kept catching slider tracks
and producing accidental band tweaks. Root cause: Material3's `Slider`
listens for taps + drags across the entire track, so any vertical scroll
that crossed a band slider got hijacked into a horizontal drag of that
band.

Fix: replace the band-row `Slider` with a custom `BandSlider` that only
listens to drags starting on the thumb circle itself. The track is now
purely decorative — touches on the bare track propagate to the parent
verticalScroll Column, so the page scrolls cleanly even when the
finger lands on a slider line. Drag mechanics on the thumb are
preserved (cumulative-delta from drag start, snapped to the same 23
discrete steps over the [-12 dB, +12 dB] range, accent color matches
the override-state tint).

Visuals: full-width inactive track + a center tick at the 0 dB home
position (so the user can see how far each band is shaped relative to
flat) + an active track segment from the center to the thumb in the
accent color. Slightly more minimal than Material3's slider — no
ripple, no thumb scale animation — which suits the dense 10-band
graphic-EQ row better.

Trade-offs: tap-to-jump on the track is gone (the very behavior we
were fixing). Keyboard / accessibility nav is gone (we're touch-only).
The volume-boost slider above still uses the standard Material3
Slider since it's a single isolated control where tap-to-jump is
useful and accidental drag isn't a problem.

Bonus: TopBar title renamed from "Audio" to "Audio Fine-tuning" so
the screen's purpose reads more clearly from the navigation bar.

ai_contamination: true # claude opus 4.7

## EqScreen: bottom Hold-to-A/B mirror under the band sliders

The existing Hold-to-A/B button sits up top (under the master "Audio
enhancement" toggle). Once the user scrolls down to the graphic EQ
band sliders to dial them in, the A/B button is two screens of scroll
away — wrong tool placement for the natural "tweak band, A/B, tweak
again" workflow.

Add a second `HoldToBypassButton` instance directly below the band
sliders, identical semantics: hold bypasses the entire `EqAudioProcessor`
chain (DC blocker → biquad/FIR EQ → master gain → 2x oversample →
look-ahead limiter → dither → int16 truncation), release restores.
Same disabled state when the master toggle is off, same haptic + the
same telemetry events.

ai_contamination: true # claude opus 4.7

## EQ reshape: podcast owns the EQ; episode override is the one branch point

Course-correction on v0.6.11. The right model:

- **No global EQ.** Each podcast owns its own tuning, full stop.
- **Episode inherits from its podcast** by default.
- **One-off episode override** is the only per-episode knob — branches
  off the podcast's tuning for that single episode.
- **No "disable EQ" toggles.** Disabling = setting bands to flat. The
  master "Audio enhancement" toggle still gates the whole DSP chain
  globally; per-podcast or per-episode disable was redundant noise.

Resolution chain in `PlayerController.applyEqOverrideFor`:
`episode_state.eqBandsCsvOverride` → `podcast_state.eqBandsCsvOverride`
→ `EqPresets.FLAT`. The first non-null wins. Enabled state is purely
the master toggle.

EqScreen reshape:
- "For this podcast" section + its disable + its override toggle: **gone**.
- "Disable EQ for this episode" toggle: **gone**.
- One toggle remains: "Use a one-off EQ for this episode" (new copy
  reflects inheritance: "Branches off this podcast's EQ for this
  episode only. Toggle off to re-inherit the podcast's tuning.").
- Slider routing: when the override toggle is on, edits write to
  `episode_state.eqBandsCsvOverride` for the current guid. When off,
  edits write to `podcast_state.eqBandsCsvOverride` for the current
  feedUrl — i.e. the slider IS the podcast's tuning interface. With
  no episode loaded, edits are transient (live processor only, no
  persistence).

Schema: no migration. The existing `podcast_state.eqBandsCsvOverride`
column is repurposed as the podcast's primary EQ store; the
`eqDisabled` columns on both `episode_state` and `podcast_state` are
marked deprecated and unread (kept on the schema for backup
round-trip compat). The `episode_state.eqBandsCsvOverride` column,
briefly deprecated in v0.6.11, is now back as the override layer.

ai_contamination: true # claude opus 4.7

## EQ override moves from per-episode to per-podcast

User feedback: "EQ for an episode is supposed to be applied to all
episodes WITHIN that same podcast." The implementation was per-episode
(`episode_state.eqDisabled`, `episode_state.eqBandsCsvOverride`), so a
tweak made on one episode never propagated to the rest of that
podcast's catalog. Fixed by lifting the override up to the podcast
level.

Schema:
- New columns on `podcast_state`: `eqDisabled` (default 0),
  `eqBandsCsvOverride` (nullable TEXT).
- Migration v16 → v17: ADD COLUMN both, then backfill from
  `episode_state` — for each feedUrl whose episodes had any non-default
  EQ override, ensure a `podcast_state` row exists and copy the
  most-recently-played episode's override values onto it. The
  `episode_state` columns stay (SQLite ALTER doesn't drop columns
  cleanly without table recreation) but are now marked deprecated and
  unread.

Plumbing:
- `PlayerController.applyEqOverrideFor(guid)` looks up the episode's
  feedUrl, then reads `podcast_state` for the effective enable/bands.
  Same single-source-of-truth invariant as before.
- `PodcastStateDao` gains `ensureRow`, `setEqDisabled`, and
  `setEqBandsCsvOverride` helpers. UI calls `ensureRow` before
  setters so the row exists for podcasts the user hasn't otherwise
  customized.
- `EqScreen`: section relabeled "For this podcast." Toggles read
  `currentFeedUrl` (derived from the playing episode's row) and write
  to `podcast_state`. Sliders persist to `podcast_state.eqBandsCsvOverride`
  when the override toggle is on. Override-color tint logic unchanged
  — visual reminder still shows when shaping a non-global preset.
- `Backup.kt`: `podcastState` JSON entries now carry `eqDisabled` and
  `eqBandsCsvOverride` so per-podcast EQ round-trips through backup +
  restore. The legacy per-episode fields are still serialized for
  archival but unused on restore.

Behavior change: the user's existing per-episode override gets promoted
to the podcast level on first launch of v0.6.11. From then on every
episode of that podcast plays through the override.

ai_contamination: true # claude opus 4.7

## Nav cleanup: back from miniplayer-launched player goes to natural parent

User report: navigating catalog -> episodes -> player -> settings ->
[miniplayer launches player] -> settings -> [miniplayer launches player]
left a literal-history back stack — each system-back walked back through
every visited screen. Expected: back from a player launched via the
miniplayer should go to episodes (the screen the user originally
navigated into player from), not retrace the whole spiral.

Fix: new `navigateToPlayerCleanly(nav)` helper in `MainActivity`. Before
pushing player, walks the back stack to find the most recent
"player flavor" entry (`player`, `player/preview/*`, `player/transcript/*`)
and pops up to (and excluding) the route immediately below it. End
state is `[..., naturalParent, player]` regardless of how many
player <-> settings cycles preceded the click.

Wired into the three "shortcut to player" entry points:
- Miniplayer tap
- Catalog's "Now Playing" link
- System media notification's `ACTION_OPEN_PLAYER` intent

NOT wired into the "explicit play" paths (tap an episode in
EpisodesScreen / MyListsScreen / SearchScreen) — those plays establish
a new natural parent and should push fresh.

Edge: no player has ever been in the stack this session (cold-start
with audio resumed but player never opened). Helper falls through to
a plain `navigate("player")`, accepting that back goes to whatever
the user was on. Reasonable since there's no "natural parent" to
infer.

ai_contamination: true # claude opus 4.7

## v0.6.9 — Ditch Media3's offline framework; OkHttp downloader from scratch

After v0.6.5–v0.6.8 each fixed a different Media3 download-stack quirk
(paused-by-default DownloadManager, deferred-fire missing the play-and-
listen-straight-through case, listener that doesn't fire on byte
progress) and downloads were *still* hit/miss, the user called it: ditch
Media3's offline framework entirely. v0.6.9 does that.

**What's gone:**
- `Downloads.kt` (Media3 DownloadManager wrapper) — deleted.
- `LofiPodDownloadService` (.kt + manifest entry + dataSync FGS comment) —
  deleted.
- `SimpleCache` + `StandaloneDatabaseProvider` + `CacheDataSource` from
  `DownloadHolder` — gone. The streaming-cache that used to wrap HTTP
  requests is gone too; re-streaming on a back-scrub during streaming
  is acceptable (rare for podcasts).
- Cache contention between the player and downloader — gone (only one
  thing reads/writes audio bytes now).

**What's new:**
- `LofiDownload` (data class) — app's own state model: `{ guid, state,
  bytesDownloaded, contentLength, filePath, errorMessage }` with a
  4-state enum `{ QUEUED, DOWNLOADING, COMPLETED, FAILED }`.
- `LofiPodDownloader` — pure OkHttp + coroutines. Per-GUID file at
  `filesDir/episode_audio/<sha256(guid)>.bin`. Concurrency capped at 2
  via Semaphore. HTTP Range resume. `ensureActive()` cancellation
  contract so a cancelled download never gets marked completed on
  partial bytes. Direct `MutableStateFlow<Map<String, LofiDownload>>`
  emissions on every progress tick (500 ms) — no listener whose
  fan-out we have to second-guess.
- `LofiDownloadEntity` + `LofiDownloadDao` — Room v15 → v16 migration
  adds `lofi_download` table for persistence.
- `DownloadHolder` — drastically simplified. Holds the shared OkHttp
  client + a `DefaultDataSource.Factory` that auto-routes `file://`
  → FileDataSource and `http(s)://` → OkHttpDataSource. That's it.
- `PlayerController.playEpisode` — when constructing the MediaItem,
  prefers `app.downloadsApi.completedFile(guid)` (file URI) over the
  remote audioUrl when a local copy exists. Offline playback now
  literally reads from disk.

**Migration path:**
- One-shot legacy cleanup in `LofiPodApp.onCreate` deletes the old
  `filesDir/downloads/` SimpleCache directory + Media3's
  `exoplayer_internal.db`. Reclaims disk that was holding broken cache
  chunks.
- The `auto_download` table stays (its schema is downloader-agnostic).
  The new downloader's `LofiDownloadEntity` is fresh — no rows from the
  old Media3 db carry over.

**Hydrate behavior:**
- On app start, any `lofi_download` row left in DOWNLOADING / QUEUED
  state from a prior session (process killed mid-download) gets
  presented as FAILED with an "Interrupted on app exit — tap to resume."
  message. The user retries from the row UI; that calls `start(ep)`
  which seeks the partial file with a Range header and resumes from
  the saved byte offset.

ai_contamination: true # claude opus 4.7

## Fix: download progress poll — UI sees real-time bytes, not frozen 0%

User on v0.6.7: "downloads still do not work. Manual download appears
downloaded only after backing out and back in. Auto-download — can't
see the state change. All downloads appear to be infinitely in progress."

Root cause: Media3's `DownloadManager.Listener.onDownloadChanged` fires
only on STATE transitions (QUEUED → DOWNLOADING → COMPLETED / FAILED).
It does NOT fire during DOWNLOADING for byte/percentage progress
updates — Media3 documents this as "clients must poll for granular
progress." Our `Downloads.kt` was wired purely off the listener, so
the StateFlow emitted once at QUEUED→DOWNLOADING (typically with 0%
bytes downloaded) and then went silent until COMPLETED. The progress
arc on the DownloadButton therefore looked frozen at 0% for the whole
download. The "have to back out and come back" symptom was the user
forcing a fresh composition that re-read the now-COMPLETED state.

Fix: a 500 ms self-pacing poll loop in `Downloads.ensureProgressPolling()`
that re-emits `byId` from `manager.currentDownloads` while any
download is in DOWNLOADING / QUEUED / RESTARTING, and stops when
none are. Self-starts on `start()`, on `refreshAll()`, and on any
listener callback (defensive). The same poll cadence picks up state
transitions within 500 ms regardless of whether the listener's
fan-out is delayed by Compose snapshot scheduling or Media3 handler
throttling — so it's both a progress source and a redundancy net.

Cost: ~2 reads/sec of the in-memory `currentDownloads` list while
active, fully idle otherwise. Map equality uses reference equality
on Download values (Media3 has no equals override), so any
progress-field difference yields a structurally-different map and
triggers Compose recomposition end-to-end.

ai_contamination: true # claude opus 4.7

## Fix: APK update integrity — temp-file + atomic rename + ZIP magic check

User reported "app will not download. 'there's a problem with the app
file'" when installing v0.6.6 via the in-app updater. The release
artifact on GitHub was verified well-formed: 57.4 MB, valid ZIP magic
(`PK\x03\x04`), APK Signing Block v2/v3 present. So the upstream APK
was fine.

Root cause: `UpdateChecker.downloadApk` wrote the response body
directly to `cacheDir/updates/lofipod-<code>.apk` and on retry reused
any cached file with `length() > 0L`. A network drop mid-stream left
a partial APK in cache; subsequent retries reused the partial file
without revalidating, and the system installer surfaced "There's a
problem with the app file" indefinitely. Cache hit-the-wrong-thing.

Fix:
- Download to `<file>.tmp`, atomic-rename to final on success.
- Validate downloaded length against `Content-Length` header; short
  read → delete + retry on next call.
- Validate ZIP magic (`PK\x03\x04`) on both the freshly-written tmp
  and any reused cached file. Catches HTML-error-page-saved-as-APK.
- Wipe leftover `.tmp` at function entry so an interrupted prior run
  can't leak across sessions.
- Cross-filesystem rename failure falls back to copy+delete, preserving
  the all-or-nothing consumer contract.

The user's stuck v0.6.6 cache is at `lofipod-40.apk`; v0.6.7 uses
cache key `lofipod-41.apk`, so it's a clean download path. From v0.6.7
forward, every update is integrity-checked end-to-end.

ai_contamination: true # claude opus 4.7

## Fix: auto-download fires immediately at play time, not deferred

User report: manual downloads work after the v0.6.5 `resumeDownloads()`
fix, but auto-download for the currently-playing episode never fires
and "some downloads are hit/miss." Root cause was the v0.5.9 deferred-
fire design: `playEpisode` would write an `auto_download` row but NOT
call `addDownload` until the user transitioned away (track switch or
`STATE_ENDED`). The original justification was "avoid simultaneous HTTP
fetches that cause spinner-forever-no-progress" — but that symptom was
actually `downloadsPaused = true` (the v0.6.5 fix). The deferred design
was a workaround for the wrong diagnosis, and it broke the most common
listening pattern: play one episode and listen straight through.

That's also the "hit/miss" pattern: episodes the user happened to skip
or transition off of got auto-downloaded; episodes they listened all
the way through (or paused on indefinitely) did not.

`DownloadManager` and `CacheDataSource` already share the same
`SimpleCache`, so firing `addDownload` immediately doesn't double-fetch
overlapping byte ranges — they coordinate through cache spans.

Fix: in `PlayerController.playEpisode`, when the episode has no current
Download (or the prior one is STATE_FAILED), insert the auto_download
row AND call `app.downloadsApi.start(ep)` inline. The existing
`fireDeferredAutoDownload` calls on STATE_ENDED + track-out remain as
no-op safety nets (addDownload is idempotent against existing requests),
and the orphan-sweep on connect handles legacy rows from before this
fix. STATE_FAILED treatment is new: a previously-failed auto-download
gets a fresh attempt on replay rather than silently staying broken.

ai_contamination: true # claude opus 4.7

## Fix: downloads stuck in STATE_QUEUED — call `resumeDownloads()` at startup

Root cause of the long-standing "downloads don't actually download" bug
that survived v0.5.6 (bypass DownloadService.sendAddDownload), v0.5.x
(deferred auto-download), and v0.6.4 (remove the `<service>` declaration
entirely). Per Media3 1.4.1's `DownloadManager.java` javadoc: "Normally
a download manager should be accessed via a `DownloadService`. When a
download manager is used directly instead, **downloads will be initially
paused and so must be resumed by calling `resumeDownloads()`**." The
field `downloadsPaused = true` by default in the constructor; we never
flipped it. Every `addDownload()` we issued landed in STATE_QUEUED and
stayed there because `canStartDownloads()` gates on `!downloadsPaused`.

Fix: one-line `resumeDownloads()` call right after the DownloadManager
is constructed in `DownloadHolder`. The flip persists across the
manager's lifetime — subsequent `addDownload()` calls auto-start once
`Requirements.NETWORK` is satisfied (which the manager's internal
`RequirementsWatcher` tracks regardless of whether a `DownloadService`
is alive). No service re-introduction needed, so the
`ForegroundServiceStartNotAllowedException` crash that drove the v0.6.4
manifest change stays gone.

ai_contamination: true # claude opus 4.7

## DSP polish: 128-tap oversampler FIR + Kaiser-windowed linear-phase kernel

Two pure-quality bumps to the audiophile chain. No behavior change beyond
cleaner magnitude response — pre-existing toggles, presets, latency budget,
diagnostics screen all still apply.

**Oversampler FIR 64 -> 128 taps.** Tightens the anti-imaging /
anti-aliasing transition band from ~18-26 kHz to ~20-24 kHz at 44.1k. The
old design started rolling off at ~18 kHz, intruding into the audible band
for the most sensitive listeners; the new one keeps the response flat to
~0.001 dB ripple all the way to 20 kHz. Stopband attenuation stays at
~90 dB (Kaiser β=9). Total chain latency ticks up from ~5.7 ms to ~6.4 ms;
still inaudible. CPU cost is ~11 M extra MACs/sec at 44.1k stereo —
negligible on any modern phone, especially after the v0.5.1 limiter
deque optimization that took ~50% off chain CPU.

**Linear-phase kernel: Kaiser window the truncation.** The biquad-cascade
magnitude response is sampled at 8192 frequency points, IFFT'd, and
truncated to 4096 taps. Without windowing, that's a rectangular truncation
= sinc convolution in frequency = small ripple in the magnitude response.
Now multiplied by a Kaiser window (β=6) on the truncated taps, which gives
~60 dB of ripple suppression in exchange for mild softening of high-Q peak
edges. The window is precomputed once at class load (4096 doubles cached on
the companion) and applied per band-change, so the cost is one
KERNEL_LENGTH-sized multiply.

Latency / spec text propagated everywhere the old numbers appeared:
`AudiophileNotesScreen`, `EqScreen`, `EqAudioProcessor`, `LinearPhaseEq`,
`Settings`, `PlaybackService`. The audio diagnostics screen reads
`firTaps` from telemetry so it picks up the new value automatically.

ai_contamination: true # claude opus 4.7

## v0.6.4 — XML rescue + chapter-level Bible index + AppDiagnostics screen

Five fixes addressing user-reported issues from the v0.6.3 logs.

**Tolerant XML retry on bare ampersands.** CCM's two Sunday-service
feeds emit `<title>Q&A on Romans</title>`-style content without
`&amp;` escaping; strict XmlPullParser dies with "unterminated entity
ref." `RssParser` now reads the whole stream up front, parses
strictly, and on failure retries with a sanitized copy where bare
`&` are replaced with `&amp;`. Negative-lookahead in the regex skips
already-escaped entities (`&amp;`, `&#39;`, `&#x27;`) so we don't
double-encode. Doesn't fix unclosed tags / mismatched quotes — those
re-throw and surface as feed failures. Rescue events get a row in
`AppDiagnostics` so it's visible the feed needed help.

**Chapter-level Bible index.** Per user feedback that verse-level
was too sparse to navigate (most RSS auto-tagging only nails
chapter precision), the verse-grid step was dropped. Flow is now
Book grid -> Chapter grid -> Sermons-for-chapter. Chapter-level
matches what the tagger can reliably extract and what users want
to scrub through.

**Coverage-bug fix.** Books were rendering highlighted in the grid
even when no underlying row had a chapter — tapping the book
showed an empty chapter grid. Root cause: `loadCoverage` was
incrementing `bookCounts` for every row in `episode_scripture`,
including rows where `startCh` was null (e.g., a Kabod entry with
`scriptureBook` set but no chapter, or a degenerate regex hit).
Fixed: a row only counts toward the book if it has a non-null
`startCh`. No more "highlighted but empty" books.

**First-50-words description scan.** ScriptureTagger.detect now
slices the description to its first 50 whitespace-separated tokens
(after stripping HTML-ish `<...>` runs) before regex scanning. A
sermon's description usually opens with a single explicit citation
("In this sermon, Pastor expounds Romans 8:28-30...") and then
drifts into broader themes that incidentally mention many verses.
Scanning the whole description was picking up incidental mentions
and yielding wrong primary tags.

**LofiPodDownloadService removed from manifest.** v0.5.6 stopped
routing through the service (DownloadManager.addDownload directly).
But Media3's internals can still bind a registered service from
cached download intents on app start, which on Android 12+/14+/15
crashes with ForegroundServiceStartNotAllowedException — visible in
recent Pixel logs even though our code doesn't invoke the service.
Solution: comment out the `<service>` declaration + the
`FOREGROUND_SERVICE_DATA_SYNC` permission. With no manifest entry,
the OS can't bind. The .kt file stays per EFFICIENCY_REVIEW notes
as a hatch for future reactivation with a proper Scheduler + UIDT.

**New AppDiagnostics infrastructure + screen.** Sister to
StartupTimings (which tracks timing); this one captures errors and
notable events across subsystems. Categories: feed failures, feed
rescues (tolerant XML pass needed), download failures (both
synchronous throws from `addDownload`/`removeDownload` and the
async STATE_FAILED listener path), scripture-tag skips, generic
"other." Bounded ring buffer per category (50 entries) so a noisy
subsystem can't push out signal from quiet ones. New
`AppDiagnosticsScreen` lists entries by category newest-first,
copy-to-clipboard per category. Settings -> "App diagnostics
(bugs)" entry routes here. Lets a user with a misbehaving build
capture concrete data rather than "it didn't work."

ai_contamination: true # claude opus 4.7

## v0.6.0 — Canonical Bible index + scripture-aware smart-queue

The categorical level-up: the app is no longer just "podcasts organized
by feed" — it's a personal sermon archive navigable by Scripture. The
podcast feeds become source material; the Bible canon is the navigation
primitive.

**Bible canon data.** New `com.lofipod.app.bible.BibleCanon` — 66-book
Protestant canon, KJV versification, Logos-style 10-group canonical
categorization (Pentateuch / Historical / Wisdom / Major Prophets /
Minor Prophets / Gospels / Acts / Pauline / General Epistles /
Revelation), each book with chapter and per-chapter verse counts plus
common abbreviation aliases. Drives the verse grid's gray-out behaviour
and the ScriptureTagger's detection regex.

**Auto-detection from RSS title + description.** New
`com.lofipod.app.bible.ScriptureTagger` builds a precompiled regex from
all canonical names + aliases. Anchored: book name MUST be followed by
a chapter digit, so "John Piper" doesn't match the gospel-John
("John 3:16" or "(2 John 1:5)" do). Confidence scoring (0..100) ranks
title-vs-description hits and chapter-only-vs-chapter+verse precision.
BBC's pattern of putting the passage in `<description>` rather than
`<title>` is handled — title is preferred, description is a fallback.

**Storage (v14 → v15 migration).** New `episode_scripture` table
mirrors the Kabod schema's `scriptureRef` shape: `(guid, book, startCh,
startV, endCh, endV, source, confidence)`. Single uniform query path
across Kabod-imported and RSS-tagged refs. New `EpisodeScriptureDao`
exposes `coveringVerse`, `nextInCanon`, `coveredChaptersIn`,
`coveredVersesIn`, `forBook`, etc.

**ScriptureIndexer + warm-tag pass.** `ScriptureIndexer` owns
population: `backfillFromKabod` copies authoritative Kabod-pack refs
on every app start (idempotent); `tagPodcast` runs the RSS tagger
after each successful fetch via the new `PodcastRepository.afterFetchHook`.
A startup warm-tag sweep walks the disk-hydrated cache so users with
existing data get a populated Bible index without needing to refresh.
Kabod-sourced rows are never overwritten by regex guesses.

**Logos-style canon-browse UI.** New `CanonBrowseScreen` —
hierarchical: book grid (4-col, color-coded by group, gray when no
sermons) → chapter grid (6-col, gray when no sermons) → verse grid
(8-col, gray when no sermons) → sermons-for-verse list with
"Play through from here" + "Just this" affordances. Reachable from
the Catalog overflow menu ("Bible index").

**Smart canon-order autoplay.** New `Settings.canonAutoplayEnabled`
flag. When set, end-of-stream advances to the *next sermon in canon
order* across all feeds (resolver: `EpisodeScriptureDao.nextInCanon`)
instead of the queue/feed-next chain. End-of-book auto-clears the flag.
"Play through from here" sets the flag; "Just this" clears it. Falls
through to standard advance for episodes without a scripture ref.

**Source filter for the Bible index.** New
`Settings.canonBrowseExcludedFeeds` (CSV-stored Set<String>). Tune-icon
button on the canon-browse top bar opens a dialog of all canon sources;
unchecking hides a feed from the grids without removing it from the
catalog. Excluded feeds DO NOT apply to canon-order autoplay (the user
explicitly opted into a series; respecting browse-time exclusions
mid-series would silently skip sermons).

**pubDate parser fix.** `RssParser.parsePubDate` now handles ISO 8601
with `Z` UTC suffix (Megaphone, Castos) and millisecond-precision
variants. The `Z` is normalized to `+00:00` before the format pass.
Also added bare `yyyy-MM-dd` for kabod-pack-style sources. Episodes
with previously-unparseable dates now sort correctly in the canon view.

Color tokens for the canonical groups are inspired by traditional
Christian publishing — earth/sand for the Pentateuch, royal blue for
the Major Prophets, scarlet for the Gospels, Pentecost yellow for
Acts, violet for the Pauline epistles, deep purple for Revelation —
without cloning Logos directly.

ai_contamination: true # claude opus 4.7

## Feed loading: disk cache + per-feed timing + concurrency cap

User report: "loading feeds is impossibly slow on Pixel 7. Pixel 8
does not seem to have the same issue." 16 sources × parallel HTTP
fetches + RSS parsing = a multi-second blank-Catalog stall on every
cold start, exacerbated on hardware/network configurations weaker
than the dev's. Three changes here.

**Disk cache for parsed feeds.** New `FeedDiskCache` writes each
successfully-fetched `Podcast` as JSON under `<filesDir>/feeds/`
(filename = SHA-256 of feedUrl). On cold start, `PodcastRepository`
hydrates the in-memory cache from these files in milliseconds —
the Catalog renders immediately with stale-but-valid data while
the network refresh runs in the background. JSON encoding is hand-
rolled with `org.json` (already used by Backup.kt; no new deps).
Schema-versioned so a future model change can ignore old files
without crashing.

**Stale-while-revalidate in CatalogViewModel.** `loadCanon` now
ALWAYS shows whatever the cache has (in-memory + disk-hydrated)
immediately, then triggers a network refresh. Even partial cache
coverage shows what we have with `loading=true` for the missing
sources, instead of a blank screen. On network failure, the cached
content stays visible with the error surfaced inline.

**Concurrency cap on parallel fetches.** `MAX_CONCURRENT_FETCHES = 8`
via `Semaphore.withPermit` around each `fetchOne`. 16+ simultaneous
TLS handshakes + RSS parses were starving slower devices; 8 keeps
most of the parallelism while reducing contention.

**Per-feed timing diagnostics.** Each `fetchOne` records to
`StartupTimings` as `feed_<host>` (e.g., `feed_feeds_megaphone_fm`,
`feed_ccmodesto_com`). Plus `feed_disk_cache_init` and
`feed_disk_cache_hydrate` for the cache-side timing. Surfaced in
the existing Startup section of the diagnostics screen so the user
can capture which specific feed dominates on Pixel 7 vs Pixel 8.

ai_contamination: true # claude opus 4.7

## Startup diagnostics + beep ducking redesign + defer auto-download until track-out

Three related fixes shipped together.

**Startup-phase diagnostics.** New `StartupTimings` (in
`com.lofipod.app.diagnostics`) records `(name, startNs, endNs)` tuples
for key initialization steps: `repo_init`, `db_get`, the per-version
Room migrations (`migration_1_to_2` … `migration_13_to_14`),
`download_db_provider`, `simple_cache_init`, `download_manager_init`,
`downloads_warmup`, `playback_service_oncreate`,
`media_controller_connect`, `kabod_install_bundled`,
`kabod_loader_init`, `transcripts_init`, `application_oncreate`. Each
phase shows duration in ms and offset-from-process-start. Surfaced as
a "Startup" section in the Audio diagnostics screen, also included
in the Copy-to-clipboard dump. The slow-cold-start report flow is now:
user opens diagnostics → screenshots the Startup section → dev sees
which phase ate the time. Room migrations are individually wrapped
via a `timed(Migration)` helper so any future hop's cost shows up
without per-migration plumbing.

**BeepPlayer ducking redesign.** Old: `player.volume = 0f` before the
beep, restore after. The MediaController → session → ExoPlayer.volume
path is supposed to be synchronous but was leaving the podcast
audible during the beep on device — so the beep was effectively
mixed with the podcast at full volume, which the user perceived as
"too loud." New: `player.pause()` before the beep, `player.play()`
after if the user was playing. Pause is bulletproof (stops feeding
the audio sink within one buffer; no rebuffer on resume since the
sink picks up where it left off). Bookkeeping uses `wasPlaying` to
avoid auto-resuming if the user had already paused. New
`BEEP_TRACK_VOLUME = 0.5f` constant via `AudioTrack.setVolume` adds
an additional runtime tuning knob independent of the pre-rendered
`sustainPeak`. Effective beep level = `sustainPeak * BEEP_TRACK_VOLUME`
at full system volume. With the ducking now actually working, the
calibration baseline changes — set `sustainPeak` to 0.3 (× 0.5
volume = 0.15 effective), can tune either constant later.

**Defer auto-download until the user moves on.** Old: `playEpisode`
fired `DownloadManager.addDownload(request)` immediately for the
now-playing episode. The DownloadManager opened a second HTTP
connection to the same audio URL while ExoPlayer was streaming via
its own connection — many podcast hosts rate-limit or stall the
second connection, producing the user-reported "spinner spins
forever, no progress" symptom. New: `playEpisode` only INSERTS the
`auto_download` row; the actual `addDownload` is deferred until the
user transitions away (track change in `playEpisode`'s outgoing
block, or `STATE_ENDED` in the player listener). By then ExoPlayer's
CacheDataSource has filled the SimpleCache as part of streaming, so
DownloadManager's worker sees the cached spans and completes
near-instantly without re-fetching. New helper
`fireDeferredAutoDownload(guid)` does the work; new orphan sweep
`fireDeferredAutoDownloadOrphans()` runs on `connect()` to pick up
deferred rows from prior sessions where the app was closed before
the transition fired. Manual download trigger paths
(EpisodesScreen / PlayerScreen buttons,
`startDownloadForCurrent`) are unchanged — user explicitly asking
for a download still fires `addDownload` immediately.

UX side effect: during playback, the now-playing episode's download
button shows the "Download" icon (no Download object yet). The
"downloaded" checkmark appears shortly after the user transitions
away — typically within seconds since the cache is mostly full
already. Easier to live with than the broken "spinner forever"
behaviour and avoids double-fetch.

ai_contamination: true # claude opus 4.7

## Cold-start: defer download infra to first access; add 32h unfinished-auto sweep

User report: "the app loads very slow on some devices to the point that
playback is not able to start" — reproducible on two users' devices,
but not on the dev's. The dev's storage is faster + has less
downloaded data, so the cold-start cost on slower eMMC was hidden.

Root cause: `LofiPodApp.onCreate` was constructing `DownloadHolder`
synchronously, and `SimpleCache`'s constructor synchronously scans
the entire download directory (Media3 documents this as slow). For
users with many downloaded episodes, this scan + the synchronous
`Downloads.refreshAll` cursor walk over the download index can cost
seconds — and it blocked Application.onCreate, which delays MainActivity's
first-frame render.

**Fix**:
- `LofiPodApp.downloads` and `downloadsApi` are now `by lazy {}`
  properties. `onCreate` launches a background coroutine that touches
  `downloadsApi.byId.value` to warm them up off-thread, in parallel
  with MainActivity init. UI shell renders immediately; the slow disk
  I/O runs alongside without blocking.
- `Downloads.init.refreshAll()` now runs on `cleanupScope` instead
  of inline, so even if construction does happen on the main thread
  for some reason, the cursor walk doesn't block it.

Worst-case behaviour (slow user, immediate playback request): if a
UI thread / PlaybackService accesses `downloads` before the warmup
finishes, that access blocks on the lazy. Same total wait as before,
but the UI is already visible so the user perceives "starting
playback..." rather than "app frozen at launch."

## Auto-downloads also expire after 32 h if unfinished

Per user follow-up: extend the auto-expiration rule to catch
auto-downloads the user lost interest in. New
`AutoDownloadDao.expiringUnfinishedGuids(cutoffMs)` query — selects
auto_download rows where the episode was NOT played to completion
AND `max(auto_download.createdAt, episode_state.lastPlayedMillis)`
is older than `cutoffMs`. The `max()` clock catches both
"never started" (lastPlayedMillis = 0, clock = createdAt) and
"started but abandoned" (clock = last play tick).

`PlayerController.sweepExpiredAutoDownloads` now runs both queries
and de-dupes the results before iterating. New constant
`AUTO_DOWNLOAD_UNFINISHED_TTL_MS = 32 h`. Rename of the existing
`AUTO_DOWNLOAD_TTL_MS` to `AUTO_DOWNLOAD_FINISHED_TTL_MS` for clarity.

ai_contamination: true # claude opus 4.7

## Auto-downloads expire 1h after the episode finishes playing

User-triggered downloads should stick around forever; auto-downloads
fired by `playEpisode` should free up disk shortly after the user is
done with them. New `auto_download` table tracks which downloads
were auto-fired vs user-triggered.

**Schema.** New `auto_download` Room entity (guid PK, createdAt). v13
→ v14 migration adds the table. Empty on first migration — every
existing download is treated as manual (kept until the user removes
it explicitly).

**Auto path.** `playEpisode` upserts into `auto_download` when it
fires a download. Re-listening to a finished episode that's still
auto-flagged renews the timestamp so the 1-hour clock resets.
Re-listening to a manually-downloaded episode does NOT silently
convert it to auto.

**Manual paths.** Every UI download trigger (EpisodesScreen + PlayerScreen
buttons, PlayerController.startDownloadForCurrent) deletes the
`auto_download` row alongside calling `Downloads.start`, so a user
pressing the download button converts an auto download to manual.
`Downloads.remove` clears the row internally so removal callers don't
have to remember the cleanup.

**Sweep.** New `PlayerController.sweepExpiredAutoDownloads` runs:
- on `connect()` (catches stragglers from prior sessions)
- at the start of every `playEpisode` (housekeeping on each track switch)

The query inner-joins `auto_download` against `episode_state`,
returning guids where `durationMs > 0 AND positionMs >= durationMs - 5000
AND lastPlayedMillis < (now - 1h)`. For each match, the manager's
download is removed and the auto_download row is cleared. Episodes
that were started but never finished stay (the rule is
"finished + idle 1h," not "started + idle 1h"). Manual downloads
(no auto_download row) are never eligible.

**Constant.** `PlayerController.AUTO_DOWNLOAD_TTL_MS = 60 * 60 * 1000`.
One knob to retune the expiration window.

## Efficiency Review file (gitignored)

New `EFFICIENCY_REVIEW.md` at repo root. Personal scratch tracking
dormant / partially-wired / abandoned pieces of the build. Gitignored
alongside `SCREEN_MAP.md` and `KABOD_SCHEMA.md`. First entry
documents `LofiPodDownloadService` as dormant since v0.5.6 — kept on
disk + in the manifest as a hatch in case we want a separate download
foreground notification later, but no code path invokes it.

## Fix auto-download hangs by bypassing DownloadService.sendAddDownload

User-reported: auto-download for the now-playing episode hangs and
generally doesn't work. Root cause: `DownloadService.sendAddDownload`
triggers a foreground service start. On Android 12+ this can throw
`ForegroundServiceStartNotAllowedException` from background-restricted
states; on Android 15+ the `dataSync` foreground-service-type has a
hard daily timeout (~6 h cumulative) that kills the service
mid-download, leaving downloads silently stuck. Even though
`playEpisode` runs while the PlaybackService (mediaPlayback type)
keeps the app process in the foreground, starting a SECOND foreground
service (the dataSync one) is what runs into the restriction.

References: androidx/media#2614 (Android 15 dataSync timeout),
#1239 (background sendAddDownload throws), #831 (downloads stuck on
force-close).

Fix: `Downloads.start()` and `Downloads.remove()` now call
`DownloadManager.addDownload(request)` and `removeDownload(guid)`
directly, bypassing `DownloadService.sendAddDownload` /
`sendRemoveDownload`. The DownloadManager's executor pool (configured
in DownloadHolder with 2 worker threads) runs downloads in-process;
the existing PlaybackService keeps the process alive during active
playback, which is when auto-download is most likely to fire.

Trade-off: no dedicated foreground notification for downloads — the
PlaybackService's media notification covers the visible artifact when
audio is playing, and download progress is already shown inline in
the EQ/episodes screens. If the user backgrounds the app and stops
playback, the process eventually dies and downloads pause; they
auto-resume when the user reopens the app (DownloadManager re-reads
the index and continues).

`LofiPodDownloadService` is now dormant (still registered in the
manifest as a hatch for re-introducing foreground download
notifications later if needed) but no code path invokes it.

ai_contamination: true # claude opus 4.7

## Quality-of-life: cold-start episode restore + scroll-to-now-playing + quieter beeps

Three small but high-value UX improvements.

**Beep volume — drop to 0.2 amplitude.** v0.5.3 dropped from 0.85 to
0.5; user reported still too hot. Cut further to 0.2 (~-14 dB from
original, ~-8 dB from previous). Should now sit ~10 dB below the
limited podcast peak — a notification chime rather than an alarm.

**Cold-start episode restore.** New `EpisodeStateDao.mostRecentlyPlayed()`
query + new `PlayerController.restoreLastEpisodeIfNeeded()` helper.
Called from the post-connect block in `connect()` after a 900 ms
settle (past the existing 100/300/800 ms pushState window). Loads the
last-played episode at its saved position WITHOUT auto-playing — so
reopening the app shows the mini-player + Player screen with "where I
left off" ready to resume on tap. No-op if the session already has an
item (warm reconnect) or if no episode has ever been played. Skips
the side effects of a real `playEpisode` (no autoplay timer, no
auto-download, no feed-visit upsert) since this is a passive restore.

**Scroll-to-now-playing on the episodes screen.** Added a
`rememberLazyListState` + one-shot `LaunchedEffect` that animates the
list to the current episode's position when both the list and the
current-guid are settled. One-shot per screen instance (subsequent
manual scrolls own the viewport). No-op if the current episode isn't
in this feed's visible list (different feed, or archived and the
filter excludes it).

ai_contamination: true # claude opus 4.7

## DSP Phase C: linear-phase EQ via FFT overlap-add convolution

Optional linear-phase EQ mode that preserves transient waveform shape
exactly, at the cost of ~46 ms additional latency. Default stays
minimum-phase (the existing biquad cascade); users opt in via the new
chip row on the Audio screen.

**JTransforms dependency.** BSD-2-Clause, pure JVM, no JNI. Pulls
JLargeArrays as a transitive (also BSD-2). Added under a new "DSP"
section in `app/build.gradle.kts`.

**Kernel synthesis (`LinearPhaseEq.synthesizeKernelSync`).** Sample the
biquad cascade's magnitude response at 8192 frequency points, set
phase to zero, IFFT to get an acausal symmetric impulse response,
circular-shift by FFT_SIZE/2 to make it causal, truncate to 4096 taps
centered on the peak, FFT to get the convolution-time spectrum.
Synthesis runs on `Dispatchers.Default` via a per-instance
`workerScope`; rapid slider drags cancel any in-flight synth so only
the latest band set actually computes. The audio thread sees a single
`@Volatile` reference swap when a new kernel is ready — no locks, no
torn reads.

**Overlap-add convolution (`LinearPhaseEq.processChunk`).** 1024-sample
input chunks zero-padded to FFT_SIZE=8192, FFT'd, complex-multiplied
against the kernel spectrum (full 4-multiply per bin to handle the
linear-phase imaginary parts), IFFT'd. First 1024 samples of the
result mix with the saved overlap-tail; the next 4095 samples become
the new tail. Per-channel `DoubleFFT_1D` instances + workspaces; no
allocation in the hot loop.

**Integration with `EqAudioProcessor`.** New `phaseModeLinear`
@Volatile flag + `setPhaseModeLinear(on)` setter. New
`queueInputLinearPhase(...)` helper called from `queueInput` when the
flag is set; the post-gain chain (oversampler ↔ limiter ↔
downsampler ↔ dither ↔ truncate) is shared between modes. EOS drain
extended with a linear-phase pre-stage that pushes 3 chunks of zeros
into `linearPhaseEq` to flush the accumulator + group delay before
running the standard post-gain drain. `onFlush` resets the
linearPhaseEq state; `onReset` releases its worker scope. Exiting
passthrough in linear mode resets the linearPhaseEq output queue too,
so the Hold-to-A/B button doesn't emit ~50 ms of stale pre-bypass
audio on release.

**UI.** New chip row on the Audio screen between the DC blocker
toggle and the Hold-to-A/B button: `[Minimum] [Linear]`.
Material3 `FilterChip` with selected-state coloring. Toggling writes
to both `Settings.setPhaseModeLinear` and `eq.setPhaseModeLinear`,
so the change is both persisted and live without a track transition.

**Settings rehydration.** `PlaybackService.onCreate` now reads
`settings.phaseModeLinear.first()` and applies it to `sharedEq` so
the user's mode preference survives a process restart.

**Audiophile notes screen.** New "Phase modes (Minimum / Linear)"
section between "Parametric EQ" and "Cross-fade on band changes".
Updated the LICENSES section: linear-phase EQ + JTransforms is no
longer "planned," it's shipping.

Known limitations:
- No cross-fade between modes — switching mid-playback has a brief
  (< 50 ms) audible artifact at the transition. Acceptable for
  manual mode switches; could be smoothed with a parallel-output
  cross-fade later.
- Linear-phase truncation uses a rectangular window. High-Q bands
  could in principle show audible ripple from the truncation; should
  be inaudible at the default Q (~1.41) but worth verifying. Adding
  a Kaiser window is a one-line change if it's a problem.

ai_contamination: true # claude opus 4.7

## Autoplay beep: drop volume from 0.85 -> 0.5 amplitude

User feedback: confirmation beeps read too hot vs. typical podcast
loudness. Cut the synthesized-tone amplitude from 0.85 to 0.5 in
`BeepPlayer.synthesizePiezoTone` — about a 41% reduction in linear
peak, ~-4.6 dB. The square-wave character + 2.7 kHz fundamental are
unchanged, so the piezo "alarm clock" timbre still cuts through
speech; it just doesn't startle.

ai_contamination: true # claude opus 4.7

## DSP Phase B2: Press-and-hold A/B bypass button on the EQ screen

Reframed B2 from "another bit-perfect bypass toggle" (which would have
been a pure rename of the existing Audio enhancement toggle) into a
genuine new affordance: a press-and-hold button that instantly
bypasses the entire DSP chain while held, then restores it on release.
The audiophile A/B workflow ("hold, listen, release, listen") maps
directly to a momentary button — toggles persist across releases and
the user can lose track of which way is which after a few flips.

**Behaviour.** `pointerInput` + `detectTapGestures.onPress { ... }`. On
press: `eq.setEnabled(false)` + haptic feedback + telemetry event. The
suspending `onPress` lambda calls `awaitRelease()` inside try/finally,
so the restore (`eq.setEnabled(true)`) runs on both clean release AND
gesture cancel (drag-off, parent recompose). No DataStore writes — pure
transient state, so a forgotten release can't strand the chain in
bypass.

**Why not Modifier.combinedClickable.onLongClick.** That requires
holding for ~500 ms before firing — wrong for our use case, where we
want INSTANT bypass on press-down. detectTapGestures is the correct
primitive for momentary "while held" affordances.

**Disabled state.** When the chain is already off (master toggle off,
or per-episode "Disable EQ" override on), pressing the button would be
a no-op (passthrough → passthrough). Grayed out with explanatory label
"Hold to A/B (chain already off)" so the affordance stays visible on
revisit.

Also documented in `AudiophileNotesScreen` under "Verifying the chain
is live" — the by-ear method now sits alongside the by-telemetry
method.

ai_contamination: true # claude opus 4.7

## DSP Phase B4: Notes for audiophiles screen

New static reference screen at route `audiophileNotes` (sibling to
`audioDiagnostics` under Settings). Documents the chain design: signal
flow, Float64 rationale, DC blocker, biquad EQ + cross-fade, master
gain, 2x oversampling, look-ahead limiter (LA window, soft knee,
linked stereo, monotonic-deque peak detector), TPDF dither + truncation,
total latency math (~5.7 ms), CPU footprint, an explicit "what this
chain does NOT do" list (no exciter, no widening, no spatializer), and
license attribution (all original code; algorithmic credits only —
RBJ cookbook, Lipshitz/Wannamaker/Vanderkooy 1992, Vaidyanathan,
Kaiser+Schafer 1980; planned JTransforms is BSD-2; no GPL).

Sister page to the Audio diagnostics screen — diagnostics shows the
LIVE state, this page documents the DESIGN. Body wrapped in
`SelectionContainer` so spec values are copy-paste-able. Chain spec
strings live as file-level `private const val`s grouped at the bottom
of the file so the wording can be edited without touching the layout.

Wired up the navigation: new `NESTED_PARENTS` entry mapping the route
back to `settings`, new `composable("audiophileNotes")` block in
`AppNav`, new `onOpenAudiophileNotes` param on `SettingsScreen`, new
`TextButton` link below the audio-diagnostics link in the Settings
"Audio diagnostics" section.

ai_contamination: true # claude opus 4.7

## DSP Phase B: DC blocker UI toggle + live level meters in Audio screen

First slice of Phase B work surfacing the audiophile chain in the EQ
screen. Two tightly-scoped additions, both in `EqScreen.kt`.

**B1 — DC blocker toggle.** New `Switch` row directly under the master
"Audio enhancement" toggle, mirroring its visual pattern. Wired to
`Settings.setDcBlockerEnabled` (persists across restart) AND
`PlaybackService.sharedEq.setDcBlockerEnabled` (takes effect immediately
without waiting for a track transition). Subtitle wording: "Removes DC
offset from poorly-encoded sources before the EQ amplifies it. Default
off." The processor's `isPassthroughEffective()` already returns false
when DC blocker is on, so the toggle isn't a no-op for FLAT users — it
forces the DSP path so the blocker actually runs.

**B3 — Level meters.** New "Levels" section below the master gain slider:
three small horizontal bar meters (IN / OUT / GR) reading the
existing `@Volatile` telemetry fields (`inputPeak`, `outputPeak`,
`reductionDb`). 250 ms `LaunchedEffect` polling tick, same pattern as
the Audio diagnostics screen. Per-meter mapping: PEAK uses the primary
accent and maps [-60, 0] dBFS → [0, 1] fill; GR uses the error color
and maps [0, -20] dB → [0, 1] fill so active limiting reads as a
visible alarm. Floor at -60 dBFS via a 1e-6 amplitude threshold so the
"-inf" case stays readable. Decay is already half-life'd at 500 ms in
the audio thread, so 250 ms sampling integrates cleanly. When the chain
is in passthrough (master off, or FLAT + 0 dB + DC blocker off), the
audio thread doesn't update these fields — bars freeze at zero post-
decay, which is the right "no signal" UX.

Phase B2 (bit-perfect bypass) and B4 (audiophile-notes page) still
pending; Phase C unchanged.

ai_contamination: true # claude opus 4.7

## Limiter window-max via monotonic deque + per-buffer processing-time telemetry

Symptom that triggered this: with audio enhancement on and the phone in
a pocket, BT-headphone playback developed intermittent artifacts that
disappeared the moment the phone came out of the pocket — and that the
audio enhancement off case never reproduced. Strong fingerprint of
thermal / power-saver CPU throttling pushing the audio thread past its
buffer deadline.

**Limiter peak detector: brute-force scan -> monotonic deque.** The
sliding-window max in `Limiter.processFrame` was scanning the whole
[lookAheadSamples]-long peak window every frame (~440 compares per
frame at LA=5ms × 88.2k oversampled rate, ~39M ops/sec). Replaced with
a monotonic deque (head -> tail non-increasing), so peak retrieval is
O(1) amortized. Bit-exact same windowed max as the prior version — no
audible change to the signal — but ~8× cheaper for the limiter alone
and ~50% off the total chain CPU at 44.1k stereo. This is the headroom
that lets us survive a throttled core.

**Per-buffer timing telemetry.** New `recordBufferTiming(processingNs,
audioNs)` on `AudioChainTelemetry`, called once per DSP buffer from
`EqAudioProcessor.queueInput`. Tracks last/avg/p95/max processing time
plus avg/max load factor (= processing/audio-time) over a rolling
ring of the last 64 buffers. Surfaced on the Audio diagnostics screen
under a new "Performance" section. Load factor near 0% = healthy
headroom; approaching 100% = audio thread is saturated and underruns
are imminent. The Performance section is the diagnostic surface for
"is the chain still keeping up while the phone is in my pocket?"

ai_contamination: true # claude opus 4.7

## Topical back-stack + autoplay direction + per-episode EQ override + piezo beeps + audio-diagnostics help

Bundle of UX work that touches navigation, the EQ surfacing of per-episode
controls, and the autoplay-confirmation beep character.

**Smart back navigation.** System back gesture and every top-bar back button
now route through a single `smartBack(nav, currentRoute)` helper in
`MainActivity`:
  - **Primary chain** (`catalog` → `episodes/{feed}` → `player` /
    `player/preview/{guid}`): pop normally. Catalog is the practical top —
    back from it exits the app.
  - **Nested secondaries**: `audioDiagnostics` → `settings`,
    `notes/{guid}` → `notesBrowser`. Back lands on the parent, not the
    most recent primary, so the natural drill-down hierarchy stays intact.
  - **Flat secondaries** (`settings`, `mylists`, `eq`, `metrics`,
    `history`, `notesBrowser`, `search`, `transcript`): back walks past
    any intermediate secondaries and lands on the most recent primary
    in the back stack. Stops the historical "I went to settings, then
    mylists, then back lands on settings instead of player" walk.
  Implemented via `BackHandler` at the `AppNav` level (only enabled on
  non-primary routes — primary's default popBackStack is already correct
  and intercepting on catalog would swallow the activity-finish back).

**Autoplay direction toggle.** `Settings.autoplayDirectionUp` (default
`true`) controls which way the feed-fallback autoplay walks the episode
list when the queue is empty. Up = newer episodes (closer to today, the
existing pre-toggle behaviour); down = older episodes (chronological
backlog walk). `PlayerController.advanceToNextInQueue` now picks the
*adjacent* unplayed episode in the chosen direction relative to the
finished episode's pubDate, instead of the absolute newest. Stops at the
end of the list rather than wrapping.

In `EpisodesScreen`, each episode card got a compact direction indicator
(arrow up / arrow down, tinted primary). Tap toggles the global setting
and shows a Snackbar ("Autoplay: next newer episode (up the list)" /
"...next older episode (down the list)") for ~3 s. To make room, share
+ archive moved into the per-row overflow menu — the visible row is now
download / queue / direction / overflow / heart.

**Player overflow share.** `PlayerScreen`'s top-bar overflow menu
(previously: Playback history → Settings) now has a Share entry slotted
between the two. Resolves the audio URL via the cached feed for the
current episode and hands off to the standard enclosure share intent.
Disabled when no episode is loaded.

**Per-episode EQ override.** "Disable EQ for this episode" moved off the
Player Details tab and onto the EQ screen, alongside a new "Use a one-off
EQ for this episode" toggle. When override is on:
  - Slider movements save to `episode_state.eqBandsCsvOverride` (new
    nullable column added in DB migration 12 → 13) instead of the global
    `Settings.eqBandsCsv`. Master volume boost stays global on purpose
    so perceived loudness doesn't change when comparing override vs
    default.
  - The band sliders, master gain slider, and the override toggle itself
    re-tint to `MaterialTheme.colorScheme.tertiary` so the user has a
    persistent visual reminder that they're shaping a per-episode preset.
  - `PlayerController.applyEqOverrideFor` reads the override on track
    transitions and pushes the right bands into the live EQ; falls back
    to global Settings bands when the override is null.

EQ screen also gained an explanatory line under Audio enhancement
clarifying that EQ + master gain are global, with the per-episode
toggles below for one-off shaping.

**Piezo autoplay beeps.** `BeepPlayer` rewritten to render its own
square-wave-with-soft-edges PCM via `AudioTrack` instead of using
`ToneGenerator`'s mid-frequency `TONE_PROP_BEEP`. New tone is 2.7 kHz
(piezo-buzzer territory — well above voice fundamentals and sibilance,
cuts through podcast playback the way a kitchen-timer alarm does),
sustained for 500 ms per strike with 500 ms between strikes. 5 ms
linear attack/release ramps avoid click artifacts at start/stop; a
small sine blend on each square half-cycle's leading edge keeps the
buzz character without scraping your ears off.

**Audio diagnostics help section.** `AudioDiagnosticsScreen` got a
collapsible "What do these mean?" card at the top of the scroll. One
tap expands a single text block with one-line definitions for every
field on the screen, grouped by section. Long-press tooltips would have
fought with the existing `SelectionContainer`, so a single help card
sits above it instead.

ai_contamination: true # claude opus 4.7

## Audio diagnostics: live readouts + breadcrumb event log + dedicated screen

Settings → Audio diagnostics now has a full-screen view with:

- **Chain spec** — input format, FIR taps per stage, look-ahead window in
  samples + ms, brick-wall threshold, total chain latency, DC-blocker on/off,
  master enable. One-shot at configure; verifies the chain is wired the way
  the code claims.
- **Live readouts** — decayed peak meter at chain input (post-EQ, post-gain,
  pre-upsample) and at chain output (post-downsample, pre-truncate), limiter
  gain reduction in dB, and state flags (passthrough / cross-fade in flight /
  TPDF dither active / DC blocker on). Polled every 250 ms.
- **Counters** — configures, flushes, cross-fades, band changes, EOS drains,
  passthrough vs DSP buffer hits with %, total frames processed. Lets you
  spot pathological churn (e.g. cross-fades firing every buffer = upstream
  setBands bug).
- **Player state + last error** — same source as the inline panel that lived
  in Settings; kept here because audio diagnosis usually needs both chain and
  player state in one view.
- **Recent events** — last 50 chain events with timestamps: configure / flush
  / cross-fade / passthrough toggle / EOS drain / DC blocker on/off / format
  change. Breadcrumb log for "what happened just before things sounded weird?"
- **Copy-to-clipboard** — dumps the whole readout as plain text for pasting
  into bug reports. Counters/events also resettable.

Implementation: new `AudioChainTelemetry` singleton holds @Volatile readouts
for the audio thread to write cheaply, AtomicInteger/Long counters, and a
fixed-capacity ring buffer for events guarded by a small mutex (event
logging is cold-path only — never per-frame, so contention is negligible).
`EqAudioProcessor` writes into telemetry on every configure/flush/preset
change and updates peak readouts + GR + flags inside the per-frame loop
(no allocations, just @Volatile writes). New `AudioDiagnosticsScreen`
renders the snapshot; `SettingsScreen` keeps its inline mini-panel and
adds an "Open full audio diagnostics" button. Nav route
`audioDiagnostics` added.

ai_contamination: true # claude opus 4.7

## Oversampler cutoff fix — EQ presets audible again

v0.4.9 shipped the Phase A audiophile chain with the oversampler's
anti-image FIR cutoff incorrectly set to 0.5 (the filter's Nyquist
itself), which means the filter wasn't filtering at all. Symptom on
device: every EQ preset sounded the same and "jumbled" — the alias
copy of the upsampled signal was surviving through the limiter and
folding back into the audible band, masking whatever the EQ was doing.

Fix: set `Oversampler.FIR_CUTOFF` to 0.25, which is the correct
normalized cutoff for a 2x oversampler — half of the filter's Nyquist,
exactly at the boundary between the original signal band and the image
band introduced by zero-stuffing. With this, the FIR properly attenuates
the imaging artifacts and the chain becomes transparent for in-band
audio.

Also corrected the docstring's claimed transition width (was off by
~3.5×) and clarified the cutoff semantics in inline comments. No other
behavior change; the rest of the Phase A chain (Float64 DSP, DC blocker,
biquad cross-fade, look-ahead limiter, gated TPDF dither) is unchanged
and was correct.

ai_contamination: true # claude opus 4.7

## Audiophile-grade audio chain — Phase A (Float64, DC blocker, cross-fade, look-ahead limiter, 2x oversampling, gated dither)

Foundational rebuild of the EQ audio chain for mastering-grade processing.
The signal flow is now:

```
int16 PCM in
  -> Float64                          (zero-error int -> double conversion)
  -> [DC blocker]                     (toggleable, off by default)
  -> per-channel biquad chain         (parallel cross-fade on band change)
  -> master gain
  -> 2x polyphase upsample            (anti-imaging FIR)
  -> look-ahead brick-wall limiter    (linked-stereo, ~5 ms LA, soft knee, runs at 2x)
  -> 2x polyphase downsample          (anti-aliasing FIR)
  -> [TPDF dither when limiter engaged]
  -> int16 PCM out
```

**What changed and why.**

- **Float64 throughout.** The biquads at 31/62 Hz were numerically marginal
  in Float32 — RBJ cookbook math multiplies quantities that cancel to ~1e-7
  of full magnitude, and Float32 has only ~7 decimal digits of precision.
  Float64 has ~16 and the precision problem vanishes. CPU cost on modern
  ARM is identical for scalar Double vs Float; pure precision win, no perf
  hit. `Biquad.kt` rewritten end-to-end; denormal flush at 1e-15 prevents
  ARM FPU's slow path during long silences.

- **DC blocker (optional, off by default).** New `DcBlocker.kt` — single-pole
  HPF at ~5 Hz to remove DC bias from poorly-encoded sources before the EQ
  amplifies it and steals limiter headroom. Per-channel x_prev/y_prev state.
  Cheap (one comparison + flush-to-zero); rehydrated from Settings on
  service start. Still needs UI toggle (Phase B1).

- **Parallel-filter cross-fade on band change.** When an EQ band changes,
  the previous coefficients + state are snapshotted into a parallel "old"
  filter chain that runs alongside the live chain for 2048 samples (~46 ms
  at 44.1k). Outputs are mixed via half-cosine equal-power weights
  (zero derivative at both ends — inaudibly smooth). No coefficient
  interpolation: poles can briefly leave the unit circle during interp =
  momentary instability = pop. Two stable filters in parallel + mixing is
  bulletproof. ~2x CPU during the fade only; zero rest of the time.

- **Look-ahead brick-wall limiter** (`Limiter.kt`). Replaces the tanh
  waveshaper that was generating odd-order harmonics on every loud sample.
  ~5 ms LA window with brute-force windowed-max peak detection (linked
  stereo: max(|L|,|R|) per frame, applied to both channels so the stereo
  image stays put). 3 dB quadratic soft knee centered on -1 dBFS; one-pole
  envelope follower with 1 ms attack / 50 ms release.
  `lastReductionDb` exposed for dither gating + a future GR meter.

- **2x oversampling around the limiter** (`Oversampler.kt`). The biquad EQ
  is linear and doesn't need oversampling, but the limiter is time-varying
  gain (multiplication = convolution in frequency domain = spectrum
  spreading), so it can produce aliasing at the 16-bit Nyquist. Running it
  at 2x sample rate pushes the alias products above the original Nyquist
  where the downsample FIR removes them. 64-tap Kaiser-windowed sinc FIR
  (β=9 → ~90 dB stopband, ~0.05 transition width). Polyphase up (even/odd
  phase split, ×2 amplitude scaling) and polyphase-style down (single
  delay line at 2x rate, full FIR scan once per 1x output).

- **TPDF dither, gated.** `Dither.kt` — sum-of-two-uniforms triangular
  noise at ±1 LSB peak before the int16 truncation. Decorrelates
  quantization error from the signal — converts harmonic distortion into
  broadband noise floor (~-90 dBFS RMS for 16-bit), which sounds like
  analog hiss instead of digital crunch. Applied ONLY when
  `limiter.lastReductionDb < 0.0` so we don't add noise to bit-passthrough
  output (a noise-floor regression vs the FLAT fast-path).

- **End-of-stream drain.** Media3's `BaseAudioProcessor.queueEndOfStream`
  is final; the override hook is `protected onQueueEndOfStream`. Ours
  pushes zero 1x frames through the entire post-gain chain (oversampler ↔
  limiter ↔ oversampler) for `oversampler.totalDelayFrames1x +
  limiter.drainFrameCount/2` ≈ 251 frames at 1x ≈ 5.7 ms. Without this,
  the last bit of every track gets silently eaten as buffered audio sits
  in the chain when Media3 stops calling `queueInput`.

**Latency.** ~5.7 ms total chain delay (5 ms limiter LA + ~0.7 ms FIR
group delay across both oversampling stages). Below the audible threshold
for casual listening; well below the perceptual A/V sync threshold for
podcast playback.

**CPU footprint.** A few percent of one core for stereo at 44.1 kHz. Most
of the cost is in the oversampler (~22M MAC/sec) and the limiter's
windowed-max scan (~9.7M ops/sec). Both negligible on any modern phone.

**Licensing.** All original code, no third-party DSP libraries (yet —
JTransforms arrives in Phase C for linear-phase mode). Apache 2.0 / BSD /
MIT only; no GPL, no patent encumbrance.

**Files affected.**
- `app/src/main/java/com/lofipod/app/audio/Biquad.kt` (rewritten)
- `app/src/main/java/com/lofipod/app/audio/EqAudioProcessor.kt` (modified)
- `app/src/main/java/com/lofipod/app/audio/DcBlocker.kt` (new)
- `app/src/main/java/com/lofipod/app/audio/Dither.kt` (new)
- `app/src/main/java/com/lofipod/app/audio/Limiter.kt` (new)
- `app/src/main/java/com/lofipod/app/audio/Oversampler.kt` (new)
- `app/src/main/java/com/lofipod/app/data/Settings.kt` (new `dcBlockerEnabled` key)
- `app/src/main/java/com/lofipod/app/player/PlaybackService.kt` (rehydrate dcBlocker on service start)

Phases B (UI toggles + meters + audiophile-notes page) and C
(linear-phase convolution mode) still pending.

ai_contamination: true # claude opus 4.7

## Audio enhancement no-playback fix + diagnostics panel

The bug that's been around since pre-v0.4.0 is finally pinned: with the
default Audio enhancement = ON and Skip silence = Off, the play button
would visibly cycle play → pause → play and audio never started. Logcat
showed `ERROR_CODE_FAILED_RUNTIME_CHECK (IllegalArgumentException)` on
the first decoder buffer. Workaround the user discovered: bump
Skip-silence to L1 (or toggle Audio enhancement off) and playback would
work.

**Root cause.** Both `EqAudioProcessor` and `SilenceSkippingProcessor`
extend Media3's `BaseAudioProcessor` and used the bulk
`out.put(inputBuffer)` ByteBuffer-to-ByteBuffer transfer in their
respective passthrough fast-paths (EQ when FLAT/disabled,
SilenceSkipping when level=0). The two processors sit back-to-back in
the audio chain — `[eq, skipSilence] → built-in skip-silence (disabled)
→ Sonic`. With BOTH custom processors taking the bulk-put path on the
same buffer, Media3 1.4.1's `DefaultAudioSink` tripped a runtime check
on the first buffer and the player wedged out before audio reached
AudioTrack. As soon as either processor's chain entry switched to its
DSP path (per-sample reads + writes), the bug went away — which is why
"set Skip-silence to L1" was the workaround.

**Fix.** Both processors' passthrough paths now use per-short writes
(`while (src.hasRemaining()) out.putShort(src.short)`) instead of
`out.put(inputBuffer)`. Functionally identical (output is a verbatim
copy of input), but the chain doesn't trip whatever runtime check the
bulk path was hitting. The DSP paths are unchanged. Net cost: one
short read + one short write per sample on top of what was previously a
bulk memcpy — at 44.1kHz stereo that's ~88k ops/sec, negligible.

`EqAudioProcessor.queueInput` also reordered: passthrough check moved
ABOVE the `frameCount = remaining / (2 * channelCount)` line. The old
order would div-by-zero on `channelCount = 0` (processor not yet
configured) before reaching the safety check inside the if.

**Audio diagnostics panel.** Settings → Audio diagnostics now shows
selectable plain-text dump of:
  - Live EQ state: `audio_enhancement`, `master_gain_db`, `bands_db`
    (10 values), `skip_silence_lvl` — read straight from the running
    `PlaybackService.sharedEq` / `sharedSkipSilence` so it reflects what
    the audio thread actually sees.
  - Live player state: state, current episode title, guid, speed.
  - Last error verbose: full unclipped `ERROR_CODE_*` + cause class +
    cause message — same string we've been logging to logcat under
    `LofiPodPlayer`, now reachable from the device without adb. Reset
    on the next healthy state transition (BUFFERING/READY).
  - "Reset audio to defaults" button: writes audio_enhancement=on,
    gain=0, bands=FLAT, skip_silence=off back into Settings AND pushes
    them into the live processors. Lets a user recover from a wedged
    EQ configuration without hunting through the EQ screen.

`PlayerController` now also stores `lastErrorVerbose` alongside the
existing clipped `lastError`. The chip on PlayerScreen still uses the
clipped form (one-line constraint); the diagnostics panel shows the
verbose form.

`SettingsScreen` signature changed: now takes a `PlayerController`
parameter (passed through from `MainActivity`) so the diagnostics panel
can read live player state.

ai_contamination: true # claude opus 4.7

## Autoplay confirmation timer — phases 3 + 4 (countdown UI + BT intercept)

Last two slices of the autoplay-confirmation feature. The play button on
both surfaces now visibly morphs into a countdown from T=60s onward, and
Bluetooth / vehicle / system-notification play-pause presses confirm
continuation instead of toggling pause during the window.

**Phase 3 — visible countdown.** New
`com.lofipod.app.ui.screens.AutoplayCountdownUi` exports
`rememberAutoplayCountdown(timer)` and `AutoplayCountdownContent`. The
remember helper ticks at 250ms (fast enough that the digital `M:SS`
doesn't visibly skip seconds, slow enough for negligible recomposition
cost) and returns null both before T=60s and when no timer is active —
callers gate on null/non-null to flip between the regular Pause/Play
icon and the countdown morph. Below the surface it computes
`remaining = (started + total) - now` and `progress = remaining /
visible_window` (where `visible_window = total - first_beep_ms = 130s`).

`PlayerScreen` consumes the helper inside the existing transport-row
play button: when the countdown info is non-null, the FilledIconButton
keeps its 88dp tonal background but shows a `Spacer(48dp)` instead of
the Pause icon, and `AutoplayCountdownContent(ringSize = 88dp)` overlays
a drainable progress ring + `Text("M:SS")` in the center. Buffering
ring is suppressed during the countdown so the two indicators don't
stack. Tap routes through the same `controller.togglePlay()` already
wired — phase 1 made that short-circuit to `confirmAutoplayContinuation`
when the timer is active and the player is playing, so no separate
confirm wiring is needed at the UI layer.

`MiniPlayer` mirrors the same morph at smaller scale: ring 40dp,
labelMedium typography, same Spacer-instead-of-icon swap. Subscribes to
`controller.autoplayTimer` directly (no mirror state).

**Phase 4 — BT / vehicle / notification intercept.** New
`com.lofipod.app.player.AutoplayConfirmBridge` is a process-wide
singleton that lets the service-side `MediaSession.Callback` reach the
activity-side `PlayerController` without crossing an IPC boundary
(both run in the same JVM). PlayerController binds itself in `connect`
and unbinds in `release` (reference-equality guarded so a fast activity
recreate doesn't null out the new pin from the old controller's late
release). The bridge reads timer state directly from the bound
controller's StateFlow rather than mirroring it, which would otherwise
race a collector-driven mirror against the timer body's auto-pause
clear.

`PlaybackService.MediaSession.Builder` now passes a custom
`MediaSession.Callback` whose `onPlayerCommandRequest` intercepts
`Player.COMMAND_PLAY_PAUSE`: if the bridge confirms the timer is active,
it runs `confirmAutoplayContinuation` and returns
`SessionResult.RESULT_INFO_SKIPPED` to deny the play/pause command —
audio keeps rolling, the countdown disappears, the next press goes
through normally. Non-play-pause commands and any play/pause arriving
when no timer is active fall straight through to
`SessionResult.RESULT_SUCCESS`.

To keep our own auto-pause from being intercepted as if it were a remote
button press, the timer body now `compareAndSet`-clears the snapshot
*before* calling `cc.pause()` at expiry. The bridge sees timer=null on
the inbound command and lets it through.

Combined effect: a Bluetooth play/pause press during the autoplay
window confirms continuation just like tapping the on-screen
countdown — same single-tap UX whether the user's hand is on the phone
or on their headphones.

## Autoplay confirmation timer — phase 2 (beeps + ducking)

Audible strikes now land on the autoplay-confirmation timer. New
`com.lofipod.app.audio.BeepPlayer` wraps `ToneGenerator(STREAM_MUSIC)`
and ducks the player's output to zero for the duration of each tone —
GPS-style — restoring the prior volume in a `finally` so a cancellation
or a torn-down MediaController can never leave the player muted. Both
the read of `player.volume` and the writes are wrapped in `runCatching`
so a controller release mid-flight degrades into a missed restore
rather than a crash.

`PlayerController.maybeStartAutoplayTimer` swapped its single 190s
`delay` for an absolute schedule against `SystemClock.elapsedRealtime`:

  - T=60s  → 1 ducked beep (~200ms)
  - T=120s → 2 ducked beeps, 333ms apart (~733ms total)
  - T=180s → 3 ducked beeps (~1.27s total)
  - T=190s → auto-pause check (unchanged from phase 1)

Absolute targets — not chained relative delays — keep the strike marks
pinned at the exact second values from autoplay start; a chained
`delay(60_000)` four times would slide the third strike late by however
long the prior beep sequences took.

Cancellation safety: the whole body is wrapped in try/finally; the
finally releases the BeepPlayer (which releases the ToneGenerator) and
clears `_autoplayTimer`. The clear uses `compareAndSet(mySnapshot, null)`
rather than a direct write, so an old timer's late finally never
clobbers the snapshot of a newer timer that already replaced it
(re-arm race when the user starts a fresh autoplay before the old
coroutine's cancellation has propagated through the dispatcher).

Degraded path: if `ToneGenerator`'s ctor throws — a few low-end OEM
ROMs ship without the proprietary-beep tone bank — `BeepPlayer` logs a
warning and runs silent. The timer keeps its full schedule and still
auto-pauses at 3:10; the user just doesn't get audible cues until the
phase-3 visible countdown lands.

## Autoplay confirmation timer — phase 1 (skeleton + auto-pause)

First slice of the autoplay-confirmation feature. Each autoplay-induced
episode (queue-next or feed-next) now arms a 3:10 timer; if the user
doesn't confirm, the episode auto-pauses. No beeps and no UI yet — phases
2 and 3. The visible behavior of this phase is just the auto-pause +
the new Settings toggle.

**`PlayerController` changes.** A new `@Volatile lastPlayWasAutoplay`
flag is set inside `advanceToNextInQueue` immediately before each
`playEpisode(...)` call (queue-next path and feed-next path). `playEpisode`
consumes + clears the flag at entry — so a stale flag from a long-since-
bypassed autoplay can't latch onto a later manual play — and, if it was
set, calls `maybeStartAutoplayTimer(ep.guid)` after `play()`.

The timer body delays for `AUTOPLAY_CONFIRM_TOTAL_MS` (190_000) and then
auto-pauses if the player is still on the same episode and still playing.
Guid identity guards against a manual play swapping the loaded episode
mid-window. State is exposed as a `StateFlow<AutoplayTimerState?>` for
phase-3 UI to consume; the UI computes the remaining window itself by
subtracting `SystemClock.elapsedRealtime` from `startedAtElapsedMs` so
the controller doesn't have to emit a per-frame tick.

Cancel paths: `confirmAutoplayContinuation()` (idempotent, public —
called by phase-3 UI taps and the phase-4 BT intercept), explicit
`pause()` (user is awake; auto-pause is redundant), `togglePlay` while
the timer is active and the player is playing (treated as confirm —
the morphed countdown button invites the tap), the start of any
subsequent `playEpisode` (new episode = new timer arming or a clean
manual play), and `release()`.

Constants for all four phase markers (`AUTOPLAY_CONFIRM_FIRST_BEEP_MS`
= 60_000, `_SECOND_` = 120_000, `_THIRD_` = 180_000, `_TOTAL_` = 190_000)
live in the companion object so phases 2 and 3 can consume them without
re-deriving the timing.

**`Settings` changes.** New `autoplayConfirmEnabled` flag, default true,
keyed `"autoplay_confirm_enabled"`. `SettingsScreen` exposes it as a
SwitchRow under Playback, right after "Auto-play next in feed", with
copy describing the 1/2/3 beeps + 3:10 auto-pause and the BT-confirm
escape hatch.

**Spec ambiguity carried forward.** The user's spec wrote both "the
timer should run for 3 minutes and 10 seconds [from first beep]" AND
"10 seconds after the last beep, the auto-played episode should
pause." Those produce 4:10 vs 3:10 from episode start. This phase
goes with 3:10 total (pause 10s after the third beep at 3:00). If
playback testing wants 4:10, it's a one-line change to
`AUTOPLAY_CONFIRM_TOTAL_MS`.

## Stronger retry + cause-message in error chip

Two diagnostic improvements after a `Failed: Failed runtime check
(IllegalArgumentException)` chip surfaced on a Castos-hosted feed and
tap-to-retry didn't help.

**Cause-message in chip.** The chip already showed the cause's class name
(added in v0.4.3); now it also clips and shows the cause's message when
present, capped at ~80 chars: `Failed: Failed runtime check
(IllegalArgumentException: Invalid Uri scheme: …) — tap to retry`. For
IAEs especially, the message names the offending input — class alone
hides that. Same string is logged in full to logcat under `LofiPodPlayer`.

**Retry via fresh setMediaItem cycle.** The retry chip used to call
`togglePlay()`, which after an error sees STATE_IDLE and does
`prepare()+play()`. That re-runs prepare on the same in-memory player
state — fine for a stuck state machine, useless if the player itself
got confused. New `PlayerController.retryCurrentEpisode()` reconstructs
the Episode from `episode_state` (audioUrl/feedUrl/title/artworkUrl) and
runs the full `playEpisode` cycle: setMediaItem → prepare → play. Any
internal state from the original failure gets reset. If the source URL
itself is bad, retry surfaces the same error — that's correct; the
retry isn't a magic wand, but a one-shot reset.

Refactored: extracted `episodeFromState(guid)` private helper since
`startDownloadForCurrent` was already doing the same EpisodeState →
Episode mapping.

## Buffering layout shift + Audio-enhancement toggle wiring

Two fixes targeting bugs that landed after v0.4.4.

**Player layout no longer shifts on rewind.** The "Buffering…" text under
the transport row was bouncing the speed chip and tabs every time a
rewind transiently kicked the player into STATE_BUFFERING. Removed the
text — the CircularProgressIndicator ring around the play button is
already the buffering signal, and a redundant text below was the only
thing causing the shift. The error chip stays in the same slot (errors
are persistent, not transient, so the one-time appearance is fine).

**Audio-enhancement master toggle now lockstep with the per-episode
override.** The "Audio enhancement" switch on the EQ screen was
local-only Compose state (`remember { mutableStateOf(true) }`) that
desynced from the actual processor on screen revisit AND was silently
overwritten by `applyEqOverrideFor` on every track transition (which
only knew about the per-episode `eqDisabled` flag). Two writers, one
boolean, last-wins → fights. Result: toggling the master switch did
nothing predictable and the user had to fight skip-silence to recover
playback.

Fix: `Settings.audioEnhancementEnabled` is the persisted master flag.
`PlayerController.applyEqOverrideFor` is now the single source of truth
for the effective enabled state — it reads BOTH the global flag and
the per-episode `eqDisabled`, sets `sharedEq.enabled = global &&
!episodeDisabled`. Three writers (master toggle, per-episode toggle,
track transitions) all funnel through it. The EQ screen's switch
collects from the Settings flow (no more desync) and writes through
to Settings + immediate re-apply for the currently playing episode.

`PlaybackService.onCreate` rehydrates the master flag alongside the
existing band/gain/skip-silence rehydration so the very first track
(before any item-transition fires) starts with the right enabled state.

## Download status on PlayerScreen + auto-download for live plays

PlayerScreen now surfaces the same five-state download affordance the
catalog row carries (download / spinner+percent+cancel / done / retry).
Sits on the artist line, right-aligned. Same composable in both modes —
extracted from EpisodesScreen into `DownloadUi.kt` so the catalog row
and the player header stay in lockstep.

**Live**: `PlayerController.playEpisode` now triggers
`downloadsApi.start(ep)` after `setMediaItem/prepare/play`, gated by an
in-memory check against the current download map so we don't thrash
DownloadManager on every play. By the time the user is committed
enough to be playing, an offline copy for the next session is the
better default. The auto-trigger skips guids already in any download
state including FAILED — a previously-failed download isn't auto-
retried on every play; the user can retry from the chip explicitly.

**Preview**: button is fully interactive from the start — tap downloads,
tap-while-downloading cancels, tap-when-done deletes. The episode is
already known via `previewData`, so the click goes straight through to
`downloadsApi.start(previewData.episode)`.

In live mode the user can also override the auto-download by tapping
cancel or delete on the same button; a new
`PlayerController.startDownloadForCurrent(guid)` looks up the
audioUrl/feedUrl from `episode_state` (already upserted by playEpisode)
to handle re-start from FAILED without needing the full Episode object
in hand.

## EQ preset memory + Notes parity + shared seek increments

Three small post-v0.4.3 corrections, bundled because each is self-contained.

**EQ screen now remembers its active preset.** Previously, navigating
out of EQ and back showed the preset row at "Flat" even though the
audio was still applying e.g. Voice L2 — the highlight state was stored
as `remember { mutableStateOf(...) }` and reset on every recomposition.
Now derived from the current bands by reverse-matching against the
known preset levels (whole-integer dB values, exactly representable as
Float, so list-equality is safe). No new persistence — single source of
truth is the bands themselves. Falls through to "no preset highlighted"
for FLAT and for any custom hand-tuned curve that doesn't match a level.

**Notes tab on PlayerScreen reaches parity with the per-episode Notes
screen.** Pulled `NoteCard` (jump / edit / delete row) and
`NoteEditorDialog` into a shared `NoteUi.kt`. Both NotesScreen and
PlayerScreen.NotesTab now render the same row, with the same dialog for
both Add and Edit. The `pauseOnNote` behavior (auto-pause while writing,
auto-resume on close) is in lockstep across both screens because the
dialog is the same code.

**Bluetooth / vehicle media controls.** Already worked via Media3's
default mapping of KEYCODE_MEDIA_REWIND/FAST_FORWARD to
`player.seekBack()`/`seekForward()`, which use the increments configured
on ExoPlayer in `PlaybackService` (15s back / 30s forward). What was
inconsistent: the on-screen back/forward buttons in PlayerScreen and
MiniPlayer hardcoded `seekRelative(-15_000)` / `seekRelative(30_000)`,
meaning a future "adjustable skip increments" Setting would only flow
to BT and not to the on-screen buttons. Both sides now go through new
`PlayerController.seekBack()` / `seekForward()` methods that delegate
to the controller, so the ExoPlayer config is the one place to tweak.

## Fix: FAILED_RUNTIME_CHECK on the very first play after cold bind

v0.4.2's queue fix (below) made the first-play attempt actually reach the
controller instead of being silently dropped — which exposed the
underlying race it had been masking. Symptom: error chip *"Failed: Failed
runtime check — tap to retry"*, fired by ExoPlayer
(`ERROR_CODE_FAILED_RUNTIME_CHECK = 1004`) on the first
setMediaItem/prepare/play immediately after a cold MediaController bind.
Three small changes in `PlayerController.kt`:

1. **Settle delay before draining `pendingPlay`.** The drain in
   `connect()`'s future listener now waits 150 ms before replaying the
   queued `playEpisode`, giving the MediaController's session-side state
   sync a beat to settle before we hit it with commands. Hammering
   setMediaItem on the same frame the future resolved was the trigger.

2. **Skip the bundled seek when startPos is 0.** `setMediaItem(item,
   startPos)` is internally setMediaItems + queued seek-to-startPos,
   and on Media3 1.4.x that seek can race with prepare on a fresh load
   (matches the pattern in androidx/media#1641). Fresh-install plays
   always have startPos = 0 (no saved position), so they take the
   `setMediaItem(item)` no-seek overload now. Resume-from-saved-position
   (startPos > 0) keeps the bundled form — that path has been stable.

3. **Better diagnostics on player errors.** `onPlayerError` now logs the
   full exception (with cause class + message) to logcat under the
   `LofiPodPlayer` tag, and the error chip appends the cause class name
   ("Failed: Failed runtime check (IllegalStateException) — tap to
   retry") so opaque codes still hint at what actually blew up. Capture
   with `adb logcat -s LofiPodPlayer:* *:E`.

The 150 ms delay is the load-bearing change; (2) is defensive belt-and-
suspenders; (3) is observability for whatever surfaces next.

## Fix: fresh-install playback won't start

Symptom: on a fresh install the very first tap on Play did nothing. The
known workaround was Settings → EQ → Skip silence → toggle L1 → back →
tap Play, after which playback worked for the rest of the session.

Earlier attempt (`Player: state-aware togglePlay + buffering/error UI`,
v0.3.x) added STATE_IDLE recovery to `togglePlay` and asserted the skip-
silence workaround was a recomposition coincidence. The recomposition
read was wrong. The actual coincidence is *time elapsed*: the multi-screen
detour burns 2–5 seconds, which is exactly long enough for the
`MediaController.Builder.buildAsync()` future to resolve on a cold service
bind. Skip Silence's `setLevel` is a no-op until audio has flowed
(`sampleRate > 0`), so it can't be the active ingredient.

Root cause: `PlayerController.connect()` builds the `MediaController`
asynchronously. Until the future fires, `controller` is null and every
public entry point — `playEpisode` included — returns silently with
`val c = controller ?: return`. On a fresh install the `PlaybackService`
has never been started, so first-bind is markedly slower than warm
subsequent runs and the user reliably wins the race against `controller`
being assigned. The first tap is dropped without any visible feedback.
This matches [androidx/media#282](https://github.com/androidx/media/issues/282)
and [#1059](https://github.com/androidx/media/issues/1059).

Fix in `PlayerController.kt` only: queue one pending `playEpisode` when
the controller isn't connected yet (`@Volatile var pendingPlay`, last-
wins), drain it inside the future listener once `controller` is live,
clear it in `release()`. Other entry points (`togglePlay`, `play`,
`seekTo`) still drop on null — those scenarios assume an existing
controller-connected session and queueing them isn't useful for this bug.
The 921bd9c STATE_IDLE recovery in `togglePlay` stays in place; it's
correct for the orthogonal "stuck IDLE after a failed prepare" case.

## Settings → Share: QR code for the latest signed APK

New "Share" section pinned at the bottom of Settings. Renders a 220dp QR
code that encodes the stable
`https://github.com/MannKablam/LofiPod/releases/latest/download/lofipod.apk`
redirect — same URL the in-app `UpdateChecker` already hits. A friend
points their camera at the screen, their phone downloads the latest
signed APK, they install. No tag-aware logic on either side; the GitHub
"latest" redirect always resolves to whatever release is currently
flagged on the repo.

Three affordances stacked in the row:
- **The QR itself** for in-person sharing.
- **Selectable URL** below it (wrapped in `SelectionContainer`) — long-press
  copies the same URL manually if the QR can't be scanned (poor lighting,
  cracked lens, etc).
- **"Share link" button** that fires `Intent.ACTION_SEND` for remote
  sharing — text, Slack, email, etc.

Generation is pure-Kotlin via ZXing core (`com.google.zxing:core:3.5.3`,
~530 KB). `QrCode.generate(text, sizePx)` returns an [ImageBitmap]; we
render it inside a white `Surface` plate (regardless of theme — black-on-
white is what every camera scanner expects) and use `FilterQuality.None`
so the modules' hard pixel edges don't get softened by anti-aliasing,
which would degrade scan reliability.

Error correction is set to `H` (~30%) over the default `M` (~15%) — the
share QR gets scanned in whatever lighting the friend's phone is in, so
robustness wins over density.

No camera permission added (we encode, we don't scan). No WebView. No
network beyond the existing OkHttp / Coil paths.

Position deliberate: just above About at the foot of Settings. The
Share section exists for someone else's benefit, not the user's own
daily-use prefs — it gets scrolled to deliberately rather than
encountered passively above the fold, but stays anchored to a section
header rather than orphaned at the very bottom.

## Kabod Pack catalog card + artwork overrides + Kabod schema doc

Three threads shipping together as v0.4.0.

**1. Kabod Pack render path + distinguished card chrome**

The catalog refactor (previous entry) iterated `Sources.PODCASTS` only — kabod
packs live in `Sources.KABOD_PACKS` and were getting fetched into state but
never rendered. Bug was masked because the only kabod pack in the canon was
the Romans series and we hadn't tested the catalog after the refactor.

Fix + visual polish in one pass. New `KabodPackRow` composable, rendered at
the **top** of the catalog ahead of all RSS feeds — these are weighty,
archived bodies of work and deserve the prominence.

The card is intentionally distinguished from regular podcast rows:
- **Border**: `BorderStroke(1.5.dp, primary @ 0.55α)` — visible across every
  theme without being garish.
- **Background**: `surface` instead of the usual `surfaceVariant`. The
  contrast with neighboring cards reads as "this row is different" without
  relying on the border alone.
- **Book glyph badge**: a 24dp circular `Surface` plate carrying
  `Icons.Filled.MenuBook`, anchored to the lower-right of the artwork via
  `align(BottomEnd) + offset(4.dp, 4.dp)`. The badge's primary fill
  preserves icon legibility against any artwork (DG's default OG image is
  bland; this gives the row a recognizable identity).
- **Hebrew chip**: כבוד ("kabod" — weight, glory, presence) in a primary
  `Surface` chip pinned to the upper-right via `align(TopEnd)`. The Column's
  56dp end-padding reserves space so titles don't wrap behind it. Unpointed
  spelling at 14sp bold — the niqqud (vowel-point) form clashes with the
  chip's vertical metrics; system fonts pick up Noto Sans Hebrew
  automatically, no asset needed.
- **Subtitle**: "{author} · {N} entries" instead of the usual "{N}
  episodes". Speaker identity is a primary identifier for archived
  expository series, and `entries` is more neutral than `episodes` for a
  generic packaged-content format.

The kabod chip itself is the visual signature — it names the format, points
back to the Hebrew root, and carries the gravity of the pastor's labor.
Same chip + glyph treatment will apply to every kabod pack we ship.

**2. Catalog artwork overrides — fully populated cards**

`SourceEntry` gains an optional `customArtworkUrl: String?` and `SourceGroup`
gains `groupArtworkUrl: String?`. CatalogScreen now prefers the override at
each render site; falls back to the parsed feed's `<itunes:image>` when
unset. Three places thread the override:
- `PodcastRow` takes `artworkUrl: String?` directly (decoupled from `pod`).
- `KabodPackRow` does too.
- `GroupRow`'s `leadArtworkUrl` falls back through `groupArtworkUrl` →
  first child's `customArtworkUrl` → first child's parsed feed art.

Two specific overrides land:
- **Bethany Bible Church**: pinned to its own CDN copy. Same image the feed
  exposes via `<itunes:image>`, but hardcoded so the card always renders
  even if the feed parse skips the tag for any reason.
- **Calvary Chapel Modesto**: the `Sunday Morning Service` and `Sunday
  Evening Service` feeds both reference `ccmodesto.com/podcast/podcast-cc.jpg`,
  which is currently a zero-byte file (200 OK with empty body). Both child
  feeds + the cluster's group banner now point at the working
  `2026-Topical-podcast-itunes-1400.jpg` cover on CCM's DigitalOcean Spaces
  bucket — high-res, CCM-branded, and at least one source of truth for the
  whole cluster.

After the overrides every card in the catalog renders with real artwork —
no placeholders for actively-shipped feeds.

**3. Kabod Pack format reference (`KABOD_SCHEMA.md`)**

A standalone, self-contained reference for the `.kabod` format: file
identity, channel- and item-level fields with their requirements, build
flow, ingestion paths, storage tables, and a "what not to put in the
format" section. Lives at the project root, **gitignored** — kept on disk
for AI-agent recall and for anyone hand-authoring a pack, but not part of
the shipped repo (the format is also documented inline in BUILD_LOG and
in the `KabodPackParser` source). When the schema needs a breaking change,
bump the namespace URI from `/1` to `/2` and the parser will treat `/2`
content as plain RSS until support is added.

## Refreshed catalog + expandable card stacks for grouped feeds

Source canon refreshed against Podcast Index. Same fetch model (raw RSS, not
the Podcast Index API itself — that one truncates `<description>`); IDs were
just used as a directory to find publisher RSS URLs. New entries: Now That
We're A Family, Simple Farmhouse Life, Homeschool Made Simple, The Briefing
with Albert Mohler, The Pour Over. Just Thinking moved off the dead Anchor
URL onto `feeds.podcastmirror.com`. Bethany / BibleThinker / Alpha and
Omega / Desiring God feeds are unchanged from the old list.

Two podcasts are actually clusters of feeds — Calvary Chapel Modesto has
four (Topical Studies, Thru the Bible, Sunday Morning, Sunday Evening) and
John Piper has three (APJ, Solid Joys, Light + Truth). Rather than four
near-identical CCM cards in the catalog, both are folded into a single
expandable "card stack" row that opens on tap to reveal the children.

**Data model.** Introduced a sealed `CatalogItem` interface in
`SourcesFileParser.kt` with two implementers: `SourceEntry` (one feed) and
`SourceGroup` (a named cluster of `SourceEntry` children). `Sources.PODCASTS`
is now `List<CatalogItem>` and the order in that list is the catalog order.
A flat `Sources.PODCAST_FEEDS` accessor expands groups into their children,
and `Sources.ALL` (kabod packs + flat feeds) is what the fetch path
(`CatalogViewModel`, `PodcastRepository`) reads — fetching is per-feed and
ignores grouping.

`Sources.displayNameOf(feedUrl)` now prefixes group children with the group
name (e.g. "Calvary Chapel Modesto — Topical Studies") so screens outside
the catalog (history, metrics) get unambiguous labels. The catalog screen
itself reads child names directly off `SourceEntry.displayName` and renders
them under the group header without the prefix.

**Catalog UI.** `CatalogScreen.kt` walks `Sources.PODCASTS` instead of
iterating loaded `Podcast` objects. It joins by `feedUrl` against an
in-memory map. A new `GroupRow` composable renders each group: first
child's artwork as a stand-in, group title, "N feeds · M episodes"
subtitle, summed "new" badge, and a chevron that rotates 180° via
`animateFloatAsState` when expanded. Expanded children render inline
underneath the group header with 24dp start padding and the short
display-name override (e.g. "Topical Studies" rather than the feed's own
"Calvary Chapel Modesto — Topical Studies"). Expansion state lives in a
`mutableStateMapOf` keyed by `groupId`. `PodcastRow` gained two optional
parameters (`titleOverride`, `indent`) so it can be reused for both
top-level and nested rows; existing call sites are unchanged.

A group is hidden entirely if all of its children failed to load — the
subtitle's feed/episode count reflects only loaded children.

The old iTunes-ID lookup comments in `Sources.kt` were replaced with
Podcast Index IDs since that's now the directory used for URL re-resolution.

## Kabod Pack format + Romans-by-Piper pack + Player transcript surface

**The format.** A "Kabod Pack" is RSS 2.0 with a `kabod:` namespace overlay
(`https://lofipod.app/ns/kabod/1`), file extension `.kabod`. Standard RSS tags
carry audio + dates so the pack would also work in a generic podcast app; the
`kabod:` extensions carry sermon-specific metadata: `packId`, `archived`,
`speaker`, `bookOfBible`, `sourceSite`, `seriesStart`/`seriesEnd` at the
channel level; `partNumber`, `scripture`, structured `scriptureRef`,
`transcriptUrl`, `transcriptSelector` per item. Optional `itunesCollectionId`
(channel) / `itunesTrackId` (item) are reserved for CDN-rotation fallback via
the iTunes Lookup API — unused in v1, slots exist.

A pack is for *completed* content — the `archived` flag tells the app to
never refresh the feed (`PodcastRepository.fetchOne` short-circuits to the
asset loader for `kabod://` URLs and never touches the network for them).
Hostable as a feed-like endpoint OR shipped locally — both are supported.

**The first pack: John Piper's Romans (1998-04-26 → 2006-12-24).** 223
verified entries, the full verse-by-verse exposition. Two sermons from the
desiringgod.org series listing were excluded because their primary text
isn't Romans: "Does James Contradict Paul?" (1999-08-08, James 2 — an
apologetic excursus during Romans 4) and "Treasuring Christ Together"
(2004-12-05, vision-casting, no scripture text). One borderline entry
("Be Constant in Prayer for the Joy of Hope", 2004-12-26) was kept after
verifying its primary text is Romans 12:12 even though the page lists
both Ephesians 1:15-23 and Romans 12:12. `partNumber` is the position in
the verified subset (1..223) — desiringgod.org's series page doesn't
publish explicit ordinal labels. Final entry: "Jesus Christ in the Book
of Romans", preached Christmas Eve 2006.

Built via a one-time generator script (`tools/build-piper-romans-pack.py`)
that converts a JSON catalog (verified by a research pass against
desiringgod.org) into the `.kabod` XML. The script handles both my own
schema and the research pass's pre-structured shape; re-runnable for future
packs from other series.

Output committed to `app/src/main/assets/kabod/desiringgod-piper-romans.kabod`
(~170 KB, 223 entries). Audio enclosure URLs point at desiringgod.org's own
CDN (`audio.desiringgod.org`), which DG hosts itself.

**App-side schema (Room v11 → v12).** Three new tables, no existing ones
touched:
- `kabod_pack` — channel-level metadata per installed pack.
- `episode_kabod` — per-episode kabod fields (scripture, transcript URL,
  part number, speaker), keyed by the same `guid` as `episode_state`.
- `episode_transcript` — cache of fetched + parsed transcripts so
  re-opening the Transcript tab doesn't re-hit the network.

Backup schema bumped v7 → v8: `kabodPacks[]` and `episodeKabod[]` arrays
round-trip the new tables. Cached transcripts are intentionally NOT exported
— they re-fetch on demand. v7 backups still import on the new build.

**Ingestion.** Two paths, both routing to the same `KabodAssetLoader`
(idempotent — already-installed packs by `packId` are skipped):
- **Bundled**: `LofiPodApp.onCreate` scans `assets/kabod/*.kabod` on every
  launch, parses, upserts. Synthetic `PodcastSourceEntity` rows are inserted
  so the catalog UI surfaces the pack alongside RSS feeds. The kabod feed
  uses a `kabod://<packId>` URL so it shares identity space with RSS
  (queue / favorites / notes / history / downloads all key off
  `(feedUrl, guid)` already and need no special-casing).
- **SAF import** ("Import pack…" in the Catalog overflow): standard
  `ActivityResultContracts.OpenDocument` picker, parse + upsert, snackbar
  reports "Imported {title} — {N} entries" or the error.

`PodcastRepository.fetchOne` checks for the `kabod://` scheme and routes
to the asset loader instead of HTTP. The standard RSS path is untouched.

**Download parity is preserved.** Kabod-pack episodes flow through the
same `Episode` model with a real HTTP `audioUrl`, so the existing Media3
`DownloadManager` path (`Downloads.start(Episode)` keyed by `guid`)
handles them with no special-casing — same Download button, same progress
indicator, same offline playback. No friction; it just looks like a
podcast.

**Transcript surface.** Two surfaces sharing one renderer
(`TranscriptContent`):
- **Tab on PlayerScreen** — third tab alongside Notes / Details. States:
  no transcript URL → "No transcript available"; URL set, fetching →
  spinner + "Loading transcript…"; loaded → paragraph list with a "Read
  full page" icon top-right; failed → inline error + Retry button.
- **Full-page route** `player/transcript/{guid}` — own `TranscriptScreen`,
  hides artwork/scrubber/controls so the user gets a clean reading
  surface. Audio keeps playing through this view; back arrow returns to
  the player. Top bar carries a play/pause icon that only renders when
  this episode is what's loaded.

Paragraphs render via Compose `Text` + `AnnotatedString` in
`bodyLarge` with 1.5× line height. Source attribution at the foot of the
list as plain text (not a tappable link — no browser).

**Scripture handoff to Logos.** Inline regex finds scripture references
(`Romans 1:1`, `Rom. 1:1–7`, `Romans 1`, etc., with the leading-numeric
book pattern for `1 Corinthians` etc.); matched ranges render in
`primary` color, semibold, and tappable via `LinkAnnotation.Clickable`.
Tap fires `Intent.ACTION_VIEW` for `https://ref.ly/<ref>` (e.g.
`https://ref.ly/Rom1.1`). Logos / Faithlife registers `ref.ly` as an app
link, so devices with Logos installed land directly on the passage.
Without Logos, the OS chooser shows. If no app handles the link at all,
a snackbar reports "No Bible app installed to open {ref}". No in-app
WebView — handing off via system intent is the user choosing what to do
with the link, not us embedding a browser.

**HTML transcript parsing.** New `TranscriptHtmlParser` with five
per-host extractors (`DesiringGod`, `SermonAudio`, `Bethany`, `Castos`,
`Generic`). The pack's optional `<kabod:transcriptSelector>` overrides
the host-matched extractor when present. Powered by **jsoup**
(`org.jsoup:jsoup:1.18.1`, ~430 KB) — used purely on already-fetched
HTML strings; jsoup does NOT introduce a browser, WebView, or network.
The app's no-WebView invariant stands (verified: zero
`android.webkit.*` imports or `WebView` instantiations anywhere in
`app/`).

**`PlayerScreen.DetailsTab`** also picks up the kabod metadata and
shows "Part {N} · Romans 1:1" in primary color under the meta line, so
users who never open the Transcript tab still see the sermon's scripture
at a glance.

**Files**: 14 new (parser + 5 extractors + 3 entities + 3 DAOs +
`TranscriptRepository` + `KabodAssetLoader` + `XmlUtils` + `ScriptureRef`
+ `TranscriptScreen` + `TranscriptContent`); modified
`AppDatabase` (entities + DAO refs + `MIGRATION_11_12`),
`Sources.kt` (`KABOD_PACKS` + `ALL`),
`PodcastRepository.fetchOne` (`kabod://` routing),
`LofiPodApp.onCreate` (loader wired + `transcripts` exposed),
`PlayerScreen` (third tab + `onOpenTranscript` plumbing + scripture
chip in DetailsTab),
`MainActivity` (`player/transcript/{guid}` route + back-stack hide
in MiniPlayer guard),
`CatalogScreen` ("Import pack…" overflow item + SAF picker),
`Backup` (v7 → v8 round-trip + caller updates in
`BackupWorker` + `MetricsScreen`),
`build.gradle.kts` (jsoup dep).

Tools also: `tools/build-piper-romans-pack.py` (the generator),
`tools/romans-catalog.json` (verified 223-entry source — already filtered
to strict Romans by the research pass), `tools/romans-sample-10.json`
(the early-build subset, kept for reference).

Local build verification was not run (no gradle wrapper jar is
committed; the repo relies on CI's `gradle/actions/setup-gradle@v4`).
First push will compile via the existing `build.yml` workflow.

## In-app updater + tag-driven release pipeline

End-to-end updates from a `git tag v*` push:

**CI side** — new `.github/workflows/release.yml`:
- Triggers on `v*` tag push (build.yml now scoped to branches only — no
  duplicate work).
- Parses the tag: leading `v` stripped → `versionName`. `versionCode` =
  `github.run_number` (always monotonically increases for this repo
  regardless of which workflow ran, so each release will satisfy
  Android's "code > previous code" rule for in-place install).
- Builds `assembleRelease` signed with the repo's stable keystore, copies
  the APK to a stable filename `lofipod.apk`.
- Generates `latest.json` (`{ manifestSchema, tag, versionCode,
  versionName, apkUrl, releaseUrl }`). `apkUrl` points at GitHub's
  stable `releases/latest/download/` redirect — same URL for every
  release so the in-app checker doesn't need to know specific tag names.
- Creates the GitHub Release with both files attached. Not a draft, not
  a prerelease — those don't count as "latest" in GitHub's API.
- `app/build.gradle.kts` now reads `versionCode` / `versionName` from
  `-PlofipodVersionCode` / `-PlofipodVersionName` gradle properties when
  set. The release workflow injects them via `ORG_GRADLE_PROJECT_*` env;
  local builds keep the literals in the file.

**App side** — new `update/UpdateChecker.kt`:
- Fetches the manifest via OkHttp, compares `versionCode` against the
  installed `PackageInfo.longVersionCode`. On a hit, downloads the APK
  into `cacheDir/updates/lofipod-<code>.apk` (versioned filename so a
  re-check after the user dismisses the dialog reuses the existing file).
- `launchInstaller` hands the APK to the system installer via a
  `FileProvider` content:// URI (file:// is blocked since Android 7).
- `canRequestInstall` / `openInstallUnknownAppsSettings` cover the
  "Install unknown apps" permission. Manifest declares
  `REQUEST_INSTALL_PACKAGES`; the user still has to grant the per-app
  toggle once via system Settings — the UI detects that and routes them.
- `res/xml/file_provider_paths.xml` exposes the `updates/` cache subdir.

**Schedule** — new `update/UpdateWorker.kt` (CoroutineWorker):
- `schedule()` re-aligns the periodic job to next 23:59 local time, then
  every 24 hours. Re-issuing on every app launch (`LofiPodApp.onCreate`)
  resets the initial delay so timezone changes / app upgrades don't leave
  a stale schedule pointing at the old wall clock.
- On a hit, posts a notification (channel `lofipod-updates`); tapping it
  fires the system installer for the already-staged APK. Tap-to-install
  is the user's call — Android doesn't allow truly silent install.
- Bails early if the user has turned auto-check off in Settings.

**Settings → Updates section**:
- Auto-check toggle (default on; reschedules / cancels the worker).
- "Check now" button for on-demand runs.
- Last-checked timestamp.
- "Update available: vX.Y.Z" chip with an Install button when a
  download is staged. Reappears after process restart by re-deriving
  from cache + persisted version metadata in Settings.

**The user-facing release flow**:
- Day-to-day: `git push` → no release, no app pickup.
- Ship: `git tag v0.3.0 && git push origin v0.3.0` → CI builds, signs,
  releases. App picks it up at next 23:59 local (or on demand from
  Settings → Updates → Check now).

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
