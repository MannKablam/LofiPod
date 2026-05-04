# Romans Catalog — Build Summary

Source: <https://www.desiringgod.org/series/romans-the-greatest-letter-ever-written/messages>
(9 paginated pages, 25 cards per page, 225 cards total in the published series order.)

## Totals

- **Entries written:** 223
- **Canonical-count match:** 224 (closest). The desiringgod.org series page lists 225
  cards. Of those, 2 are excluded by rule 5 (primary scripture not in Romans). The
  literature commonly cites either 225 (full series-page count) or 224 (excluding the
  one obvious topical exception). My strict reading of rule 5 excludes 2 entries,
  which yields 223. The 224 count is recovered if the James 2 excursus is treated as
  in-series; the 225 count is recovered if everything published on the series page
  is retained.

## Date range

- First sermon: **1998-04-26 — "The Author of the Greatest Letter Ever Written" (Romans 1:1)**
- Last sermon: **2006-12-24 — "Jesus Christ in the Book of Romans" (Romans 16:27)**
- All 223 dates fall within `[1998-04-26, 2006-12-24]` and are strictly ascending.

## Skipped sermons (rule 5: primary scripture not in Romans)

Both appeared on the series page but were excluded:

| Series-page index | Slug | Date | Listed scripture | Reason |
|---|---|---|---|---|
| 45 | `does-james-contradict-paul` | 1999-08-08 | James 2:14–26 | Apologetic excursus on the apparent Paul-vs-James tension during the Romans 4 series. The primary expository text is James 2, not Romans. (Spec rule 5: "EXCLUDE any sermon... whose primary scripture text is NOT in Romans... topical sermons.") |
| 178 | `treasuring-christ-together-the-vision-and-its-cost` | 2004-12-05 | (none listed) | Topical capital-campaign vision sermon for Bethlehem's expansion. No scripture text on the page. Not a Romans exposition. |

## Borderline INCLUDED sermons (decided on body inspection)

One entry has a mixed scripture display where the primary expository text is in
Romans but a non-Romans cross-reference appears first in the listing:

| Part | Slug | Date | Listed scripture | Decision rationale |
|---|---|---|---|---|
| 179 | `be-constant-in-prayer-for-the-joy-of-hope` | 2004-12-26 | "Ephesians 1:15–23, Romans 12:12" | The sermon body opens: "The phrase we left out last time at the end of Romans 12:12 is 'Be constant in prayer.' I saved it for today..." Title quotes Romans 12:12 verbatim. Sits between part 178 (Romans 12:12) and part 180 (Romans 12:11/13). The Ephesians passage is supporting material. Primary text **is** Romans 12:12. The catalog's `scriptureDisplay` preserves the as-published string; `scriptureBook/StartCh/...` are overridden to `Romans 12:12`. |

## Data-quality notes

- **Audio MP3 URLs:** all 223 entries have an enclosure URL pulled from the
  per-message page's "Audio (MP3)" link (`audio.desiringgod.org/<YYYYMMDD>-en-<slug>.mp3`).
  A 14-URL HEAD-check sample returned HTTP 200 from all of them.
- **`durationSeconds`:** **null** for every entry. desiringgod.org's HTML message
  pages do not expose duration metadata; computing it would require either a
  per-file HEAD probe with bitrate inference or a full MP3 download. Left as a
  TODO — the LofiPod player can fill this in lazily on first playback (it already
  has the bytes once the MP3 is requested).
- **Curly-quote handling:** titles preserve Unicode curly apostrophes/em-dashes
  exactly as published (e.g., "God's Good News Concerning His Son").
  `scriptureDisplay` preserves the en-dash (–) used by desiringgod.org for verse
  ranges (e.g., "Romans 1:1–4"), while parsed `scriptureStartV/EndV` numbers are
  ASCII integers.
- **Date source:** `datePreached` is the ISO date from each message page's
  `<meta property="article:published_time">` tag (verified to match the human-
  readable card date in every case).
- **`partNumber`:** the desiringgod.org series page does not publish explicit
  ordinal labels for each entry (only "First in a Series of Messages on Romans"
  on sermon #1 and "Part 1/2/3" suffixes on multi-part sermon titles). The
  `partNumber` here is the chronological position within the included subset
  (1..223). The original series-page index (1..225) is preserved in the skipped
  table above for traceability.

## Pipeline

The intermediate workflow lives under `tools/_cache/`:

- `_cache/romans-page1.html` ... `_cache/romans-page9.html` — series listing pages.
- `_cache/messages/<slug>.html` — individual message pages (225 files, ~14 MB total).
- `_cache/romans-cards.json` — parsed series-page cards (225 entries, in series order).
- `_cache/romans-catalog-intermediate.json` — pre-final catalog with helper fields.
- `_cache/romans-skipped.json` — exclusion log (machine-readable).
- `_cache/romans-issues.json` — empty (all 223 included entries pass all rules).

Build scripts:

- `_parse_series.py` — series-page parser (HTML → cards JSON).
- `_fetch_messages.sh` — parallel curl fetch of message pages.
- `_refetch_blocked.sh` — sequential re-fetch for any pages that hit Cloudflare.
- `_build_catalog.py` — final assembly with rule-5 exclusions and overrides.

To rebuild the catalog from cached HTML:

```
python tools/_parse_series.py     # writes _cache/romans-cards.json
python tools/_build_catalog.py    # writes tools/romans-catalog.json
```

To regenerate from scratch (re-download HTML):

```
rm -rf tools/_cache
mkdir -p tools/_cache
# fetch pages 1..9 with curl, then:
bash tools/_fetch_messages.sh
bash tools/_refetch_blocked.sh    # if any hit CF
python tools/_parse_series.py
python tools/_build_catalog.py
```

## Output schema

Each entry in `tools/romans-catalog.json`:

```json
{
  "partNumber": 1,
  "title": "The Author of the Greatest Letter Ever Written",
  "datePreached": "1998-04-26",
  "messagePageUrl": "https://www.desiringgod.org/messages/the-author-of-the-greatest-letter-ever-written",
  "audioMp3Url": "https://audio.desiringgod.org/19980426-en-the-author-of-the-greatest-letter-ever-written.mp3",
  "scriptureDisplay": "Romans 1:1",
  "scriptureBook": "Romans",
  "scriptureStartCh": 1,
  "scriptureStartV": 1,
  "scriptureEndCh": 1,
  "scriptureEndV": 1,
  "guid": "the-author-of-the-greatest-letter-ever-written",
  "durationSeconds": null
}
```
