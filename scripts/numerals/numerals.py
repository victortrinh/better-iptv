#!/usr/bin/env python3
"""
Cuts the Top row numerals (#379) from three open-licence condensed faces into Android vector
drawables: app/src/main/res/drawable/numeral_<style>_<n>.xml, digits 0-9 and a "10", per style.
Only the XML ships; the fonts stay here, with their SIL Open Font Licence texts beside them.

Why vector and not text: Inter has no condensed cut and its digits are built from overlapping
contours, which a stroked outline shows as inner seams. Here each numeral is one outline with its
overlaps removed (skia-pathops), outlined in white at 60% over a faint fill darker than the ground,
both set in the drawable; RankedCard only picks the drawable and sizes it.

    python3 -m venv .venv && .venv/bin/pip install fonttools skia-pathops
    .venv/bin/python scripts/numerals/numerals.py
"""
from pathlib import Path

import pathops
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "app/src/main/res/drawable"

# The numeral's height in dp: Cards.numeralHeight, about three quarters of the poster's 204.
HEIGHT = 152.0
# The outline's stroke, in dp: thin enough to read as an outline, thick enough from the couch.
STROKE = 3.5
# Room around the digits for the stroke, so the drawable's bounds don't clip it.
PAD = STROKE / 2 + 0.25
OUTLINE = "#99FFFFFF"  # white at 60%
FILL = "#80000000"  # black at 50%: over the ground (#07080A), a little darker than it

# The styles behind Numerals.style (RankedCard.kt): the drawable name, the font, a variable font's
# instance, and the face's name for the credit.
STYLES = {
    "barlow": ("BarlowCondensed-ExtraBold.ttf", None, "Barlow Condensed ExtraBold"),
    "bebas": ("BebasNeue-Regular.ttf", None, "Bebas Neue"),
    "bigshoulders": ("BigShouldersDisplay[wght].ttf", {"wght": 900}, "Big Shoulders Display Black"),
}


def outline(font: TTFont, text: str) -> pathops.Path:
    """[text]'s digits set side by side, as one path with its overlaps removed, in font units."""
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    path = pathops.Path()
    x = 0
    for ch in text:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(path.getPen(), (1, 0, 0, 1, x, 0)))
        x += font["hmtx"][name][0]
    path.simplify()
    return path


def bounds(path: pathops.Path):
    xs, ys = [], []
    for _, points in path:
        for x, y in points:
            xs.append(x)
            ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def path_data(path: pathops.Path, scale: float, dx: float, dy: float, top: float) -> str:
    """[path] as Android path data in dp: font units scaled, y flipped from [top], moved by (dx, dy)."""
    out = []
    for verb, points in path:
        p = [(dx + x * scale, dy + (top - y) * scale) for x, y in points]
        if verb == pathops.PathVerb.MOVE:
            out.append("M%s,%s" % (f(p[0][0]), f(p[0][1])))
        elif verb == pathops.PathVerb.LINE:
            out.append("L%s,%s" % (f(p[0][0]), f(p[0][1])))
        elif verb == pathops.PathVerb.QUAD:
            out.append("Q%s,%s %s,%s" % (f(p[0][0]), f(p[0][1]), f(p[1][0]), f(p[1][1])))
        elif verb == pathops.PathVerb.CUBIC:
            out.append("C%s,%s %s,%s %s,%s" % (f(p[0][0]), f(p[0][1]), f(p[1][0]), f(p[1][1]), f(p[2][0]), f(p[2][1])))
        elif verb == pathops.PathVerb.CLOSE:
            out.append("Z")
    return "".join(out)


def f(x: float) -> str:
    return f"{x:.2f}".rstrip("0").rstrip(".")


def drawable(data: str, width: float, height: float, credit: str) -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Cut by scripts/numerals/numerals.py from {credit} (SIL Open Font License 1.1, see scripts/numerals): edit that, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{f(width)}dp"
    android:height="{f(height)}dp"
    android:viewportWidth="{f(width)}"
    android:viewportHeight="{f(height)}">
    <path
        android:fillColor="{FILL}"
        android:strokeColor="{OUTLINE}"
        android:strokeWidth="{STROKE}"
        android:strokeLineJoin="round"
        android:pathData="{data}" />
</vector>
"""


def main():
    for style, (file, axes, credit) in STYLES.items():
        font = TTFont(HERE / file)
        if axes:
            font = instantiateVariableFont(font, axes)
        # Every digit is scaled so the 0 is HEIGHT tall: a lining figure, the height they share.
        _, bottom, _, top = bounds(outline(font, "0"))
        scale = HEIGHT / (top - bottom)
        for n in range(0, 11):
            path = outline(font, str(n))
            x0, _, x1, _ = bounds(path)
            width = (x1 - x0) * scale + 2 * PAD
            height = HEIGHT + 2 * PAD
            data = path_data(path, scale, PAD - x0 * scale, PAD, top)
            (OUT / f"numeral_{style}_{n}.xml").write_text(drawable(data, width, height, credit))
            print(f"{style} {n}: {width:.0f} dp wide")


if __name__ == "__main__":
    main()
