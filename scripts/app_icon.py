#!/usr/bin/env python3
"""Write the launcher icon and the TV banner as vector drawables (#66).

The mark is a glass cloud behind a solid white cloud on the charcoal ground:
Verre's translucent glass plus the one solid white thing. It is drawn on a
512 x 512 master canvas and placed by <group> transforms, so every resource
shares one geometry. The banner sets "NuageTivi" in the app's Inter Bold,
turned into outlines because a vector drawable can't hold text.

Writes:
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml   adaptive icon (API 26+)
  app/src/main/res/mipmap-anydpi/ic_launcher.xml       legacy icon (API 25)
  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/main/res/drawable/ic_launcher_monochrome.xml themed icons (API 33+)
  app/src/main/res/drawable/banner.xml                 320 x 180 android:banner
  app/src/main/res/values/ic_launcher.xml              the ground colour

Usage: python3 scripts/app_icon.py   (needs fonttools: pip install fonttools)
"""

import math
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

GROUND = "#FF16171B"  # charcoal, a step above Colors.ground so the icon isn't a hole
CLOUD = "#FFF2F2F4"   # off-white
GLASS = "#FF4A4B50"   # white glass over the ground, flattened to an opaque colour

# Three bumps on a flat base, as (cx, cy, r) on the 512 master. The left and
# right bumps sit on the base line y = 450.
CLOUD_BUMPS = ((116, 380, 70), (246, 292, 128), (378, 362, 88))


def scaled(bumps, k, dx, dy):
    """Scale about the middle of the base line, then move."""
    return tuple((256 + (x - 256) * k + dx, 450 + (y - 450) * k + dy, r * k) for x, y, r in bumps)


BACK = scaled(CLOUD_BUMPS, 0.8, 60, -36)
FRONT = scaled(CLOUD_BUMPS, 0.86, -16, 22)


def n(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def upper_intersection(c1, c2):
    (x1, y1, r1), (x2, y2, r2) = c1, c2
    d = math.hypot(x2 - x1, y2 - y1)
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(r1 * r1 - a * a)
    xm, ym = x1 + a * (x2 - x1) / d, y1 + a * (y2 - y1) / d
    p1 = (xm + h * (y2 - y1) / d, ym - h * (x2 - x1) / d)
    p2 = (xm - h * (y2 - y1) / d, ym + h * (x2 - x1) / d)
    return min(p1, p2, key=lambda p: p[1])


def cloud_path(bumps):
    L, M, R = bumps
    a, b = upper_intersection(L, M), upper_intersection(M, R)
    return (f"M{n(L[0])},{n(L[1] + L[2])}"
            f"A{n(L[2])},{n(L[2])} 0 0 1 {n(a[0])},{n(a[1])}"
            f"A{n(M[2])},{n(M[2])} 0 0 1 {n(b[0])},{n(b[1])}"
            f"A{n(R[2])},{n(R[2])} 0 0 1 {n(R[0])},{n(R[1] + R[2])}Z")


def cloud_points(bumps):
    """The outline sampled, for fitting the mark inside a safe zone."""
    L, M, R = bumps
    pts = []
    for cx, cy, r in bumps:
        for i in range(360):
            t = math.radians(i)
            p = (cx + r * math.cos(t), cy + r * math.sin(t))
            if p[1] <= L[1] + L[2]:
                pts.append(p)
    return pts


def mark(clouds_only_front=False):
    paths = [] if clouds_only_front else [(cloud_path(BACK), GLASS)]
    return paths + [(cloud_path(FRONT), CLOUD)]


def path_xml(d, colour, indent):
    return f'{indent}<path\n{indent}    android:fillColor="{colour}"\n{indent}    android:pathData="{d}" />\n'


def group_xml(paths, scale, tx, ty, indent="    "):
    out = (f'{indent}<group\n{indent}    android:scaleX="{scale:.5f}"\n{indent}    android:scaleY="{scale:.5f}"\n'
           f'{indent}    android:translateX="{n(tx)}"\n{indent}    android:translateY="{n(ty)}">\n')
    for d, colour in paths:
        out += path_xml(d, colour, indent + "    ")
    return out + f"{indent}</group>\n"


def vector(width, height, body, comment):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Written by scripts/app_icon.py; edit that, not this. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width}dp"\n    android:height="{height}dp"\n'
            f'    android:viewportWidth="{width}"\n    android:viewportHeight="{height}">\n{body}</vector>\n')


def fit(radius, centre):
    """Scale and offset that put the whole mark inside a circle of `radius` around `centre`."""
    pts = cloud_points(BACK) + cloud_points(FRONT)
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    far = max(math.hypot(x - cx, y - cy) for x, y in pts)
    k = radius / far
    return k, centre - cx * k, centre - cy * k


def text_outline(font, s, x, baseline, size, tracking):
    gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
    pen = SVGPathPen(gs)
    k = size / upm
    cursor = 0
    for ch in s:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (k, 0, 0, -k, x + cursor, baseline)))
        cursor += gs[g].width * k + tracking * size
    return pen.getCommands(), cursor - tracking * size


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print(path.relative_to(ROOT))


def main():
    # Adaptive icon: 108 dp layers, of which a launcher may show as little as the
    # 66 dp circle in the middle. The mark keeps inside it with a little air.
    k, tx, ty = fit(30, 54)
    write(RES / "drawable" / "ic_launcher_foreground.xml",
          vector(108, 108, group_xml(mark(), k, tx, ty), "Adaptive icon foreground: the glass cloud and the white cloud."))
    write(RES / "drawable" / "ic_launcher_monochrome.xml",
          vector(108, 108, group_xml(mark(clouds_only_front=True), k, tx, ty),
                 "Themed icon: only the front cloud, since the system tints every layer one colour."))
    write(RES / "values" / "ic_launcher.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{GROUND}</color>\n</resources>\n')
    write(RES / "mipmap-anydpi-v26" / "ic_launcher.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@color/ic_launcher_background" />\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
          '</adaptive-icon>\n')

    # Legacy icon for API 25 (Fire OS 6): a full-bleed square, the launcher masks it.
    k, tx, ty = fit(19, 24)
    body = path_xml("M0,0h48v48h-48z", GROUND, "    ") + group_xml(mark(), k, tx, ty)
    write(RES / "mipmap-anydpi" / "ic_launcher.xml", vector(48, 48, body, "Launcher icon below API 26."))

    # Banner: the mark, then the wordmark, centred together on the 320 x 180 card.
    font = TTFont(RES / "font" / "inter_700.ttf")
    size, tracking = 38, -0.03  # Inter Bold tracked like Type.display
    _, word_w = text_outline(font, "NuageTivi", 0, 0, size, tracking)
    mark_w, gap = 66, 10
    x0 = (320 - (mark_w + gap + word_w)) / 2
    middle = 92  # the wordmark's x-height band
    k = mark_w / 512
    word, _ = text_outline(font, "NuageTivi", x0 + mark_w + gap, middle + size * 0.36, size, tracking)
    body = (path_xml("M0,0h320v180h-320z", GROUND, "    ")
            + group_xml(mark(), k, x0, middle - 307 * k)
            + path_xml(word, CLOUD, "    "))
    write(RES / "drawable" / "banner.xml",
          vector(320, 180, body, "TV banner (android:banner): the mark and NuageTivi in Inter Bold."))


if __name__ == "__main__":
    main()
