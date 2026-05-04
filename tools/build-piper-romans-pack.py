#!/usr/bin/env python3
"""
Build a Kabod Pack file (.kabod, RSS 2.0 + kabod: namespace) from a verified
JSON catalog of sermons.

Usage:
    py tools/build-piper-romans-pack.py [INPUT_JSON] [OUTPUT_KABOD]

Defaults:
    INPUT_JSON   = tools/romans-sample-10.json   (or tools/romans-catalog.json if it exists)
    OUTPUT_KABOD = app/src/main/assets/kabod/desiringgod-piper-romans.kabod

The input JSON is a list of sermon dicts. Required keys:
  partNumber, slug, title, datePreached (YYYY-MM-DD),
  messagePageUrl, audioMp3Url, scripture
Optional:
  durationSeconds, description

Idempotent — re-running with the same input produces the same output bytes
(modulo timestamps which we deliberately don't include).
"""

from __future__ import annotations
import json, re, sys, html, datetime, os
from xml.sax.saxutils import escape as xml_escape

PACK_ID = "desiringgod-piper-romans"
PACK_TITLE = "Romans: The Greatest Letter Ever Written"
PACK_SPEAKER = "John Piper"
PACK_BOOK = "Romans"
PACK_SOURCE_SITE = "https://www.desiringgod.org/series/romans-the-greatest-letter-ever-written"
PACK_DESCRIPTION = (
    "John Piper's verse-by-verse exposition of Paul's letter to the Romans, "
    "preached at Bethlehem Baptist Church, Minneapolis, from 1998 to 2006."
)
PACK_ARTWORK = "https://www.desiringgod.org/img/og-default.jpg"
SERIES_START = "1998-04-26"
SERIES_END = "2006-12-24"

# Roman canonical book name → ref.ly abbreviation map (only what we need here).
BOOK_ABBREV = {
    "Romans": "Rom",
}

SCRIPTURE_RE = re.compile(
    r"^\s*(?P<book>[1-3]?\s*[A-Za-z]+(?:\s+[A-Za-z]+)*)\s+"
    r"(?P<startCh>\d+)"
    r"(?::(?P<startV>\d+))?"
    r"(?:\s*[–—\-]\s*(?P<endChOrV>\d+)(?::(?P<endV>\d+))?)?"
    r"\s*$"
)


def parse_scripture(s: str | None):
    if not s:
        return None
    m = SCRIPTURE_RE.match(s)
    if not m:
        return None
    book = re.sub(r"\s+", " ", m.group("book").strip())
    start_ch = int(m.group("startCh"))
    start_v = int(m.group("startV")) if m.group("startV") else None
    end_v = None
    end_ch = None
    end_or = m.group("endChOrV")
    end_v_only = m.group("endV")
    if end_or is not None:
        if end_v_only is not None:
            # "Romans 1:5-2:3" form (rare in this series but valid)
            end_ch = int(end_or)
            end_v = int(end_v_only)
        else:
            # "Romans 1:1-7" — end token is verse within startCh; OR "Romans 1-3" — end is chapter
            if start_v is not None:
                end_ch = start_ch
                end_v = int(end_or)
            else:
                end_ch = int(end_or)
    return {
        "book": book,
        "startCh": start_ch,
        "startV": start_v,
        "endCh": end_ch,
        "endV": end_v,
    }


def to_rfc822(date_iso: str) -> str:
    """ '1998-04-26' -> 'Sun, 26 Apr 1998 00:00:00 GMT' """
    dt = datetime.datetime.strptime(date_iso, "%Y-%m-%d")
    return dt.strftime("%a, %d %b %Y 00:00:00 GMT")


def el(tag: str, text: str | None = None, indent: int = 4, **attrs) -> str:
    """Render an XML element. text is xml-escaped. attrs are xml-escaped."""
    pad = " " * indent
    attr_str = "".join(f' {k}="{xml_escape(str(v), {chr(34): "&quot;"})}"' for k, v in attrs.items() if v is not None)
    if text is None:
        return f'{pad}<{tag}{attr_str}/>'
    return f'{pad}<{tag}{attr_str}>{xml_escape(text)}</{tag}>'


def normalize_entry(e: dict) -> dict:
    """Normalize one catalog entry across the two known input shapes:
    (a) build-time sample shape: slug, scripture, description
    (b) research-subagent shape:   guid, scriptureDisplay, scriptureBook/StartCh/StartV/EndCh/EndV
    Returns a dict with every field the XML generator expects, with None for
    anything that isn't present in this entry.
    """
    n = dict(e)
    n.setdefault("slug", e.get("guid"))
    n.setdefault("guid", e.get("slug"))
    n.setdefault("scripture", e.get("scriptureDisplay"))
    return n


def filter_to_romans(catalog: list[dict]) -> tuple[list[dict], list[dict]]:
    """Apply the user's strict-Romans rule. Returns (kept, dropped).
    Drop any entry whose scriptureBook is not 'Romans', or that has no scripture
    info at all. Drops are reported separately so the generator log makes the
    exclusions visible.
    """
    kept, dropped = [], []
    for e in catalog:
        book = e.get("scriptureBook")
        if book == "Romans":
            kept.append(e)
        else:
            dropped.append(e)
    return kept, dropped


