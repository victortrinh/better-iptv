#!/usr/bin/env python3
"""Survey A for M6: how much of the Provider's catalog Wikidata knows by TMDB ID.

Reads the Listings scripts/survey.py saved (.survey/raw/vod_streams.json and
series_streams.json), takes every distinct TMDB ID, and asks the Wikidata Query
Service in batches which IDs have an item (P4947 for movies, P4983 for shows)
and, for those, whether the item has an English label, a French label, an IMDb
ID (P345) and an original language (P364), and how many languages its labels
and aliases cover. Never touches the Provider. Polite: one query at a time, a
pause between them, a descriptive User-Agent.

Usage: python3 -I scripts/survey_wikidata.py [survey-dir]
Writes <survey-dir>/wikidata.json (per-ID answers) and prints the report.
"""

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
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / ".survey"
RAW = OUT / "raw"
ENDPOINT = "https://query.wikidata.org/sparql"
UA = "zugig-survey/0.1 (https://github.com/victortrinh/better-iptv; survey of TMDB-ID coverage)"
BATCH = 300
PAUSE = 1.0

QUERY = """
SELECT ?id (SAMPLE(?item) AS ?q) (SAMPLE(?en) AS ?labelEn) (SAMPLE(?fr) AS ?labelFr)
       (SAMPLE(?imdb) AS ?imdbId) (SAMPLE(?langCode) AS ?lang)
       (COUNT(DISTINCT ?anyLabelLang) AS ?labelLangs) (COUNT(DISTINCT ?alias) AS ?aliases)
WHERE {
  VALUES ?id { %s }
  ?item wdt:%s ?id .
  OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en) = "en") }
  OPTIONAL { ?item rdfs:label ?fr FILTER(LANG(?fr) = "fr") }
  OPTIONAL { ?item wdt:P345 ?imdb }
  OPTIONAL { ?item wdt:P364 ?langItem . ?langItem wdt:P218 ?langCode }
  OPTIONAL { ?item rdfs:label ?anyLabel BIND(LANG(?anyLabel) AS ?anyLabelLang) }
  OPTIONAL { ?item skos:altLabel ?alias }
}
GROUP BY ?id
"""


def present(v):
    return v not in (None, "", "0", 0)


def load(kind):
    rows = json.loads((RAW / f"{kind}_streams.json").read_bytes())
    if isinstance(rows, dict):
        rows = list(rows.values())
    titles = {}
    for row in rows:
        tmdb = row.get("tmdb")
        if not present(tmdb):
            continue
        tmdb = str(tmdb).strip()
        if not tmdb.isdigit():
            continue
        t = titles.setdefault(tmdb, {"listings": 0, "added": 0, "rating": 0.0, "name": row.get("name")})
        t["listings"] += 1
        added = row.get("added") or row.get("last_modified") or 0
        t["added"] = max(t["added"], int(added) if str(added).isdigit() else 0)
        try:
            t["rating"] = max(t["rating"], float(row.get("rating") or 0))
        except ValueError:
            pass
    return titles


