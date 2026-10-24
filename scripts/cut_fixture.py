#!/usr/bin/env python3
"""Cut a small, sanitized test fixture from the survey's catalog snapshot.

Reads the git-ignored .survey/raw/ written by scripts/survey.py and writes the
fake Provider's fixture to core/src/test/resources/fixture/. The fixture keeps
real Listing names and Categories (the groups the spec's tests rely on) but
strips image hosts, direct sources and trailers, renumbers every Provider id,
so nothing identifies the Provider or the Viewer. (The fake Provider makes up
its own account answer.)

Usage: python3 -I scripts/cut_fixture.py [--raw PATH]   (default: .survey/raw)
"""

import json
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / ".survey" / "raw"
OUT = ROOT / "core" / "src" / "test" / "resources" / "fixture"

# Groups the spec's fixture must cover, matched on Listing name or Category name.
GROUPS = [
    ("34-Listing Ballerina group", lambda m, cat: re.search(r"\bBallerina\b", m["name"]) and "2025" in m["name"], 99),
    ("cross-language Godfather group", lambda m, cat: str(m.get("tmdb")) in ("238", "240"), 99),
    ("cross-language Oz group", lambda m, cat: str(m.get("tmdb")) == "68728", 99),
    ("Québec French", lambda m, cat: m["name"].startswith("QC - "), 3),
    ("Untagged NF", lambda m, cat: m["name"].startswith("NF - "), 4),
    ("CAM", lambda m, cat: m["name"].startswith("EN-CAM") or "CAM" in cat.split(), 3),
    ("Audio-description", lambda m, cat: "AUDIO-DESCRIPTION" in cat, 3),
    ("Sports replay", lambda m, cat: m["name"].startswith("SOC - "), 3),
    ("music video", lambda m, cat: m["name"].startswith("MV - "), 3),
    ("no TMDB ID", lambda m, cat: str(m.get("tmdb") or "") in ("", "0") and not m["name"].startswith(("SOC", "MV")), 4),
]
SAMPLE = 30  # plus a random sample of everything else


def main():
    raw = Path(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[1] == "--raw" else RAW
    movies = json.loads((raw / "vod_streams.json").read_text())
    categories = {c["category_id"]: c for c in json.loads((raw / "vod_categories.json").read_text())}

    rng = random.Random(2)
    picked, seen = [], set()
    for label, match, cap in GROUPS:
        hits = [m for m in movies if m["stream_id"] not in seen and match(m, categories[m["category_id"]]["category_name"])]
        if len(hits) > cap:
            hits = rng.sample(hits, cap)
        print(f"{label}: {len(hits)}")
        for m in hits:
            seen.add(m["stream_id"])
            picked.append(m)
    rest = [m for m in movies if m["stream_id"] not in seen]
    picked += rng.sample(rest, SAMPLE)
    rng.shuffle(picked)

    category_ids = {}
    for m in picked:
        category_ids.setdefault(m["category_id"], str(len(category_ids) + 1))
    out_categories = [
        {"category_id": new, "category_name": categories[old]["category_name"], "parent_id": 0}
        for old, new in category_ids.items()
    ]
    out_movies = []
    for i, m in enumerate(picked, start=1):
        out_movies.append({
            "num": i,
            "name": m["name"],
            "stream_type": "movie",
            "stream_id": 1000 + i,
            "stream_icon": "",
            "rating": m.get("rating", ""),
            "rating_5based": m.get("rating_5based", 0),
            "tmdb": m.get("tmdb", ""),
            "trailer": "",
            "added": m.get("added", ""),
            "is_adult": 0,
            "category_id": category_ids[m["category_id"]],
            "category_ids": [int(category_ids[m["category_id"]])],
            "container_extension": m.get("container_extension", "mkv"),
            "custom_sid": None,
            "direct_source": "",
        })

    OUT.mkdir(parents=True, exist_ok=True)
    write(OUT / "vod_categories.json", out_categories)
    write(OUT / "vod_streams.json", out_movies)
    print(f"wrote {len(out_movies)} movie Listings in {len(out_categories)} Categories to {OUT.relative_to(ROOT)}")


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")


if __name__ == "__main__":
    main()
