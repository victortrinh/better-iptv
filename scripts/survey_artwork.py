#!/usr/bin/env python3
"""Survey B for M6: what the Provider's own artwork looks like.

Takes a couple of hundred Titles from the Listings scripts/survey.py saved
(.survey/raw), spread over Prefix languages, recent additions and the Charts,
and for each:
- fetches every distinct poster its Listings link to (stream_icon / cover),
  noting bytes, pixel size and host, and which Prefix languages share a file;
- for a Movie, asks the Provider's get_vod_info once (one call per Title, with
  a pause) for its backdrop_path, cover_big, trailer, plot, cast, genre,
  runtime; for a Show, reads backdrop_path from the Listing itself;
- fetches the first backdrop;
- runs Apple's Vision text recognizer (scripts/artwork/ocr.swift) over every
  image to tell art with printed text from textless art;
- writes <survey-dir>/artwork/report.md, a contact sheet (sheet.html) to look
  at in a browser or render with headless Chrome, and the raw answers.

Catalog calls only, never a stream. Credentials come from .env and are never
printed. Usage: python3 -I scripts/survey_artwork.py [survey-dir] [--movies N] [--shows N]
"""

import base64
import gzip
import hashlib
import json
import random
import re
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
args = [a for a in sys.argv[1:] if not a.startswith("--")]
OUT = Path(args[0]) if args else ROOT / ".survey"
RAW = OUT / "raw"
ART = OUT / "artwork"
IMG = ART / "img"
MOVIES = int(sys.argv[sys.argv.index("--movies") + 1]) if "--movies" in sys.argv else 130
SHOWS = int(sys.argv[sys.argv.index("--shows") + 1]) if "--shows" in sys.argv else 70
UA = "Mozilla/5.0 (Linux; Android 11) better-iptv-survey"
PROVIDER_PAUSE = 0.5
PREFIX = re.compile(r"^\s*([A-Z0-9+\-]{2,12})\s*[|:\-–]\s+")
LANG_PREFIXES = ["EN", "FR", "DE", "ES", "IT", "NL", "PT", "GR", "TR", "PL", "SE", "AR", "IR", "IN", "RO", "NF", "SC", "TOP", "4K-FR", "4K-DE", "4K-EN"]


def load_env():
    env = {}
    env_file = ROOT / ".env" if (ROOT / ".env").exists() else OUT.parent / ".env"
    for line in env_file.read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip("\"'")
    raw = env["XTREAM_URL"]
    if "://" not in raw:
        raw = "http://" + raw
    p = urllib.parse.urlsplit(raw)
    return f"{p.scheme}://{p.netloc}", env["XTREAM_USER"], env["XTREAM_PASS"]


