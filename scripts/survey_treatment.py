#!/usr/bin/env python3
"""Survey C for ADR 0016: how often a Title treatment can be cut from the Provider's poster.

Reads the Titles and images scripts/survey_artwork.py saved (.survey/artwork), draws 60 posters (the 20 Titles with
the most Listings, the 20 newest, 20 at random, seeded), runs scripts/artwork/cutout.swift over each (Vision text
recognition, crop, colour key) and applies the checks the app applies before showing a cut-out:
- name: at least half of the Title's name words (3+ letters, articles dropped, diacritics folded) must appear in the
  recognised text, each allowing one misread letter in three, else the crop is a badge, a tagline or credits and the
  Title falls back to its name in type;
- visibility: the kept pixels must average at least 0.30 in luma or in chroma, else the title is dark and would
  vanish on the hero's ground (a saturated red or purple title is dark in luma but shows);
- coverage: between 3% and 70% of the crop kept, else the key found no title or kept the whole poster.
Writes .survey/artwork/treatment/report.md, three contact sheets (sheet1-3.html, render with headless Chrome) and
the cut-outs. No network. Usage: python3 -I scripts/survey_treatment.py [survey-dir]
"""

import html
import json
import random
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / ".survey"
ART = OUT / "artwork"
DEST = ART / "treatment"
SAMPLE = 20
VISIBILITY_FLOOR = 0.30
STOPWORDS = set("the and for from with les des une und der die das del los las van het".split())
COVERAGE = (0.03, 0.70)


def fold(s):
    s = unicodedata.normalize("NFKD", s)
    return re.sub(r"[^a-z0-9 ]", " ", "".join(c for c in s if not unicodedata.combining(c)).lower())


def name_words(name):
    return sorted({w for w in fold(name).split() if (len(w) >= 3 or w.isdigit()) and w not in STOPWORDS})


