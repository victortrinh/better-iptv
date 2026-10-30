#!/usr/bin/env python3
"""Write the launcher icon and the TV banner as vector drawables (#66, #104).

The mark is a solid white lowercase z on a glass disc, on the charcoal ground:
Verre's translucent glass plus the one solid white thing. The banner's "zugig" ends
in a grey dot raised to cap height, the same dot the in-app Wordmark draws. It is drawn on a
512 x 512 master canvas and placed by <group> transforms, so every resource
shares one geometry. The z and the banner's "zugig" are the app's Inter Bold,
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

from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
FONT = TTFont(RES / "font" / "inter_700.ttf")

GROUND = "#FF16171B"  # charcoal, a step above Colors.ground so the icon isn't a hole
WHITE = "#FFF2F2F4"   # off-white
GLASS = "#FF4A4B50"   # white glass over the ground, flattened to an opaque colour
DOT_GREY = "#FF77787E"  # the Wordmark's dot: a step back from the white letters

WORD = "zugig"
TRACKING = -0.03  # Inter Bold tracked like Type.display

# The Wordmark's dot, in em: 1.35 x Inter's own i-dot, 0.4 i-dots clear of the g's ink,
# its top on the cap height. The Wordmark composable uses the same numbers.
DOT_EM = 0.2169
DOT_GAP_EM = 0.0643

# The disc fills the 512 master; the z's x-height is 45% of it, its ink centred.
DISC = "M256,0A256,256 0 1 1 256,512A256,256 0 1 1 256,0Z"
Z_HEIGHT = 230


def n(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def text_outline(s, x, baseline, size, tracking=0.0):
    gs, cmap, upm = FONT.getGlyphSet(), FONT.getBestCmap(), FONT["head"].unitsPerEm
    pen = SVGPathPen(gs)
    k = size / upm
    cursor = 0
    for ch in s:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (k, 0, 0, -k, x + cursor, baseline)))
        cursor += gs[g].width * k + tracking * size
    return pen.getCommands(), cursor - tracking * size


def ink_bounds(s, size, tracking=0.0):
    """The ink box of `s` set at the origin, y down: (left, top, right, bottom)."""
    gs, cmap, upm = FONT.getGlyphSet(), FONT.getBestCmap(), FONT["head"].unitsPerEm
    k, cursor, box = size / upm, 0, None
    for ch in s:
        g = cmap[ord(ch)]
        pen = BoundsPen(gs)
        gs[g].draw(pen)
        if pen.bounds:
            l, b, r, t = pen.bounds
            gb = (cursor + l * k, -t * k, cursor + r * k, -b * k)
            box = gb if box is None else (min(box[0], gb[0]), min(box[1], gb[1]), max(box[2], gb[2]), max(box[3], gb[3]))
        cursor += gs[g].width * k + tracking * size
    return box


def centred_text(s, size, cx, cy, tracking=0.0):
    l, t, r, b = ink_bounds(s, size, tracking)
    d, _ = text_outline(s, cx - (l + r) / 2, cy - (t + b) / 2, size, tracking)
    return d


def wordmark(size, cx, cy, tracking=0.0):
    """ "zugig" and its dot, their ink centred together on (cx, cy): (word path, dot path)."""
    l, t, r, b = ink_bounds(WORD, size, tracking)
    d = DOT_EM * size
    dot_l = r + DOT_GAP_EM * size
    dot_t = -FONT["OS/2"].sCapHeight * size / FONT["head"].unitsPerEm
    ox, oy = round(cx - (l + dot_l + d) / 2, 2), round(cy - (min(t, dot_t) + b) / 2, 2)
    word, _ = text_outline(WORD, ox, oy, size, tracking)
    rad, x, y = d / 2, ox + dot_l + d / 2, oy + dot_t + d / 2
    dot = f"M{n(x)},{n(y - rad)}A{n(rad)},{n(rad)} 0 1 1 {n(x)},{n(y + rad)}A{n(rad)},{n(rad)} 0 1 1 {n(x)},{n(y - rad)}Z"
    return word, dot


def mark(monochrome=False):
    size = Z_HEIGHT * FONT["head"].unitsPerEm / FONT["OS/2"].sxHeight
    z = centred_text("z", size, 256, 256)
    return [(z, WHITE)] if monochrome else [(DISC, GLASS), (z, WHITE)]


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
    """Scale and offset that put the disc on a circle of `radius` around `centre`."""
    k = radius / 256
    return k, centre - 256 * k, centre - 256 * k


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print(path.relative_to(ROOT))


def main():
    # Adaptive icon: 108 dp layers, of which a launcher may show as little as the
    # 66 dp circle in the middle. The disc keeps inside it with a little air.
    k, tx, ty = fit(30, 54)
    write(RES / "drawable" / "ic_launcher_foreground.xml",
          vector(108, 108, group_xml(mark(), k, tx, ty), "Adaptive icon foreground: the glass disc and the white z."))
    write(RES / "drawable" / "ic_launcher_monochrome.xml",
          vector(108, 108, group_xml(mark(monochrome=True), k, tx, ty),
                 "Themed icon: only the z, since the system tints every layer one colour."))
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

    # Banner: the wordmark and its dot alone, their ink centred on the 320 x 180 card.
    word, dot = wordmark(64, 160, 90, TRACKING)
    body = path_xml("M0,0h320v180h-320z", GROUND, "    ") + path_xml(word, WHITE, "    ") + path_xml(dot, DOT_GREY, "    ")
    write(RES / "drawable" / "banner.xml",
          vector(320, 180, body, "TV banner (android:banner): zugig in Inter Bold and its grey dot."))


if __name__ == "__main__":
    main()
