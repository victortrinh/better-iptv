#!/usr/bin/env python3
"""Read-only survey of an Xtream Codes account.

Hits the same player_api.php endpoints a player uses during a sync, then
summarises catalog size, field coverage, naming patterns, duplicates and live
sports channels into .survey/report.md (raw responses go to .survey/raw/).
It never opens a stream, so it doesn't use one of your connections, and it
never prints the credentials.

Usage: python3 -I scripts/survey.py   (reads XTREAM_URL/USER/PASS from .env)
"""

import base64
import gzip
import json
import random
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".survey"
RAW = OUT / "raw"
UA = "Mozilla/5.0 (Linux; Android 11) better-iptv-survey"
XMLTV_CAP = 600 * 1024 * 1024

KINDS = {
    "live": ("get_live_categories", "get_live_streams"),
    "vod": ("get_vod_categories", "get_vod_streams"),
    "series": ("get_series_categories", "get_series"),
}

QUALITY_TAGS = [
    "4K", "UHD", "2160p", "FHD", "1080p", "HD", "720p", "SD", "HEVC", "H265", "x265",
    "HDR", "HDR10", "DV", "Dolby", "Atmos", "3D", "MULTI", "MULTISUB", "MULTI-SUB",
    "VOSTFR", "VOST", "VO", "VF", "VFF", "VFQ", "SUB", "SUBS", "DUB", "DUBBED", "DUAL", "CAM",
]
TAG_RES = {tag: re.compile(rf"(?<![A-Za-z0-9]){re.escape(tag)}(?![A-Za-z0-9])") for tag in QUALITY_TAGS}

BRACKET_PREFIX = re.compile(r"^\s*[\[\(|]\s*([^\]\)|]{1,12}?)\s*[\]\)|]\s*")
SEP_PREFIX = re.compile(r"^\s*([A-Z0-9+]{2,6}(?:[ -][A-Z0-9+]{2,6})?)\s*[|:\-–]\s+")
BRACKET_TOKEN = re.compile(r"[\[\(]([^\[\]\(\)]{1,20})[\]\)]")
YEAR = re.compile(r"(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)")
SPORT_RE = re.compile(
    r"\b(nhl|nfl|nba|mlb|mls|ufc|ppv|f1|dazn\w*|espn\w*|tsn\w*|rds\w*|bein\w*|wwe|epl|cfl|ncaa\w*"
    r"|motogp|nascar|sky ?sports?|events?)\b"
    r"|sport|football|soccer|formula ?1|boxing|ligue|premier league|la ?liga|serie a|bundesliga"
    r"|cricket|tennis|golf|hockey|basketball|baseball|rugby",
    re.I,
)
EVENT_RE = re.compile(r"\bvs\.?\b|\sv\s|\s@\s|\b\d{1,2}[:h]\d{2}\b|\b\d{1,2}\s?(am|pm)\b", re.I)