def edits(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def word_found(w, tokens, text):
    if w in text:
        return True
    return any(edits(w, t) * 3 <= len(w) for t in tokens if abs(len(t) - len(w)) * 3 <= len(w))


def name_matches(name, text):
    words = name_words(name)
    if not words:
        return True
    folded = fold(text); tokens = folded.split()
    found = [w for w in words if word_found(w, tokens, folded)]
    return len(found) * 2 >= len(words)


def draw(titles, images):
    rows = []
    for x in titles:
        ls = x["listings"]
        en = [l for l in ls if (l.get("prefix") or "") in ("EN", "NF", "4K-EN", "EN-TOP", "UNV")]
        pi = images.get((en or ls)[0]["poster"])
        if not pi or pi.get("status") != 200 or pi["size"][0] < 500:
            continue
        d = x.get("details") or {}
        name = d.get("name") or re.sub(r"(\s*\([^)]*\))+\s*$", "", x["name"].split(" - ", 1)[-1]).strip()
        rows.append(dict(kind=x["kind"], name=name, n=len(ls), date=d.get("releasedate") or d.get("releaseDate") or "", file=pi["file"].split(".")[0]))
    seen, picked = set(), []

    def take(seq, tag):
        c = 0
        for r in seq:
            if r["file"] in seen or c >= SAMPLE:
                continue
            seen.add(r["file"]); r["tag"] = tag; picked.append(r); c += 1

    take(sorted(rows, key=lambda r: -r["n"]), "popular")
    take(sorted(rows, key=lambda r: r["date"], reverse=True), "newest")
    rest = [r for r in rows if r["file"] not in seen]
    random.seed(398); random.shuffle(rest); take(rest, "random")
    return picked


def tool():
    exe = DEST / "cutout"
    src = ROOT / "scripts" / "artwork" / "cutout.swift"
    if not exe.exists() or exe.stat().st_mtime < src.stat().st_mtime:
        subprocess.run(["swiftc", "-O", "-o", str(exe), str(src)], check=True, capture_output=True)
    return exe


def judge(r, res):
    if not res or res.get("error"):
        return "no text"
    if not name_matches(r["name"], res["text"]):
        return "not the name"
    if max(res["luma"], res["chroma"]) < VISIBILITY_FLOOR:
        return "dark title"
    if not COVERAGE[0] <= res["coverage"] <= COVERAGE[1]:
        return "key failed"
    return "accepted"


def sheets(sample):
    css = """body{margin:0;background:#111;color:#fff;font-family:Inter,system-ui;width:1900px;padding:10px}
.r{display:grid;grid-template-columns:40px 90px 430px 430px 430px 400px;gap:10px;align-items:center;height:130px;margin-bottom:6px}
.r img.p{width:86px;height:129px;object-fit:cover;border-radius:6px}
.c{height:128px;display:flex;align-items:center;justify-content:center;border-radius:6px;overflow:hidden}.c img{max-width:420px;max-height:118px}
.g{background:#07080A}.m{background:#777}.k{background:repeating-conic-gradient(#3a3a3a 0 25%,#555 0 50%) 0 0/20px 20px}
.n{font-size:16px;line-height:22px}.n small{color:#aaa;display:block}.i{font-size:18px;color:#aaa;text-align:right}.no{color:#f66}"""
    for part in range(3):
        rows = ""
        for i, r in enumerate(sample[part * SAMPLE:(part + 1) * SAMPLE]):
            f, res, verdict = r["file"], r["result"], r["verdict"]
            has = res and not res.get("error")
            cells = (f'<div class="c k"><img src="cut/{f}-crop.png"></div><div class="c g"><img src="cut/{f}-key.png"></div>'
                     f'<div class="c m"><img src="cut/{f}-key.png"></div>') if has else '<div class="c k"></div><div class="c g"></div><div class="c m"></div>'
            facts = f"OCR: {html.escape(res['text'])} · luma {res['luma']:.2f} · chroma {res['chroma']:.2f} · kept {res['coverage']:.0%}" if has else "no text found"
            v = verdict if verdict == "accepted" else f'<span class="no">{verdict}</span>'
            rows += (f'<div class="r"><div class="i">{part * SAMPLE + i + 1}</div><img class="p" src="../img/{f}.jpg">{cells}'
                     f'<div class="n">{html.escape(r["name"])} · {v}<small>{r["tag"]} · {r["kind"]} · {facts}</small></div></div>')
        (DEST / f"sheet{part + 1}.html").write_text(f"<!doctype html><meta charset=utf-8><style>{css}</style>{rows}")


def main():
    data = json.loads((ART / "titles.json").read_text())
    sample = draw(data["titles"], data["images"])
    (DEST / "cut").mkdir(parents=True, exist_ok=True)
    exe = tool()
    for r in sample:
        p = subprocess.run([str(exe), str(ART / "img" / f"{r['file']}.jpg"), str(DEST / "cut" / r["file"])], capture_output=True, text=True)
        r["result"] = json.loads(p.stdout.strip() or "{}") if p.returncode == 0 else {"error": p.stderr[:200]}
        r["verdict"] = judge(r, r["result"])
    sheets(sample)
    verdicts = {}
    for r in sample:
        verdicts.setdefault(r["verdict"], []).append(r)
    lines = ["# Title treatments cut from the Provider's posters", "",
             f"_{len(sample)} posters: {SAMPLE} most-listed Titles, {SAMPLE} newest, {SAMPLE} at random (seed 398), the EN Listing's poster, 500 px wide or more._", "",
             "| Verdict | Popular | Newest | Random | All |", "|---|---|---|---|---|"]
    for v in ("accepted", "not the name", "dark title", "key failed", "no text"):
        rs = verdicts.get(v, [])
        lines.append(f"| {v} | " + " | ".join(str(sum(1 for r in rs if r['tag'] == t)) for t in ("popular", "newest", "random")) + f" | {len(rs)} |")
    lines += ["", f"Checks: at least half the name's words in the recognised text (one misread letter in three allowed); kept pixels at least {VISIBILITY_FLOOR} in luma or chroma; {COVERAGE[0]:.0%}–{COVERAGE[1]:.0%} of the crop kept.", "",
              "## Rejected", ""]
    for r in sample:
        if r["verdict"] != "accepted":
            res = r["result"]; t = res.get("text", "") if res else ""
            lines.append(f"- {r['name']} ({r['tag']}): {r['verdict']}; OCR \"{t}\"" + (f", luma {res['luma']:.2f}, chroma {res['chroma']:.2f}, kept {res['coverage']:.0%}" if res and not res.get("error") else ""))
    lines += ["", "## Accepted", ""] + [f"- {r['name']} ({r['tag']}): \"{r['result']['text']}\", luma {r['result']['luma']:.2f}, chroma {r['result']['chroma']:.2f}, kept {r['result']['coverage']:.0%}" for r in verdicts.get("accepted", [])]
    (DEST / "report.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines[:12]))


if __name__ == "__main__":
    main()