def build_pack(catalog: list[dict]) -> str:
    lines: list[str] = []
    lines.append('<?xml version="1.0" encoding="UTF-8"?>')
    lines.append(
        '<rss version="2.0"'
        ' xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"'
        ' xmlns:kabod="https://lofipod.app/ns/kabod/1">'
    )
    lines.append("  <channel>")
    lines.append(el("title", PACK_TITLE, indent=4))
    lines.append(el("link", PACK_SOURCE_SITE, indent=4))
    lines.append(el("description", PACK_DESCRIPTION, indent=4))
    lines.append(el("language", "en-US", indent=4))
    lines.append(el("itunes:author", PACK_SPEAKER, indent=4))
    lines.append(f'    <itunes:image href="{xml_escape(PACK_ARTWORK)}"/>')
    lines.append(el("kabod:packId", PACK_ID, indent=4))
    lines.append(el("kabod:archived", "true", indent=4))
    lines.append(el("kabod:speaker", PACK_SPEAKER, indent=4))
    lines.append(el("kabod:bookOfBible", PACK_BOOK, indent=4))
    lines.append(el("kabod:sourceSite", PACK_SOURCE_SITE, indent=4))
    lines.append(el("kabod:seriesStart", SERIES_START, indent=4))
    lines.append(el("kabod:seriesEnd", SERIES_END, indent=4))

    for raw in catalog:
        entry = normalize_entry(raw)
        lines.append("    <item>")
        lines.append(el("title", entry["title"], indent=6))
        lines.append(el("pubDate", to_rfc822(entry["datePreached"]), indent=6))
        lines.append(f'      <guid isPermaLink="false">{xml_escape(entry["guid"])}</guid>')
        if entry.get("description"):
            lines.append(el("description", entry["description"], indent=6))
        lines.append(
            f'      <enclosure url="{xml_escape(entry["audioMp3Url"])}" '
            f'type="audio/mpeg"/>'
        )
        if entry.get("durationSeconds"):
            lines.append(el("itunes:duration", str(int(entry["durationSeconds"])), indent=6))
        if entry.get("partNumber") is not None:
            lines.append(el("kabod:partNumber", str(entry["partNumber"]), indent=6))
        if entry.get("scripture"):
            lines.append(el("kabod:scripture", entry["scripture"], indent=6))
            # Prefer pre-structured fields when present (research-subagent shape);
            # otherwise fall back to regex parsing of the display string.
            if entry.get("scriptureBook"):
                attrs = {"book": entry["scriptureBook"]}
                for k_in, k_out in (("scriptureStartCh", "startCh"), ("scriptureStartV", "startV"),
                                    ("scriptureEndCh", "endCh"), ("scriptureEndV", "endV")):
                    if entry.get(k_in) is not None:
                        attrs[k_out] = entry[k_in]
                attr_str = "".join(f' {k}="{v}"' for k, v in attrs.items())
                lines.append(f'      <kabod:scriptureRef{attr_str}/>')
            else:
                ref = parse_scripture(entry["scripture"])
                if ref:
                    attrs = {"book": ref["book"], "startCh": ref["startCh"]}
                    if ref["startV"] is not None: attrs["startV"] = ref["startV"]
                    if ref["endCh"] is not None: attrs["endCh"] = ref["endCh"]
                    if ref["endV"] is not None: attrs["endV"] = ref["endV"]
                    attr_str = "".join(f' {k}="{v}"' for k, v in attrs.items())
                    lines.append(f'      <kabod:scriptureRef{attr_str}/>')
        if entry.get("messagePageUrl"):
            lines.append(el("kabod:transcriptUrl", entry["messagePageUrl"], indent=6))
            lines.append(el("kabod:transcriptSelector", ".message-transcript, .body-text, article", indent=6))
        lines.append("    </item>")

    lines.append("  </channel>")
    lines.append("</rss>")
    return "\n".join(lines) + "\n"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    if len(sys.argv) >= 2:
        in_path = sys.argv[1]
    else:
        full = os.path.join(here, "romans-catalog.json")
        sample = os.path.join(here, "romans-sample-10.json")
        in_path = full if os.path.exists(full) else sample
    if len(sys.argv) >= 3:
        out_path = sys.argv[2]
    else:
        out_path = os.path.join(repo, "app", "src", "main", "assets", "kabod", "desiringgod-piper-romans.kabod")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    with open(in_path, encoding="utf-8") as f:
        catalog = json.load(f)
    catalog.sort(key=lambda e: (e.get("partNumber") or 0, e.get("datePreached") or ""))

    kept, dropped = filter_to_romans(catalog)
    if dropped:
        print(f"Dropped {len(dropped)} non-Romans entries (per the strict-Romans rule):")
        for d in dropped:
            print(f"  part {d.get('partNumber')}: {d.get('title')!r} — scripture={d.get('scriptureBook') or '(none)'}")

    xml = build_pack(kept)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)
    print(f"Wrote {out_path} ({len(xml)} bytes, {len(kept)} entries from {len(catalog)} source rows)")


if __name__ == "__main__":
    main()
