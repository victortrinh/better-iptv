#!/usr/bin/env python3
"""Write the launcher icon and the TV banner as vector drawables (#66, #104, #314).

Both are the Wordmark on the charcoal ground: a white lowercase "zugig" ending in a
grey dot raised to cap height, the same dot the in-app Wordmark draws. The icon carries
the whole word, not a lone z, because Fire OS's App Library shows a sideloaded app's
icon on its tile and ignores the banner (#314). On the icon the ground is a card inset
in Fire OS's tile grey, so the tile keeps a margin around it (#323). The letters are the app's Inter Bold,
turned into outlines because a vector drawable can't hold text.

The debug build (and benchmark, the same `.debug` app) is a separate app beside the released
one, so its icon adds a small "debug" caption in Caution Amber under the Wordmark (#325).

Writes:
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml   adaptive icon (API 26+)
  app/src/main/res/mipmap-anydpi/ic_launcher.xml       legacy icon (API 25)
  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/main/res/drawable/ic_launcher_monochrome.xml themed icons (API 33+)
  app/src/main/res/drawable/banner.xml                 320 x 180 android:banner
  app/src/main/res/values/ic_launcher.xml              the ground colour
  app/src/{debug,benchmark}/res/drawable/ic_launcher_{foreground,monochrome}.xml
  app/src/{debug,benchmark}/res/mipmap-anydpi/ic_launcher.xml   the same, captioned "debug"

Usage: python3 scripts/app_icon.py   (needs fonttools: pip install fonttools)
"""

from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DEBUG_RES = [ROOT / "app" / "src" / build / "res" for build in ("debug", "benchmark")]
FONT = TTFont(RES / "font" / "inter_700.ttf")
LABEL_FONT = TTFont(RES / "font" / "inter_600.ttf")  # Type.caption's weight

GROUND = "#FF16171B"  # charcoal, a step above Colors.ground so the icon isn't a hole
TILE_GREY = "#FF5A5A5A"  # the grey Fire OS puts behind a sideloaded icon, measured on the stick
WHITE = "#FFF2F2F4"   # off-white
DOT_GREY = "#FF77787E"  # the Wordmark's dot: a step back from the white letters
CAUTION = "#FFFFB35C"  # Caution Amber: small text only, as DESIGN.md allows

WORD = "zugig"
TRACKING = -0.03  # Inter Bold tracked like Type.display

# The Wordmark's dot, in em: 1.35 x Inter's own i-dot, 0.4 i-dots clear of the g's ink,
# its top on the cap height. The Wordmark composable uses the same numbers.
DOT_EM = 0.2169
DOT_GAP_EM = 0.0643

# Fire OS draws the middle 72 dp of the adaptive icon at the tile's full height, on a
# grey tile. The icon's ground is that grey, so the visible card is a smaller charcoal
# square inside it, with the tile's grey around it. The Wordmark's ink, dot included,
# is 2.77 em wide, so 16 dp spans 44 dp of the 56 dp card. The card also fits the
# 66 dp round mask other TV launchers use.
ICON_CARD = 56
ICON_CARD_RADIUS = 12
ICON_WORD = 16
# The debug caption: "debug" under the Wordmark, the pair's ink centred on the card.
DEBUG_CAPTION = "debug"
DEBUG_SIZE = 9
DEBUG_GAP = 4


def n(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def text_outline(s, x, baseline, size, tracking=0.0, font=FONT):
    gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
    pen = SVGPathPen(gs)
    k = size / upm
    cursor = 0
    for ch in s:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (k, 0, 0, -k, x + cursor, baseline)))
        cursor += gs[g].width * k + tracking * size
    return pen.getCommands(), cursor - tracking * size


def ink_bounds(s, size, tracking=0.0, font=FONT):
    """The ink box of `s` set at the origin, y down: (left, top, right, bottom)."""
    gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
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


def wordmark_height(size, tracking=0.0):
    """The ink height of the Wordmark, dot included."""
    _, t, _, b = ink_bounds(WORD, size, tracking)
    return b - min(t, -FONT["OS/2"].sCapHeight * size / FONT["head"].unitsPerEm)