class Xtream:
    def __init__(self, base, user, password):
        self.base, self.user, self.password = base, user, password
        self.timings = []

    def scrub(self, text):
        for secret in (self.password, urllib.parse.quote_plus(self.password), self.user, urllib.parse.quote_plus(self.user)):
            text = text.replace(secret, "***")
        return text

    def _open(self, path, params, timeout):
        query = urllib.parse.urlencode({"username": self.user, "password": self.password, **params})
        req = urllib.request.Request(f"{self.base}/{path}?{query}", headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
        return urllib.request.urlopen(req, timeout=timeout)

    def api(self, label, save=True, **params):
        start = time.monotonic()
        try:
            with self._open("player_api.php", params, timeout=300) as resp:
                body = resp.read()
                encoding = resp.headers.get("Content-Encoding", "")
        except Exception as e:  # anything urllib raises may echo the URL, so scrub it
            raise RuntimeError(self.scrub(f"{label}: {e}")) from None
        wire = len(body)
        if encoding == "gzip":
            body = gzip.decompress(body)
        self.timings.append((label, wire, len(body), time.monotonic() - start))
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            preview = self.scrub(body[:80].decode("utf-8", "replace"))
            raise RuntimeError(f"{label}: response wasn't JSON (starts with {preview!r})") from None
        if save:
            (RAW / f"{label}.json").write_bytes(body)
        return data


class Report:
    def __init__(self):
        self.lines = []

    def h(self, text):
        self.lines += ["", f"## {text}", ""]

    def p(self, text=""):
        self.lines.append(text)

    def table(self, headers, rows):
        if not rows:
            self.p("_(none)_")
            self.p()
            return
        self.p("| " + " | ".join(headers) + " |")
        self.p("|" + "---|" * len(headers))
        for row in rows:
            self.p("| " + " | ".join(trunc(str(c), 120).replace("|", "\\|").replace("\n", " ") for c in row) + " |")
        self.p()


def load_env():
    path = ROOT / ".env"
    if not path.exists():
        sys.exit("No .env found. Copy .env.example to .env and fill in XTREAM_URL, XTREAM_USER, XTREAM_PASS.")
    env = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip().strip("\"'")
    missing = [k for k in ("XTREAM_URL", "XTREAM_USER", "XTREAM_PASS") if not env.get(k)]
    if missing:
        sys.exit(f".env is missing: {', '.join(missing)}")
    return env["XTREAM_URL"], env["XTREAM_USER"], env["XTREAM_PASS"]


def base_url(raw):
    """Accept a bare host, a host:port, or a pasted player_api/get.php link."""
    if "://" not in raw:
        raw = "http://" + raw
    parts = urllib.parse.urlsplit(raw)
    return f"{parts.scheme}://{parts.netloc}"


def present(v):
    return v not in (None, "", "0", 0, [], {})


def pct(n, d):
    return f"{100 * n / d:.0f}%" if d else "–"


def mb(n):
    return f"{n / 1048576:.1f} MB"


def trunc(s, n=60):
    s = "" if s is None else str(s)
    return s if len(s) <= n else s[: n - 1] + "…"


def as_list(v):
    if isinstance(v, list):
        return [row for row in v if isinstance(row, dict)]
    if isinstance(v, dict):
        return [row for row in v.values() if isinstance(row, dict)]
    return []


def fmt_ts(v):
    if present(v) and str(v).isdigit():
        return datetime.fromtimestamp(int(v), timezone.utc).strftime("%Y-%m-%d")
    return "never / not given"


def b64(text):
    try:
        return base64.b64decode(text or "").decode("utf-8")
    except Exception:
        return str(text or "")


def prefix(name):
    m = BRACKET_PREFIX.match(name) or SEP_PREFIX.match(name)
    return m.group(1).strip().upper() if m else None


def normalize(name):
    m = BRACKET_PREFIX.match(name) or SEP_PREFIX.match(name)
    if m:
        name = name[m.end():]
    name = BRACKET_TOKEN.sub(" ", name)
    for rx in TAG_RES.values():
        name = rx.sub(" ", name)
    name = YEAR.sub(" ", name)
    return " ".join(re.sub(r"[^\w]+", " ", name.lower()).split())


def tmdb_id(row):
    for key in ("tmdb", "tmdb_id"):
        if present(row.get(key)):
            return str(row[key]).strip()
    return None


def category_names(cats):
    return {str(c.get("category_id")): str(c.get("category_name") or "") for c in cats}


def spread_sample(rows, count):
    """Pick up to `count` rows, one per category, so samples aren't all from one section."""
    by_cat = defaultdict(list)
    for row in rows:
        by_cat[str(row.get("category_id"))].append(row)
    cats = list(by_cat)
    random.shuffle(cats)
    return [random.choice(by_cat[c]) for c in cats[:count]]


def summarize_stream_info(info):
    """One-line view of the ffprobe-style video/audio blocks some panels include."""
    parts = []
    video = info.get("video") if isinstance(info.get("video"), dict) else {}
    audio = info.get("audio") if isinstance(info.get("audio"), dict) else {}
    if video:
        hdr = {"smpte2084": " HDR10/DV", "arib-std-b67": " HLG"}.get(video.get("color_transfer"), "")
        parts.append(f"{video.get('codec_name', '?')} {video.get('width', '?')}x{video.get('height', '?')}{hdr}")
    if audio:
        tags = audio.get("tags") if isinstance(audio.get("tags"), dict) else {}
        parts.append(f"{audio.get('codec_name', '?')} {audio.get('channels', '?')}ch lang={tags.get('language', '?')}")
    if present(info.get("bitrate")):
        parts.append(f"{info['bitrate']} kbps")
    return ", ".join(parts) or "no video/audio info"


def survey_account(x, r):
    data = x.api("account", save=False)
    info = data.get("user_info") if isinstance(data, dict) else None
    if not isinstance(info, dict) or str(info.get("auth", 1)) != "1":
        sys.exit("The provider rejected the login. Check XTREAM_URL, XTREAM_USER and XTREAM_PASS in .env.")
    server = data.get("server_info") or {}
    safe_info = {k: v for k, v in info.items() if k not in ("username", "password")}
    (RAW / "account.json").write_text(json.dumps({"user_info": safe_info, "server_timezone": server.get("timezone")}, indent=2))
    r.h("Account")
    r.table(["Field", "Value"], [
        ("Status", info.get("status")),
        ("Expires", fmt_ts(info.get("exp_date"))),
        ("Trial", info.get("is_trial")),
        ("Max connections", info.get("max_connections")),
        ("Active connections now", info.get("active_cons")),
        ("Output formats", ", ".join(info.get("allowed_output_formats") or [])),
        ("Server timezone", server.get("timezone")),
    ])


def fetch_catalog(x, r):
    cats, items = {}, {}
    for kind, (cat_action, list_action) in KINDS.items():
        cats[kind] = as_list(x.api(f"{kind}_categories", action=cat_action))
        items[kind] = as_list(x.api(f"{kind}_streams", action=list_action))
    timing = {t[0]: t for t in x.timings}
    rows, wire_total, raw_total, secs_total = [], 0, 0, 0.0
    for kind in KINDS:
        _, wire, size, secs = timing[f"{kind}_streams"]
        wire_total, raw_total, secs_total = wire_total + wire, raw_total + size, secs_total + secs
        rows.append((kind, len(cats[kind]), len(items[kind]), mb(wire), mb(size), f"{secs:.1f}s"))
    rows.append(("**total**", sum(map(len, cats.values())), sum(map(len, items.values())), mb(wire_total), mb(raw_total), f"{secs_total:.1f}s"))
    r.h("Catalog size and sync cost")
    r.table(["Kind", "Categories", "Items", "Downloaded", "Uncompressed", "Time (from this Mac)"], rows)

    lines = ["# All categories", ""]
    for kind in KINDS:
        per_cat = Counter(str(i.get("category_id")) for i in items[kind])
        lines += [f"## {kind} ({len(cats[kind])})", ""]
        lines += [f"- {c.get('category_name')} ({per_cat.get(str(c.get('category_id')), 0)})" for c in cats[kind]]
        lines.append("")
    (OUT / "categories.md").write_text("\n".join(lines))
    return cats, items


def survey_fields(r, kind, rows):
    r.h(f"Fields provided: {kind} ({len(rows)} items; '0' counts as empty)")
    counts = Counter(k for row in rows for k, v in row.items() if present(v))
    keys = sorted({k for row in rows for k in row}, key=lambda k: -counts[k])
    r.table(["Field", "Filled"], [(k, pct(counts[k], len(rows))) for k in keys])
    if rows:
        sample = random.choice(rows)
        r.p("Example item:")
        r.p("```json")
        r.p(json.dumps({k: trunc(v, 80) if isinstance(v, str) else v for k, v in sample.items()}, ensure_ascii=False, indent=1))
        r.p("```")


def survey_naming(r, kind, cats, rows):
    names = [str(row.get("name") or "") for row in rows]
    n = len(names)
    prefixes = Counter(p for p in map(prefix, names) if p)
    tags = Counter(tag for name in names for tag, rx in TAG_RES.items() if rx.search(name))
    tokens = Counter("<year>" if YEAR.fullmatch(t.strip()) else t.strip() for name in names for t in BRACKET_TOKEN.findall(name))
    with_year = sum(1 for name in names if YEAR.search(name))
    non_latin = sum(1 for name in names if any(ch.isalpha() and ord(ch) > 0x24F for ch in name))

    r.h(f"Naming patterns: {kind}")
    r.p(f"- Names with a year: {pct(with_year, n)}")
    r.p(f"- Names with a leading tag (e.g. `EN |`, `[4K]`): {pct(sum(prefixes.values()), n)}")
    r.p(f"- Names containing non-Latin script: {pct(non_latin, n)}")
    r.p()
    r.p("Leading tags in item names:")
    r.table(["Tag", "Items", "Share"], [(t, c, pct(c, n)) for t, c in prefixes.most_common(25)])
    r.p("Quality/language words in item names:")
    r.table(["Word", "Items", "Share"], [(t, c, pct(c, n)) for t, c in tags.most_common()])
    r.p("Bracketed tokens in item names:")
    r.table(["Token", "Items"], tokens.most_common(25))

    per_cat = Counter(str(row.get("category_id")) for row in rows)
    names_by_id = category_names(cats)
    by_prefix = defaultdict(lambda: [0, 0])
    for cid, name in names_by_id.items():
        bucket = by_prefix[prefix(name) or "(none)"]
        bucket[0] += 1
        bucket[1] += per_cat.get(cid, 0)
    r.p("Category prefixes weighted by items (a proxy for the language/region split):")
    r.table(["Prefix", "Categories", "Items", "Share of items"],
            sorted(((p, c, i, pct(i, n)) for p, (c, i) in by_prefix.items()), key=lambda t: -t[2])[:25])
    r.p("Biggest categories:")
    r.table(["Category", "Items"], [(names_by_id.get(cid, f"id {cid}"), c) for cid, c in per_cat.most_common(25)])


def survey_duplicates(r, kind, rows):
    r.h(f"Duplicates: {kind}")
    n = len(rows)
    if not n:
        r.p("_(no items)_")
        return
    by_tmdb, by_name = defaultdict(list), defaultdict(list)
    for row in rows:
        name = str(row.get("name") or "")
        if tmdb_id(row):
            by_tmdb[tmdb_id(row)].append(row)
        year = YEAR.search(name)
        by_name[f"{normalize(name)}|{year.group(0) if year else row.get('year') or ''}"].append(row)
    with_tmdb = sum(map(len, by_tmdb.values()))
    r.p(f"- Items with a TMDB ID: {with_tmdb} of {n} ({pct(with_tmdb, n)})")
    if by_tmdb:
        multi = sum(1 for v in by_tmdb.values() if len(v) > 1)
        r.p(f"- Unique TMDB titles: {len(by_tmdb)}, {with_tmdb / len(by_tmdb):.2f} listings per title; {pct(multi, len(by_tmdb))} of titles have 2+ listings")
    r.p(f"- Unique titles by cleaned name + year: {len(by_name)}, {n / len(by_name):.2f} listings per title")
    r.p()

    groups = by_tmdb if with_tmdb >= n * 0.3 else by_name
    r.p(f"Most duplicated titles (grouped by {'TMDB ID' if groups is by_tmdb else 'cleaned name'}; one entry per listing):")
    for group in sorted(groups.values(), key=len, reverse=True)[:8]:
        r.p(f"- **{len(group)}×** " + " · ".join(f"`{trunc(g.get('name'), 60)}`" for g in group[:8]))
    if by_tmdb:
        crossed = [g for g in by_tmdb.values() if len({normalize(str(x.get("name") or "")) for x in g}) > 1][:8]
        r.p()
        r.p("Same TMDB ID, different names (what name-matching alone would miss):")
        for group in crossed:
            r.p("- " + " · ".join(f"`{trunc(g.get('name'), 60)}`" for g in group[:6]))


def survey_vod_details(x, r, rows, count=10):
    r.h("Movie detail samples (get_vod_info)")
    keys, out = Counter(), []
    picks = spread_sample(rows, count)
    for row in picks:
        sid = row.get("stream_id")
        try:
            data = x.api(f"vod_info_{sid}", action="get_vod_info", vod_id=sid)
        except RuntimeError as e:
            out.append((trunc(row.get("name"), 50), "–", f"error: {e}", "–"))
            continue
        info = data.get("info") if isinstance(data, dict) and isinstance(data.get("info"), dict) else {}
        keys.update(k for k, v in info.items() if present(v))
        out.append((trunc(row.get("name"), 50), row.get("container_extension"), summarize_stream_info(info),
                    info.get("tmdb_id") or info.get("tmdb") or "–"))
        time.sleep(0.3)
    r.table(["Title", "File", "Video / audio (as reported by provider)", "TMDB"], out)
    r.p(f"Detail fields filled across {len(picks)} samples: " + ", ".join(f"{k} ({c})" for k, c in keys.most_common()))


def survey_series_details(x, r, rows, count=4):
    r.h("Series detail samples (get_series_info)")
    out = []
    for row in spread_sample(rows, count):
        sid = row.get("series_id")
        try:
            data = x.api(f"series_info_{sid}", action="get_series_info", series_id=sid)
        except RuntimeError as e:
            out.append((trunc(row.get("name"), 40), "–", "–", "–", f"error: {e}", "–"))
            continue
        episodes = data.get("episodes") if isinstance(data, dict) else None
        seasons = list(episodes.values()) if isinstance(episodes, dict) else episodes if isinstance(episodes, list) else []
        eps = [e for season in seasons for e in (season if isinstance(season, list) else [season]) if isinstance(e, dict)]
        first = eps[0] if eps else {}
        einfo = first.get("info") if isinstance(first.get("info"), dict) else {}
        sinfo = data.get("info") if isinstance(data.get("info"), dict) else {}
        out.append((trunc(row.get("name"), 40), len(seasons), len(eps), first.get("container_extension", "–"),
                    summarize_stream_info(einfo), sinfo.get("tmdb") or sinfo.get("tmdb_id") or "–"))
        time.sleep(0.3)
    r.table(["Series", "Seasons", "Episodes", "File", "First episode video / audio", "TMDB"], out)


def survey_live(x, r, cats, rows):
    n = len(rows)
    names_by_id = category_names(cats)
    by_cat = defaultdict(list)
    for c in rows:
        by_cat[str(c.get("category_id"))].append(c)
    sport_cats = sorted(((cid, name) for cid, name in names_by_id.items() if SPORT_RE.search(name)),
                        key=lambda t: -len(by_cat.get(t[0], [])))
    sport_ids = {cid for cid, _ in sport_cats}
    sport_channels = [c for cid in sport_ids for c in by_cat.get(cid, [])]
    elsewhere = [c for c in rows if str(c.get("category_id")) not in sport_ids and SPORT_RE.search(str(c.get("name")))]
    events = [c for c in sport_channels if EVENT_RE.search(str(c.get("name")))]

    r.h("Live TV and sports")
    r.p(f"- Channels with a guide ID (epg_channel_id): {pct(sum(1 for c in rows if present(c.get('epg_channel_id'))), n)}")
    r.p(f"- Channels with catch-up (tv_archive): {pct(sum(1 for c in rows if present(c.get('tv_archive'))), n)}")
    r.p(f"- Sports-looking categories: {len(sport_cats)}, holding {len(sport_channels)} channels")
    r.p(f"- Sports-looking channels filed under other categories: {len(elsewhere)}")
    r.p()
    r.table(["Category", "Channels", "Sample channel names"],
            [(name, len(by_cat.get(cid, [])), " · ".join(trunc(c.get("name"), 40) for c in by_cat.get(cid, [])[:5]))
             for cid, name in sport_cats[:20]])
    r.p("Sports-looking channels outside sports categories (sample):")
    for c in elsewhere[:10]:
        r.p(f"- `{trunc(c.get('name'), 80)}` in {names_by_id.get(str(c.get('category_id')), '?')}")
    r.p()
    r.p(f"Event-style channel names (team vs team, start time) in sports categories: {len(events)}")
    for c in events[:15]:
        r.p(f"- `{trunc(c.get('name'), 90)}`")
    r.p()

    with_epg = [c for c in sport_channels + elsewhere if present(c.get("epg_channel_id"))]
    out = []
    for c in random.sample(with_epg, min(6, len(with_epg))):
        sid = c.get("stream_id")
        try:
            data = x.api(f"short_epg_{sid}", action="get_short_epg", stream_id=sid, limit=4)
        except RuntimeError as e:
            out.append((trunc(c.get("name"), 40), f"error: {e}"))
            continue
        listings = data.get("epg_listings") if isinstance(data, dict) else None
        titles = [f"{str(l.get('start') or '')[11:16]} {b64(l.get('title'))}" for l in (listings or [])[:4] if isinstance(l, dict)]
        out.append((trunc(c.get("name"), 40), " · ".join(titles) or "(empty)"))
        time.sleep(0.3)
    r.p("What the guide says on a few sports channels right now:")
    r.table(["Channel", "Next listings"], out)


def survey_xmltv(x, r):
    r.h("Full guide (xmltv.php)")
    start, total, programmes, channels, tail, truncated = time.monotonic(), 0, 0, 0, b"", False
    try:
        with x._open("xmltv.php", {}, timeout=120) as resp:
            stream = gzip.GzipFile(fileobj=resp) if resp.headers.get("Content-Encoding") == "gzip" else resp
            while True:
                chunk = stream.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                buf = tail + chunk  # an 8-byte tail can't hold a whole tag, so nothing is counted twice
                programmes += buf.count(b"<programme")
                channels += buf.count(b"<channel ")
                tail = buf[-8:]
                if total > XMLTV_CAP:
                    truncated = True
                    break
    except Exception as e:
        r.p(f"_Guide unavailable: {x.scrub(str(e))}_")
        return
    secs = time.monotonic() - start
    x.timings.append(("xmltv", total, total, secs))
    r.p(f"- Size: {mb(total)}{' (stopped at cap)' if truncated else ''}, downloaded in {secs:.1f}s")
    r.p(f"- Channels: {channels}, programme entries: {programmes}")


def survey_timings(x, r):
    r.h("Request timings")
    big = [t for t in x.timings if not re.match(r"(vod_info|series_info|short_epg)_", t[0])]
    r.table(["Request", "Downloaded", "Uncompressed", "Time"], [(l, mb(w), mb(s), f"{d:.1f}s") for l, w, s, d in big])


def main():
    raw_url, user, password = load_env()
    RAW.mkdir(parents=True, exist_ok=True)
    random.seed(7)
    x = Xtream(base_url(raw_url), user, password)
    r = Report()
    r.p("# Xtream catalog survey")
    r.p(f"_Generated {datetime.now().strftime('%Y-%m-%d %H:%M')}. Read-only: no streams were opened._")

    survey_account(x, r)
    cats, items = fetch_catalog(x, r)
    for kind in ("vod", "series", "live"):
        survey_fields(r, kind, items[kind])
    for kind in ("vod", "series", "live"):
        survey_naming(r, kind, cats[kind], items[kind])
    for kind in ("vod", "series"):
        survey_duplicates(r, kind, items[kind])
    for section in (lambda: survey_vod_details(x, r, items["vod"]),
                    lambda: survey_series_details(x, r, items["series"]),
                    lambda: survey_live(x, r, cats["live"], items["live"]),
                    lambda: survey_xmltv(x, r)):
        try:
            section()
        except RuntimeError as e:
            r.p(f"_Section skipped: {e}_")
    survey_timings(x, r)

    text = x.scrub("\n".join(r.lines) + "\n")
    (OUT / "report.md").write_text(text)
    print(text)
    print(f"Report written to {OUT / 'report.md'} (all categories in {OUT / 'categories.md'})")


if __name__ == "__main__":
    main()