class Provider:
    def __init__(self):
        self.base, self.user, self.password = load_env()
        self.calls = []

    def scrub(self, text):
        for s in (self.password, self.user):
            text = text.replace(s, "***")
        return text

    def api(self, **params):
        """One catalog call, its answer kept under artwork/answers/ so a re-run doesn't ask again."""
        kept = ART / "answers" / ("_".join(f"{k}-{v}" for k, v in sorted(params.items())) + ".json")
        if kept.exists():
            return json.loads(kept.read_text())
        data = self._api(**params)
        if data is not None:
            kept.parent.mkdir(exist_ok=True)
            kept.write_text(json.dumps(data, ensure_ascii=False))
        return data

    def _api(self, **params):
        query = urllib.parse.urlencode({"username": self.user, "password": self.password, **params})
        req = urllib.request.Request(f"{self.base}/player_api.php?{query}", headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
        start = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = resp.read()
                if resp.headers.get("Content-Encoding") == "gzip":
                    body = gzip.decompress(body)
                status = resp.status
        except urllib.error.HTTPError as e:
            status, body = e.code, b""
        except Exception as e:
            raise RuntimeError(self.scrub(str(e))) from None
        self.calls.append((params.get("action"), status, time.monotonic() - start))
        time.sleep(PROVIDER_PAUSE)
        if status != 200:
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return None


def present(v):
    return v not in (None, "", "0", 0, [], {})


def urls(v):
    """The http(s) URLs in a field that may be a string, a list with nulls, or nothing."""
    items = v if isinstance(v, list) else [v]
    return [u for u in items if isinstance(u, str) and u.startswith("http")]


def prefix(name):
    m = PREFIX.match(name or "")
    return m.group(1) if m else None


def image_size(data):
    """Pixel size of a JPEG, PNG, GIF or WebP from its bytes, without Pillow."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", data[16:24])
        return w, h
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return struct.unpack("<HH", data[6:10])
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        if data[12:16] == b"VP8X":
            return 1 + int.from_bytes(data[24:27], "little"), 1 + int.from_bytes(data[27:30], "little")
        if data[12:16] == b"VP8 ":
            return struct.unpack("<HH", data[26:30])[0] & 0x3FFF, struct.unpack("<HH", data[28:32])[0] & 0x3FFF
    if data[:2] == b"\xff\xd8":
        i = 2
        while i < len(data) - 9:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            length = struct.unpack(">H", data[i + 2:i + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            i += 2 + length
    return None


def fetch_image(url, cache):
    """Downloads an image once, keeps it under img/<sha1>.<ext>, returns its record."""
    if url in cache:
        return cache[url]
    rec = {"url": url, "host": urllib.parse.urlsplit(url).netloc, "bytes": 0, "size": None, "file": None, "status": None}
    cache[url] = rec
    for saved in IMG.glob(hashlib.sha1(url.encode()).hexdigest()[:16] + ".*"):  # an earlier run fetched it
        data = saved.read_bytes()
        rec.update(bytes=len(data), size=image_size(data), file=saved.name, status=200)
        return rec
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        start = time.monotonic()
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
            rec["status"] = resp.status
            rec["ms"] = round(1000 * (time.monotonic() - start))
            ctype = resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        rec["status"] = e.code
        return rec
    except Exception as e:
        rec["status"] = type(e).__name__
        return rec
    rec["bytes"] = len(data)
    rec["size"] = image_size(data)
    ext = "png" if data[:4] == b"\x89PNG" else "webp" if data[:4] == b"RIFF" else "gif" if data[:3] == b"GIF" else "jpg"
    if rec["size"]:
        name = f"{hashlib.sha1(url.encode()).hexdigest()[:16]}.{ext}"
        (IMG / name).write_bytes(data)
        rec["file"] = name
    elif "html" in ctype or len(data) < 500:
        rec["status"] = f"not an image ({ctype or len(data)} bytes)"
    if "exchange-cdn" in rec["host"]:
        time.sleep(PROVIDER_PAUSE)  # the Provider's own image host
    return rec


def ocr(records):
    """Runs scripts/artwork/ocr.swift over every saved image; adds text lines to each record."""
    tool = ART / "ocr"
    if not tool.exists():
        subprocess.run(["swiftc", "-O", "-o", str(tool), str(ROOT / "scripts" / "artwork" / "ocr.swift")], check=True)
    files = [r for r in records if r.get("file") and "text" not in r]
    for start in range(0, len(files), 40):
        batch = files[start:start + 40]
        out = subprocess.run([str(tool)] + [str(IMG / r["file"]) for r in batch], capture_output=True, text=True).stdout
        by_path = {}
        for line in out.splitlines():
            try:
                d = json.loads(line)
                by_path[Path(d["path"]).name] = d
            except json.JSONDecodeError:
                pass
        for r in batch:
            d = by_path.get(r["file"], {})
            lines = [l for l in d.get("lines", []) if l.get("conf", 0) >= 0.5 and len(l.get("text", "").strip()) >= 3]
            r["text"] = [l["text"] for l in lines]
            # The biggest line's height as a share of the image: a title treatment is tall, a credit block is tiny.
            r["textHeight"] = round(max((l["h"] for l in lines), default=0), 3)
        print(f"  ocr {min(start + 40, len(files))}/{len(files)}", file=sys.stderr)


def has_text(rec):
    """Printed text big enough to be a title or a tagline, not a credit block or a watermark."""
    return bool(rec.get("text")) and rec.get("textHeight", 0) >= 0.03


def load_listings(kind):
    rows = json.loads((RAW / f"{kind}_streams.json").read_bytes())
    return list(rows.values()) if isinstance(rows, dict) else rows


def pick(kind, rows, count, chart_ids):
    """Titles spread over Prefix languages and recency: a third with Listings in 4+ Prefixes, a third
    among the newest 1500, a third from the Charts the Provider has; others fill in at random."""
    by_title = defaultdict(list)
    for r in rows:
        t = str(r.get("tmdb") or "")
        if t.isdigit():
            by_title[t].append(r)
    added = lambda t: max(int(r.get("added") or r.get("last_modified") or 0) for r in by_title[t])
    rng = random.Random(7)
    wide = [t for t, ls in by_title.items() if len({prefix(l["name"]) for l in ls}) >= 4]
    newest = sorted(by_title, key=added, reverse=True)[:1500]
    charts = [t for t in chart_ids if t in by_title]
    chosen = []
    for pool, n in ((wide, count // 3), (newest, count // 3), (charts, count // 3)):
        pool = [t for t in pool if t not in chosen]
        rng.shuffle(pool)
        chosen += pool[:n]
    rest = [t for t in by_title if t not in chosen]
    rng.shuffle(rest)
    chosen += rest[:count - len(chosen)]
    return {t: by_title[t] for t in chosen}


def chart_ids_of(kind):
    f = OUT / "charts.json"
    if not f.exists():
        return []
    d = json.loads(f.read_text())
    ids = []
    for lst in (d.get("top", {}).get("movies" if kind == "movie" else "shows", []), d.get("trending", {}).get("movies" if kind == "movie" else "shows", [])):
        ids += [str(x["tmdbId"]) for x in lst]
    for g in d.get("genres", []):
        if g.get("kind") == kind:
            ids += [str(i) for i in g.get("tmdbIds", [])[:20]]
    return list(dict.fromkeys(ids))


def main():
    ART.mkdir(parents=True, exist_ok=True)
    IMG.mkdir(exist_ok=True)
    provider = Provider()
    images = {}
    titles = []
    for kind, file_kind, count in (("movie", "vod", MOVIES), ("show", "series", SHOWS)):
        picked = pick(kind, load_listings(file_kind), count, chart_ids_of(kind))
        print(f"{kind}: {len(picked)} Titles", file=sys.stderr)
        for n, (tmdb, listings) in enumerate(picked.items(), 1):
            t = {"kind": kind, "tmdb": tmdb, "name": listings[0]["name"], "listings": [], "details": None, "backdrops": [], "stills": None}
            for l in listings:
                url = l.get("stream_icon") if kind == "movie" else l.get("cover")
                t["listings"].append({"id": l.get("stream_id") or l.get("series_id"), "name": l["name"], "prefix": prefix(l["name"]), "poster": url or None})
                if url:
                    fetch_image(url, images)
            if kind == "movie":
                english = next((l for l in listings if prefix(l["name"]) in ("EN", "NF", "TOP", "4K-EN")), listings[0])
                data = provider.api(action="get_vod_info", vod_id=english["stream_id"])
                info = (data or {}).get("info") if isinstance(data, dict) else None
                if isinstance(info, dict):
                    t["details"] = {k: info.get(k) for k in ("backdrop_path", "cover_big", "movie_image", "youtube_trailer", "plot", "description", "cast", "actors", "genre", "director", "releasedate", "duration", "runtime", "rating", "country", "o_name", "name") if present(info.get(k))}
                    t["backdrops"] = urls(info.get("backdrop_path"))
                else:
                    t["details"] = {"error": "no info"}
            else:
                t["backdrops"] = urls(listings[0].get("backdrop_path"))
                t["details"] = {k: listings[0].get(k) for k in ("plot", "cast", "genre", "director", "releaseDate", "rating", "youtube_trailer", "episode_run_time") if present(listings[0].get(k))}
            if t["backdrops"]:
                fetch_image(t["backdrops"][0], images)
            titles.append(t)
            if n % 10 == 0:
                print(f"  {kind} {n}/{len(picked)}, {len(images)} images, {len(provider.calls)} Provider calls", file=sys.stderr)
    # Episode stills: the series_info answers scripts/survey.py already saved, plus a few fresh ones.
    stills = {"shows": 0, "episodes": 0, "withStill": 0, "sampleUrls": []}
    saved = list(RAW.glob("series_info_*.json"))
    fresh = [t for t in titles if t["kind"] == "show"][:8]
    answers = [json.loads(f.read_bytes()) for f in saved]
    for t in fresh:
        data = provider.api(action="get_series_info", series_id=t["listings"][0]["id"])
        if isinstance(data, dict):
            answers.append(data)
    for d in answers:
        eps = d.get("episodes")
        seasons = list(eps.values()) if isinstance(eps, dict) else eps if isinstance(eps, list) else []
        flat = [e for s in seasons for e in (s if isinstance(s, list) else [s]) if isinstance(e, dict)]
        if not flat:
            continue
        stills["shows"] += 1
        stills["episodes"] += len(flat)
        for e in flat:
            img = (e.get("info") or {}).get("movie_image") if isinstance(e.get("info"), dict) else None
            if present(img):
                stills["withStill"] += 1
                if len(stills["sampleUrls"]) < 12 and random.random() < 0.05:
                    stills["sampleUrls"].append(img)
    for u in stills["sampleUrls"]:
        fetch_image(u, images)
    print("recognising text…", file=sys.stderr)
    ocr(list(images.values()))
    (ART / "titles.json").write_text(json.dumps({"titles": titles, "images": images, "stills": stills, "calls": provider.calls}, ensure_ascii=False, default=str))
    report(titles, images, stills, provider)
    sheet(titles, images, stills)


def pct(n, d):
    return f"{100 * n / d:.0f}%" if d else "–"


def report(titles, images, stills, provider):
    L = [f"# Provider artwork survey", f"_Generated {datetime.now():%Y-%m-%d %H:%M}. {len(titles)} Titles, {len(images)} images, {len(provider.calls)} Provider catalog calls, no stream._", ""]
    for kind in ("movie", "show"):
        ts = [t for t in titles if t["kind"] == kind]
        if not ts:
            continue
        L += [f"## {kind}s ({len(ts)} Titles)", ""]
        posters = [images[l["poster"]] for t in ts for l in t["listings"] if l.get("poster") and l["poster"] in images]
        ok = [p for p in posters if p.get("size")]
        L.append(f"- Poster links on Listings: {sum(1 for t in ts for l in t['listings'] if l.get('poster'))} of {sum(len(t['listings']) for t in ts)}; fetched fine: {pct(len(ok), len(posters))}.")
        hosts = Counter(p["host"] for p in posters)
        L.append("- Hosts: " + ", ".join(f"{h} {c}" for h, c in hosts.most_common(6)))
        sizes = Counter(f"{p['size'][0]}×{p['size'][1]}" for p in ok)
        L.append("- Poster pixel sizes: " + ", ".join(f"{s} ×{c}" for s, c in sizes.most_common(6)))
        kb = sorted(p["bytes"] for p in ok)
        if kb:
            L.append(f"- Poster bytes: median {kb[len(kb) // 2] // 1024} KB, max {kb[-1] // 1024} KB")
        multi = [t for t in ts if len(t["listings"]) > 1]
        distinct = Counter(min(len({l["poster"] for l in t["listings"] if l.get("poster")}), 5) for t in multi)
        L.append(f"- Distinct poster files per Title with 2+ Listings ({len(multi)} Titles): " + ", ".join(f"{k if k < 5 else '5+'} → {pct(c, len(multi))}" for k, c in sorted(distinct.items())))
        # Does a Prefix language get its own poster?
        own = Counter()
        for t in multi:
            by_pre = defaultdict(set)
            for l in t["listings"]:
                if l.get("poster"):
                    by_pre[l["prefix"]].add(l["poster"])
            for pre, urls in by_pre.items():
                others = {u for p2, us in by_pre.items() if p2 != pre for u in us}
                if urls and not (urls & others):
                    own[pre] += 1
        L.append("- Prefixes whose Listings link a poster no other Prefix of the Title links (a poster of their own): " + ", ".join(f"{p} {c}" for p, c in own.most_common(12)))
        texted = [p for p in ok if has_text(p)]
        L.append(f"- Posters with printed text (Vision OCR, a line at least 3% of the height): {pct(len(texted), len(ok))}; textless posters: {pct(len(ok) - len(texted), len(ok))}")
        tl = [t for t in ts if any(not has_text(images[l['poster']]) for l in t["listings"] if l.get("poster") and images[l["poster"]].get("size"))]
        L.append(f"- Titles with at least one textless poster among their Listings: {pct(len(tl), len(ts))}")
        with_bd = [t for t in ts if t["backdrops"]]
        L.append(f"- Titles with a backdrop from the Provider: {pct(len(with_bd), len(ts))}; backdrops per Title: " + ", ".join(f"{k} → {c}" for k, c in sorted(Counter(min(len(t['backdrops']), 4) for t in ts).items())))
        bds = [images[t["backdrops"][0]] for t in with_bd if t["backdrops"][0] in images and images[t["backdrops"][0]].get("size")]
        if bds:
            bsz = Counter(f"{b['size'][0]}×{b['size'][1]}" for b in bds)
            L.append("- First backdrop pixel sizes: " + ", ".join(f"{s} ×{c}" for s, c in bsz.most_common(5)) + f"; median {sorted(b['bytes'] for b in bds)[len(bds) // 2] // 1024} KB")
            L.append(f"- First backdrops with printed text: {pct(sum(1 for b in bds if has_text(b)), len(bds))} (so textless: {pct(sum(1 for b in bds if not has_text(b)), len(bds))})")
        det = [t for t in ts if t["details"] and "error" not in t["details"]]
        if det:
            fields = Counter(k for t in det for k in t["details"])
            L.append(f"- Details answered for {len(det)} of {len(ts)}; fields: " + ", ".join(f"{k} {pct(c, len(det))}" for k, c in fields.most_common()))
        L.append("")
    if stills["episodes"]:
        L += [f"## Episode stills", "", f"- {stills['shows']} Shows, {stills['episodes']} Episodes, a still (`info.movie_image`) on {pct(stills['withStill'], stills['episodes'])}", ""]
    if provider.calls:
        ms = sorted(c[2] for c in provider.calls)
        L += ["## Provider calls", "", f"- {len(provider.calls)} calls, {Counter(c[0] for c in provider.calls)}, statuses {Counter(c[1] for c in provider.calls)}, median {ms[len(ms) // 2] * 1000:.0f} ms, max {ms[-1] * 1000:.0f} ms, {PROVIDER_PAUSE} s pause between calls", ""]
    if False:
        ms = []
        L += ["## Provider calls", "", f"- {len(provider.calls)} calls, {Counter(c[0] for c in provider.calls)}, statuses {Counter(c[1] for c in provider.calls)}, median {ms[len(ms) // 2] * 1000:.0f} ms, max {ms[-1] * 1000:.0f} ms, {PROVIDER_PAUSE} s pause between calls", ""]
    text = provider.scrub("\n".join(L) + "\n")
    (ART / "report.md").write_text(text)
    print(text)


def sheet(titles, images, stills):
    """A contact sheet: one row per Title (posters by Prefix, then the backdrop), text-bearing art marked."""
    def img_tag(rec, h):
        if not rec or not rec.get("file"):
            return f'<div class="missing" style="height:{h}px">{(rec or {}).get("status", "none")}</div>'
        mark = '<span class="t">T</span>' if has_text(rec) else ""
        return f'<div class="im"><img src="img/{rec["file"]}" style="height:{h}px">{mark}<small>{rec["size"][0]}×{rec["size"][1]} · {rec["bytes"] // 1024} KB</small></div>'
    rows = []
    for t in titles:
        seen, cells = set(), []
        for l in t["listings"]:
            u = l.get("poster")
            if not u or u in seen:
                continue
            seen.add(u)
            pres = sorted({x["prefix"] or "?" for x in t["listings"] if x.get("poster") == u})
            cells.append(f'<figure>{img_tag(images.get(u), 180)}<figcaption>{" ".join(pres)}</figcaption></figure>')
        bd = images.get(t["backdrops"][0]) if t["backdrops"] else None
        cells.append(f'<figure class="bd">{img_tag(bd, 180)}<figcaption>backdrop ({len(t["backdrops"])})</figcaption></figure>')
        rows.append(f'<section><h3>{t["kind"]} · {t["name"]} · TMDB {t["tmdb"]} · {len(t["listings"])} Listings</h3><div class="row">{"".join(cells)}</div></section>')
    still_cells = "".join(f'<figure>{img_tag(images.get(u), 120)}</figure>' for u in stills["sampleUrls"])
    html = f"""<!doctype html><meta charset="utf-8"><title>Provider artwork</title>
<style>body{{background:#07080A;color:#fff;font:12px Inter,system-ui;margin:16px}}h3{{font-weight:600;margin:14px 0 4px}}.row{{display:flex;gap:8px;overflow:hidden}}figure{{margin:0;position:relative}}figcaption{{color:#A9A9AF;font-size:11px;margin-top:2px}}img{{display:block;border-radius:6px}}.im{{position:relative}}.im small{{position:absolute;left:4px;bottom:4px;background:rgba(0,0,0,.6);padding:1px 4px;border-radius:3px;font-size:10px}}.t{{position:absolute;right:4px;top:4px;background:#FFB35C;color:#000;font-weight:700;padding:1px 5px;border-radius:3px}}.missing{{width:120px;background:rgba(255,255,255,.12);border-radius:6px;display:flex;align-items:center;justify-content:center;color:#A9A9AF;font-size:10px;text-align:center}}.bd{{margin-left:16px}}</style>
<h1>Provider artwork · {len(titles)} Titles</h1><p>T = printed text found by Vision OCR. Posters grouped by the Prefixes that link them; the backdrop is the first of the Title's.</p>
{"".join(rows)}
<section><h3>Episode stills (sample)</h3><div class="row">{still_cells}</div></section>"""
    (ART / "sheet.html").write_text(html)


if __name__ == "__main__":
    main()