def captioned_wordmark(size, cx, cy, tracking=0.0):
    """The Wordmark with the debug caption under it, the pair's ink centred on (cx, cy):
    (word path, dot path, caption path)."""
    k = size / ICON_WORD
    cap_size, gap = DEBUG_SIZE * k, DEBUG_GAP * k
    l, t, r, b = ink_bounds(DEBUG_CAPTION, cap_size, font=LABEL_FONT)
    word_h = wordmark_height(size, tracking)
    top = cy - (word_h + gap + b - t) / 2
    word, dot = wordmark(size, cx, top + word_h / 2, tracking)
    caption, _ = text_outline(DEBUG_CAPTION, round(cx - (l + r) / 2, 2), round(top + word_h + gap - t, 2), cap_size,
                              font=LABEL_FONT)
    return word, dot, caption


def rounded_square(size, radius, cx, cy):
    l, t, r = cx - size / 2, cy - size / 2, size - 2 * radius
    return (f"M{n(l + radius)},{n(t)}h{n(r)}a{n(radius)},{n(radius)} 0 0 1 {n(radius)},{n(radius)}"
            f"v{n(r)}a{n(radius)},{n(radius)} 0 0 1 {n(-radius)},{n(radius)}h{n(-r)}"
            f"a{n(radius)},{n(radius)} 0 0 1 {n(-radius)},{n(-radius)}v{n(-r)}"
            f"a{n(radius)},{n(radius)} 0 0 1 {n(radius)},{n(-radius)}z")


def path_xml(d, colour, indent):
    return f'{indent}<path\n{indent}    android:fillColor="{colour}"\n{indent}    android:pathData="{d}" />\n'


def vector(width, height, body, comment):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Written by scripts/app_icon.py; edit that, not this. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width}dp"\n    android:height="{height}dp"\n'
            f'    android:viewportWidth="{width}"\n    android:viewportHeight="{height}">\n{body}</vector>\n')


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    print(path.relative_to(ROOT))


def launcher_icon(res, comment, debug=False):
    """The adaptive icon's foreground and monochrome layers and the legacy icon, into `res`."""
    def marks(size, c):
        if debug:
            word, dot, caption = captioned_wordmark(size, c, c, TRACKING)
            return [(word, WHITE), (dot, DOT_GREY), (caption, CAUTION)]
        word, dot = wordmark(size, c, c, TRACKING)
        return [(word, WHITE), (dot, DOT_GREY)]

    # Adaptive icon: 108 dp layers, of which Fire OS shows the middle 72 dp square.
    ink = marks(ICON_WORD, 54)
    card = rounded_square(ICON_CARD, ICON_CARD_RADIUS, 54, 54)
    write(res / "drawable" / "ic_launcher_foreground.xml",
          vector(108, 108, path_xml(card, GROUND, "    ") + "".join(path_xml(d, c, "    ") for d, c in ink),
                 f"Adaptive icon foreground: the charcoal card, {comment}."))
    write(res / "drawable" / "ic_launcher_monochrome.xml",
          vector(108, 108, "".join(path_xml(d, WHITE, "    ") for d, _ in ink),
                 f"Themed icon: the word{', dot and caption' if debug else ' and dot'}, which the system tints one colour."))

    # Legacy icon for API 25 (Fire OS 6): a full-bleed square, the launcher masks it.
    k = 48 / 72
    ink = marks(ICON_WORD * k, 24)
    card = rounded_square(ICON_CARD * k, ICON_CARD_RADIUS * k, 24, 24)
    body = (path_xml("M0,0h48v48h-48z", TILE_GREY, "    ") + path_xml(card, GROUND, "    ")
            + "".join(path_xml(d, c, "    ") for d, c in ink))
    write(res / "mipmap-anydpi" / "ic_launcher.xml", vector(48, 48, body, "Launcher icon below API 26."))


def main():
    launcher_icon(RES, "zugig in Inter Bold and its grey dot")
    for res in DEBUG_RES:
        launcher_icon(res, "zugig in Inter Bold, its grey dot and an amber debug caption", debug=True)
    write(RES / "values" / "ic_launcher.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{TILE_GREY}</color>\n</resources>\n')
    write(RES / "mipmap-anydpi-v26" / "ic_launcher.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@color/ic_launcher_background" />\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
          '</adaptive-icon>\n')

    # Banner: the wordmark and its dot alone, their ink centred on the 320 x 180 card.
    word, dot = wordmark(64, 160, 90, TRACKING)
    body = path_xml("M0,0h320v180h-320z", GROUND, "    ") + path_xml(word, WHITE, "    ") + path_xml(dot, DOT_GREY, "    ")
    write(RES / "drawable" / "banner.xml",
          vector(320, 180, body, "TV banner (android:banner): zugig in Inter Bold and its grey dot."))


if __name__ == "__main__":
    main()
