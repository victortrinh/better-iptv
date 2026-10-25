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

# Added after the first cut and appended to its end, so the Listings above keep their numbers.
EXTRA_GROUPS = [
    # `IN-GU - Mohgiben.MBBS.2023` carries Munna Bhai M.B.B.S.' TMDB ID: a Mismatch.
    ("Mismatch under Munna Bhai M.B.B.S.", lambda m, cat: str(m.get("tmdb")) == "19625", 99),
    # `EN - Titanic  (1996)` has no ID, and a year one off TMDB's.
    ("Titanic", lambda m, cat: str(m.get("tmdb")) == "597" and m["name"].startswith(("EN - ", "4K-EN - ", "FR - ")), 3),
    ("ID-less Titanic", lambda m, cat: m["name"] == "EN - Titanic  (1996)", 1),
    # `GR - Forrest Gump (1994)` carries an IMDb ID instead.
    ("Forrest Gump", lambda m, cat: str(m.get("tmdb")) == "13", 2),
    ("ID-less Forrest Gump", lambda m, cat: m["name"] == "GR - Forrest Gump (1994)", 1),
]


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
    sample = rng.sample(rest, SAMPLE)
    seen.update(m["stream_id"] for m in sample)
    picked += sample
    rng.shuffle(picked)
    for label, match, cap in EXTRA_GROUPS:
        hits = sorted((m for m in movies if m["stream_id"] not in seen and match(m, categories[m["category_id"]]["category_name"])), key=lambda m: m["stream_id"])[:cap]
        print(f"{label}: {len(hits)}")
        for m in hits:
            seen.add(m["stream_id"])
            picked.append(m)

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
    cut_shows(raw)


# Show groups, matched on the Provider's series list entry. Those marked True also get their
# series details (get_series_info), which must be in the survey's raw output.
SHOW_GROUPS = [
    # Every Game of Thrones Listing, among them `FR - Trotro`, which carries its ID: a Mismatch.
    ("Game of Thrones and FR - Trotro", lambda s, cat: str(s.get("tmdb")) == "1399", 99, False),
    # Five Versions covering 1 to 4 seasons; most report English audio whatever their Prefix says.
    ("The Chelsea Detective", lambda s, cat: str(s.get("tmdb")) == "156240", 99, True),
    # Untagged (`D+`), with English audio in its Episode details.
    ("Dog: Impossible", lambda s, cat: str(s.get("tmdb")) == "93422", 1, True),
    ("no TMDB ID", lambda s, cat: str(s.get("tmdb") or "") in ("", "0"), 3, False),
]
SHOW_SAMPLE = 15


def cut_shows(raw):
    shows = json.loads((raw / "series_streams.json").read_text())
    categories = {c["category_id"]: c for c in json.loads((raw / "series_categories.json").read_text())}

    rng = random.Random(8)
    picked, detailed, seen = [], set(), set()
    for label, match, cap, details in SHOW_GROUPS:
        hits = sorted((s for s in shows if s["series_id"] not in seen and match(s, categories[s["category_id"]]["category_name"])), key=lambda s: s["series_id"])
        if len(hits) > cap:
            hits = rng.sample(hits, cap)
        print(f"{label}: {len(hits)}")
        for s in hits:
            seen.add(s["series_id"])
            picked.append(s)
            if details:
                detailed.add(s["series_id"])
    rest = [s for s in shows if s["series_id"] not in seen]
    picked += rng.sample(rest, SHOW_SAMPLE)
    rng.shuffle(picked)

    category_ids = {}
    for s in picked:
        category_ids.setdefault(s["category_id"], str(len(category_ids) + 1))
    out_categories = [
        {"category_id": new, "category_name": categories[old]["category_name"], "parent_id": 0}
        for old, new in category_ids.items()
    ]
    out_shows = []
    episode_ids = iter(range(100_001, 1_000_000))
    for i, s in enumerate(picked, start=1):
        series_id = 2000 + i
        out_shows.append({
            "num": i,
            "name": s["name"],
            "series_id": series_id,
            "cover": "",
            "plot": "",
            "cast": "",
            "director": "",
            "genre": s.get("genre", ""),
            "releaseDate": s.get("releaseDate", ""),
            "release_date": s.get("release_date", ""),
            "last_modified": s.get("last_modified", ""),
            "rating": s.get("rating", ""),
            "rating_5based": s.get("rating_5based", 0),
            "backdrop_path": [],
            "youtube_trailer": "",
            "tmdb": s.get("tmdb", ""),
            "episode_run_time": s.get("episode_run_time", ""),
            "category_id": category_ids[s["category_id"]],
            "category_ids": [int(category_ids[s["category_id"]])],
        })
        if s["series_id"] in detailed:
            info = json.loads((raw / f"series_info_{s['series_id']}.json").read_text())
            write(OUT / f"series_info_{series_id}.json", series_details(info, s, episode_ids))

    write(OUT / "series_categories.json", out_categories)
    write(OUT / "series_streams.json", out_shows)
    print(f"wrote {len(out_shows)} show Listings in {len(out_categories)} Categories, {len(detailed)} with series details")


def series_details(info, show, episode_ids):
    """The series details, keeping what the app reads of each Episode: numbers, title, and its video and first audio Track."""
    def episode(e):
        details = e.get("info") if isinstance(e.get("info"), dict) else {}
        video = details.get("video") if isinstance(details.get("video"), dict) else {}
        audio = details.get("audio") if isinstance(details.get("audio"), dict) else {}
        language = (audio.get("tags") or {}).get("language")
        return {
            "id": str(next(episode_ids)),
            "episode_num": e["episode_num"],
            "title": e.get("title", ""),
            "container_extension": e.get("container_extension", "mkv"),
            "info": {
                "duration_secs": details.get("duration_secs"),
                "duration": details.get("duration"),
                "video": {k: video[k] for k in ("codec_name", "width", "height", "color_transfer") if k in video},
                "audio": {**{k: audio[k] for k in ("codec_name", "channels") if k in audio}, "tags": {"language": language} if language else {}},
                "bitrate": details.get("bitrate"),
            } if details else [],
            "custom_sid": None,
            "added": e.get("added", ""),
            "season": e.get("season"),
            "direct_source": "",
        }

    episodes = info.get("episodes") or {}
    return {
        "seasons": [
            {k: season[k] for k in ("name", "episode_count", "air_date", "season_number") if k in season}
            for season in info.get("seasons") or []
        ],
        "info": {"name": show["name"], "tmdb": show.get("tmdb", ""), "last_modified": show.get("last_modified", "")},
        "episodes": {number: [episode(e) for e in season] for number, season in episodes.items()},
    }


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")


if __name__ == "__main__":
    main()