def sparql(query):
    data = urllib.parse.urlencode({"query": query, "format": "json"}).encode()
    req = urllib.request.Request(ENDPOINT, data=data, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return json.loads(resp.read())["results"]["bindings"]
        except Exception as e:  # 429 or a timeout: back off and retry
            wait = 10 * (attempt + 1)
            print(f"  retry in {wait}s: {e}", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("Wikidata didn't answer four times in a row")


def ask(ids, prop, cache):
    todo = [i for i in ids if i not in cache]
    for start in range(0, len(todo), BATCH):
        batch = todo[start:start + BATCH]
        values = " ".join(f'"{i}"' for i in batch)
        found = {}
        for b in sparql(QUERY % (values, prop)):
            v = lambda k: b.get(k, {}).get("value")
            found[v("id")] = {
                "q": (v("q") or "").rsplit("/", 1)[-1],
                "en": v("labelEn"), "fr": v("labelFr"), "imdb": v("imdbId"), "lang": v("lang"),
                "labelLangs": int(v("labelLangs") or 0), "aliases": int(v("aliases") or 0),
            }
        for i in batch:
            cache[i] = found.get(i)
        done = start + len(batch)
        print(f"  {prop}: {done}/{len(todo)} asked, {sum(1 for i in todo[:done] if cache[i])} found", file=sys.stderr)
        time.sleep(PAUSE)


def report(kind, titles, answers, lines):
    def coverage(label, ids):
        n = len(ids)
        if not n:
            return
        hit = [answers[i] for i in ids if answers.get(i)]
        h = len(hit)
        pct = lambda k: f"{100 * k / n:.0f}%"
        en = sum(1 for a in hit if a["en"]); fr = sum(1 for a in hit if a["fr"])
        imdb = sum(1 for a in hit if a["imdb"]); lang = sum(1 for a in hit if a["lang"])
        langs = sorted(a["labelLangs"] for a in hit)
        median = langs[len(langs) // 2] if langs else 0
        lines.append(f"| {label} | {n} | {pct(h)} | {pct(en)} | {pct(fr)} | {pct(imdb)} | {pct(lang)} | {median} |")

    lines.append(f"### {kind}")
    lines.append("")
    lines.append("| Sample | Titles | Item | EN label | FR label | IMDb ID | Original language | Label languages (median) |")
    lines.append("|---|---|---|---|---|---|---|---|")
    ids = list(titles)
    coverage("All Titles", ids)
    by_added = sorted(ids, key=lambda i: -titles[i]["added"])
    coverage("Newest 500 (by `added`)", by_added[:500])
    coverage("Newest 2000", by_added[:2000])
    year_ago = datetime.now(timezone.utc).timestamp() - 365 * 86400
    recent_rated = [i for i in ids if titles[i]["added"] >= year_ago and titles[i]["rating"] >= 7]
    coverage("Added this year, rated 7+", recent_rated)
    popular = sorted(ids, key=lambda i: -titles[i]["listings"])
    coverage("500 with most Listings", popular[:500])
    charts = chart_ids(kind)
    coverage("Today's Charts the Provider has (Top 10, trending, genres)", [i for i in charts if i in titles])
    lines.append("")
    langs = Counter(answers[i]["lang"] for i in ids if answers.get(i) and answers[i]["lang"])
    lines.append("Original languages found: " + ", ".join(f"{l} {c}" for l, c in langs.most_common(12)))
    missing = [i for i in by_added[:500] if not answers.get(i)]
    lines.append("")
    lines.append("Newest 500 Titles with no item (sample): " + " · ".join(f"`{titles[i]['name']}` ({i})" for i in missing[:12]))
    lines.append("")


def chart_ids(kind):
    """The TMDB IDs the cloud's Charts show today for Canada (saved from POST /v1/charts to charts.json)."""
    f = OUT / "charts.json"
    if not f.exists():
        return []
    d = json.loads(f.read_text())
    key = "movies" if kind == "movie" else "shows"
    ids = [str(x["tmdbId"]) for x in d.get("top", {}).get(key, [])] + [str(x["tmdbId"]) for x in d.get("trending", {}).get(key, [])]
    for g in d.get("genres", []):
        if g.get("kind") == kind:
            ids += [str(i) for i in g.get("tmdbIds", [])[:30]]
    return list(dict.fromkeys(ids))


SITELINKS = """
SELECT ?q ?sitelinks ?en ?fr WHERE {
  VALUES ?q { %s }
  ?q wikibase:sitelinks ?sitelinks .
  OPTIONAL { ?en schema:about ?q ; schema:isPartOf <https://en.wikipedia.org/> }
  OPTIONAL { ?fr schema:about ?q ; schema:isPartOf <https://fr.wikipedia.org/> }
}
"""


def sitelinks(cache):
    """Second pass: how many Wikipedias cover each found item (a popularity proxy), and its English and
    French article names (for Wikipedia's daily top-1000 pageviews as a trending signal)."""
    todo = sorted({a["q"] for kind in cache.values() for a in kind.values() if a and a.get("q") and "sitelinks" not in a})
    found = {}
    for start in range(0, len(todo), 500):
        batch = todo[start:start + 500]
        for b in sparql(SITELINKS % " ".join(f"wd:{q}" for q in batch)):
            v = lambda k: b.get(k, {}).get("value")
            found[(v("q") or "").rsplit("/", 1)[-1]] = {"sitelinks": int(v("sitelinks") or 0), "enwiki": urllib.parse.unquote((v("en") or "").rsplit("/", 1)[-1]) or None, "frwiki": urllib.parse.unquote((v("fr") or "").rsplit("/", 1)[-1]) or None}
        print(f"  sitelinks: {min(start + 500, len(todo))}/{len(todo)}", file=sys.stderr)
        time.sleep(PAUSE)
    for kind in cache.values():
        for a in kind.values():
            if a and a.get("q"):
                a.update(found.get(a["q"], {"sitelinks": 0, "enwiki": None, "frwiki": None}))


def wikipedia_trending(cache, lines):
    """Of yesterday's 1000 most viewed English and French Wikipedia articles, how many are the Provider's Titles."""
    from datetime import timedelta
    day = datetime.now(timezone.utc) - timedelta(days=1)
    for wiki, key in (("en.wikipedia", "enwiki"), ("fr.wikipedia", "frwiki")):
        url = f"https://wikimedia.org/api/rest_v1/metrics/pageviews/top/{wiki}/all-access/{day:%Y/%m/%d}"
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                top = json.loads(resp.read())["items"][0]["articles"]
        except Exception as e:
            lines.append(f"Wikipedia top articles ({wiki}) unavailable: {e}")
            continue
        titles = {}
        for kind, answers in cache.items():
            for tmdb, a in answers.items():
                if a and a.get(key):
                    titles[a[key].replace(" ", "_")] = (kind, tmdb, a.get("en") or a.get("fr"))
        hits = [(art["article"], art["views"], titles[art["article"]]) for art in top if art["article"] in titles]
        lines.append(f"Wikipedia's 1000 most viewed {wiki} articles on {day:%Y-%m-%d} that are the Provider's Titles: {len(hits)}: " + " · ".join(f"{t[2]} ({t[0]}, {v:,} views)" for _, v, t in hits[:25]))
        lines.append("")


WBSEARCH = "https://www.wikidata.org/w/api.php"


def wb(params):
    req = urllib.request.Request(WBSEARCH + "?" + urllib.parse.urlencode({"format": "json", **params}), headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


PREFIX_LANG = {"EN": "en", "FR": "fr", "DE": "de", "ES": "es", "IT": "it", "NL": "nl", "PT": "pt", "GR": "el", "TR": "tr", "PL": "pl", "SE": "sv", "RO": "ro", "IR": "fa", "AR": "ar", "NF": "en", "TOP": "en", "4K-FR": "fr", "4K-DE": "de", "4K-EN": "en", "IN": "hi"}
NAME = re.compile(r"^\s*(?:[A-Z0-9+\-]{2,12}\s*[|:\-–]\s+)?(.*?)\s*(?:\((\d{4})\))?\s*$")


def name_lookup(kind, file_kind, prop, lines, count=150):
    """The 6%: can a Listing with no TMDB ID be found on Wikidata by name and year? Takes a sample,
    searches Wikidata's labels and aliases in the Prefix's language (wbsearchentities), keeps the hits
    with a TMDB ID of the right kind and a release year within one of the name's, and reports the rate."""
    rows = json.loads((RAW / f"{file_kind}_streams.json").read_bytes())
    rows = list(rows.values()) if isinstance(rows, dict) else rows
    rng = random.Random(7)
    noid = [r for r in rows if not present(r.get("tmdb")) or not str(r.get("tmdb")).strip().isdigit()]
    replays_and_clutter = [r for r in noid if (r.get("name") or "").startswith(("SOC ", "MV "))]
    noid = [r for r in noid if r not in replays_and_clutter]
    rng.shuffle(noid)
    sample = noid[:count]
    found = ambiguous = 0
    examples = []
    for r in sample:
        m = NAME.match(r.get("name") or "")
        name, year = (m.group(1), m.group(2)) if m else (r.get("name"), None)
        pre = (r.get("name") or "")[: (r.get("name") or "").find(" ")].strip("-:| ")
        lang = PREFIX_LANG.get(pre, "en")
        try:
            hits = wb({"action": "wbsearchentities", "search": name, "language": lang, "uselang": lang, "type": "item", "limit": 10}).get("search", [])
            time.sleep(PAUSE / 2)
            ids = [h["id"] for h in hits]
            cands = []
            if ids:
                ents = wb({"action": "wbgetentities", "ids": "|".join(ids), "props": "claims"}).get("entities", {})
                time.sleep(PAUSE / 2)
                for q, e in ents.items():
                    claims = e.get("claims", {})
                    tm = [c["mainsnak"]["datavalue"]["value"] for c in claims.get(prop, []) if c.get("mainsnak", {}).get("datavalue")]
                    if not tm:
                        continue
                    dates = [c["mainsnak"]["datavalue"]["value"]["time"][1:5] for c in claims.get("P577", []) if c.get("mainsnak", {}).get("datavalue")]
                    if year and dates and not any(abs(int(d) - int(year)) <= 1 for d in dates if d.isdigit()):
                        continue
                    cands.append((q, tm[0]))
        except Exception as e:
            print(f"  lookup failed for a Listing: {e}", file=sys.stderr)
            continue
        if len(cands) == 1:
            found += 1
            if len(examples) < 6:
                examples.append(f"`{r.get('name')}` → {cands[0][0]} (TMDB {cands[0][1]})")
        elif len(cands) > 1:
            ambiguous += 1
    n = len(sample)
    lines.append(f"### {kind} Listings without a TMDB ID, looked up by name on Wikidata ({n} sampled of {len(noid)}; {len(replays_and_clutter)} more are Sports replays or music videos)")
    lines.append("")
    lines.append(f"- One item with a TMDB ID and the right year: {100 * found / n:.0f}%; several candidates: {100 * ambiguous / n:.0f}%; none: {100 * (n - found - ambiguous) / n:.0f}%")
    lines.append("- Examples: " + " · ".join(examples))
    lines.append("")


def main():
    cache_path = OUT / "wikidata.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {"movie": {}, "show": {}}
    lines = [f"# Wikidata coverage of the Provider's TMDB IDs", f"_Generated {datetime.now():%Y-%m-%d %H:%M} from the Listings in {RAW}._", ""]
    for kind, file_kind, prop in (("movie", "vod", "P4947"), ("show", "series", "P4983")):
        titles = load(file_kind)
        print(f"{kind}: {len(titles)} distinct TMDB IDs", file=sys.stderr)
        ask(list(titles), prop, cache[kind])
        cache_path.write_text(json.dumps(cache))
        report(kind, titles, cache[kind], lines)
    sitelinks(cache)
    cache_path.write_text(json.dumps(cache))
    for kind, answers in cache.items():
        hit = [a for a in answers.values() if a]
        sl = sorted(a.get("sitelinks", 0) for a in hit)
        if sl:
            lines.append(f"{kind}s found: sitelinks median {sl[len(sl) // 2]}, 90th percentile {sl[int(len(sl) * 0.9)]}; with an English article {100 * sum(1 for a in hit if a.get('enwiki')) / len(hit):.0f}%, French {100 * sum(1 for a in hit if a.get('frwiki')) / len(hit):.0f}%")
    lines.append("")
    wikipedia_trending(cache, lines)
    for kind, file_kind, prop in (("movie", "vod", "P4947"), ("show", "series", "P4983")):
        name_lookup(kind, file_kind, prop, lines)
    text = "\n".join(lines) + "\n"
    (OUT / "wikidata.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
