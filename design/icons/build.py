#!/usr/bin/env python3
"""Builds design/icons/index.html: 100 numbered app icon variants for MindVault, to browse and pick from.

Structured as 5 directions (what the icon says) x 4 concepts each (the mark) x 5 art styles (how it is drawn), numbered in that order.

Every icon is an Android adaptive icon drawn on its 108 x 108 dp canvas, with the mark kept inside the 66 dp safe zone (launchers mask the
rest to a circle, squircle or rounded square). Colours are the app's: paper, ink, tonal and recording red. Type is the app's Fraunces.
Run: python3 design/icons/build.py
"""
import base64
import html
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "font")

PAPER = "#EDE6D9"
INK = "#2B221B"
TONAL = "#DED5C4"
REC = "#C9361F"
DEEP = "#1E1813"
CREAM = "#F6F1E7"


C = 54  # centre of the 108 canvas


def circle(cx, cy, r, fill="none", stroke=None, sw=0, extra=""):
    s = f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="{r:.2f}" fill="{fill}"'
    if stroke:
        s += f' stroke="{stroke}" stroke-width="{sw}"'
    return s + f" {extra}/>"


def text(t, size, fill, weight=400, italic=False, y=None, x=C, family="Fraunces", spacing=0, extra=""):
    fam = "FrauncesItalic" if (italic and family == "Fraunces") else family
    yy = C + size * 0.34 if y is None else y
    return (
        f'<text x="{x}" y="{yy:.2f}" text-anchor="middle" font-family="{fam}" font-weight="{weight}" '
        f'font-size="{size}" letter-spacing="{spacing}" fill="{fill}" {extra}>{html.escape(t)}</text>'
    )


def icon(bg, body):
    # Drawn on the full 108 dp canvas; shown as a launcher shows it, the middle 72 dp (the outer 18 dp on each side is masked away).
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><rect width="108" height="108" fill="{bg}"/>{body}</svg>'


# ---------------------------------------------------------------------------------------------------------------------------------------
# The structure: 5 directions (what the icon says) x 4 concepts (the mark) x 5 art styles (how it is drawn) = 100.
# A concept draws its mark from a Look, so every concept can be drawn in every style.
# ---------------------------------------------------------------------------------------------------------------------------------------

class Look:
    """How a mark is drawn: ground, ink, accent (the red dot), stroke weight, type weight, and an optional effect behind or on the mark."""

    def __init__(self, key, name, about, bg, fg, ac, weight=1.0, font=400, deboss=False, duo=False):
        self.key, self.name, self.about = key, name, about
        self.bg, self.fg, self.ac = bg, fg, ac
        self.w, self.font, self.deboss, self.duo = weight, font, deboss, duo

    def sw(self, base):
        return round(base * self.w, 2)


STYLES = [
    Look("ink", "Ink on paper", "The app's own look: a flat ink mark on its warm paper, with the red dot.", PAPER, INK, REC),
    Look("night", "Night", "Cream on deep ink, for dark home screens.", DEEP, CREAM, REC),
    Look("seal", "Red seal", "Paper on recording red, like a stamp.", REC, PAPER, INK),
    Look("press", "Letterpress", "Tonal paper, hairline weights, gently debossed.", TONAL, INK, REC, weight=0.62, font=220, deboss=True),
    Look("duo", "Two-ink print", "Printed twice, risograph style: a red pass slightly off register under the ink.", PAPER, INK, REC, duo=True),
]


def dot(l, cx, cy, r):
    return circle(cx, cy, r, fill=l.ac)


# --- A · The voice ---

def a_monogram(l):
    return text("M", 52, l.fg, weight=max(l.font, 300) if not l.deboss else 200, y=73) + dot(l, 75, 72, 3.6)


def a_lowercase(l):
    return text("m", 60, l.fg, weight=l.font + 100 if not l.deboss else 200, y=70) + dot(l, 54, 31, 4.6)


def a_quote(l):
    return text("\u201c", 96, l.fg, weight=900 if not l.deboss else 300, y=108, x=51) + dot(l, 71, 63, 4.4)


def a_fullstop(l):
    return text("m", 54, l.fg, weight=l.font if not l.deboss else 200, italic=True, y=69, x=50) + dot(l, 76, 66, 4.8)


# --- B · Listening ---

def b_ring(l):
    return circle(C, C, 20, stroke=l.fg, sw=l.sw(3.4)) + dot(l, C, C, 7.5)


def b_ripples(l):
    rings = "".join(circle(C, C, 10 + j * 7, stroke=l.fg, sw=l.sw(2.2), extra=f'opacity="{1 - j * 0.28:.2f}"') for j in range(3))
    return rings + dot(l, C, C, 5.4)


def b_level(l):
    hs = [12, 22, 32, 22, 12]
    bw, gap = 5.6, 3.6
    x0 = C - (len(hs) * bw + (len(hs) - 1) * gap) / 2
    out = ""
    for j, h in enumerate(hs):
        fill = l.ac if j == 2 else l.fg
        w = bw if not l.deboss else bw * 0.6
        out += f'<rect x="{x0 + j * (bw + gap) + (bw - w) / 2:.2f}" y="{C - h / 2:.2f}" width="{w:.2f}" height="{h}" rx="{w / 2:.2f}" fill="{fill}"/>'
    return out


def b_ear(l):
    arcs = ""
    for j in range(3):
        r = 10 + j * 7.5
        a0, a1 = math.radians(-58), math.radians(58)
        x0, y0 = C - 8 + r * math.cos(a0), C + r * math.sin(a0)
        x1, y1 = C - 8 + r * math.cos(a1), C + r * math.sin(a1)
        arcs += f'<path d="M{x0:.2f},{y0:.2f} A{r},{r} 0 0 1 {x1:.2f},{y1:.2f}" fill="none" stroke="{l.fg}" stroke-width="{l.sw(3.4)}" stroke-linecap="round" opacity="{1 - j * 0.22:.2f}"/>'
    return dot(l, C - 8, C, 5) + arcs


# --- C · Memory ---

def c_thread(l):
    d = "M30,66 C30,30 78,30 78,54 C78,78 42,78 42,54 C42,40 62,38 70,48"
    return f'<path d="{d}" fill="none" stroke="{l.fg}" stroke-width="{l.sw(4)}" stroke-linecap="round"/>' + dot(l, 70, 48, 4.6)


def c_page(l):
    w, h, f = 34, 42, 10
    x, y = C - w / 2, C - h / 2
    if l.deboss:
        out = f'<path d="M{x},{y} h{w - f} l{f},{f} v{h - f} h{-w} z" fill="none" stroke="{l.fg}" stroke-width="{l.sw(3)}" stroke-linejoin="round"/>'
        ln = l.fg
    else:
        out = f'<path d="M{x},{y} h{w - f} l{f},{f} v{h - f} h{-w} z" fill="{l.fg}"/>'
        ln = l.bg if not l.duo else PAPER
    out += f'<path d="M{x + w - f},{y} v{f} h{f}" fill="none" stroke="{ln}" stroke-width="1.6" opacity="0.6"/>'
    for j in range(3):
        lw = (w - 13) * (0.55 if j == 2 else 1)
        out += f'<rect x="{x + 7}" y="{y + 18 + j * 6.5:.1f}" width="{lw:.1f}" height="1.8" rx="0.9" fill="{ln}" opacity="0.6"/>'
    return out + dot(l, x + 10, y + 10, 3.6)


def c_ribbon(l):
    w, h = 24, 40
    x, y = C - w / 2, C - h / 2
    if l.deboss:
        out = f'<path d="M{x},{y} h{w} v{h} l{-w / 2},-9 l{-w / 2},9 z" fill="none" stroke="{l.fg}" stroke-width="{l.sw(3)}" stroke-linejoin="round"/>'
    else:
        out = f'<path d="M{x},{y} h{w} v{h} l{-w / 2},-9 l{-w / 2},9 z" fill="{l.fg}"/>'
    return out + dot(l, C, y + 12, 3.8)


def c_stack(l):
    out = ""
    for j in range(3):
        o = (2 - j) * 6
        op = [0.35, 0.6, 1][j]
        if l.deboss:
            out += f'<rect x="{C - 18 + o}" y="{C - 20 + o - 6}" width="30" height="34" rx="5" fill="none" stroke="{l.fg}" stroke-width="{l.sw(2.6)}" opacity="{op}"/>'
        else:
            out += f'<rect x="{C - 18 + o}" y="{C - 20 + o - 6}" width="30" height="34" rx="5" fill="{l.fg}" opacity="{op}"/>'
    return out + dot(l, C - 9, C - 15, 3.4)


# --- D · Safe ---

def d_keyhole(l):
    if l.deboss:
        out = circle(C, C, 23, stroke=l.fg, sw=l.sw(3.4))
        hole = l.fg
    else:
        out = circle(C, C, 23, fill=l.fg)
        hole = l.bg if not l.duo else PAPER
    out += circle(C, C - 5, 6.5, fill=hole) + f'<path d="M{C - 3.6},{C - 2} L{C - 6},{C + 13} H{C + 6} L{C + 3.6},{C - 2} Z" fill="{hole}"/>'
    return out + circle(C, C - 5, 2.6, fill=l.ac if l.ac != hole else l.fg)


def d_door(l):
    return (f'<path d="M{C - 17},{C + 24} V{C - 6} A17,17 0 0 1 {C + 17},{C - 6} V{C + 24} Z" fill="none" stroke="{l.fg}" stroke-width="{l.sw(3.6)}" stroke-linejoin="round"/>'
            + dot(l, C + 8, C + 6, 3.6))


def d_dial(l):
    ticks = "".join(
        f'<line x1="{C + 19 * math.cos(math.radians(a)):.2f}" y1="{C + 19 * math.sin(math.radians(a)):.2f}" '
        f'x2="{C + 24 * math.cos(math.radians(a)):.2f}" y2="{C + 24 * math.sin(math.radians(a)):.2f}" stroke="{l.fg}" stroke-width="{l.sw(2)}" stroke-linecap="round"/>'
        for a in range(0, 360, 30)
    )
    return ticks + circle(C, C, 13, stroke=l.fg, sw=l.sw(3.2)) + dot(l, C, C - 8, 3)


def d_box(l):
    return (f'<rect x="{C - 23}" y="{C - 23}" width="46" height="46" rx="14" fill="none" stroke="{l.fg}" stroke-width="{l.sw(3.4)}"/>'
            + f'<rect x="{C - 12}" y="{C - 12}" width="24" height="24" rx="7" fill="none" stroke="{l.fg}" stroke-width="{l.sw(3.4)}"/>'
            + dot(l, C, C, 4))


# --- E · Presence ---

def e_two(l):
    if l.deboss:
        return circle(C - 9, C, 16, stroke=l.fg, sw=l.sw(3.2)) + circle(C + 9, C, 16, stroke=l.fg, sw=l.sw(3.2)) + dot(l, C, C, 4)
    return (circle(C - 9, C, 16, fill=l.fg, extra='opacity="0.55"') + circle(C + 9, C, 16, fill=l.fg, extra='opacity="0.85"')
            + dot(l, C, C, 4))


def e_speech(l):
    left = f'<path d="M{C - 24},{C - 16} h18 a6,6 0 0 1 6,6 v10 a6,6 0 0 1 -6,6 h-12 l-6,6 z" fill="{l.fg}" opacity="0.9"/>'
    right = f'<path d="M{C + 24},{C - 2} h-18 a6,6 0 0 0 -6,6 v10 a6,6 0 0 0 6,6 h12 l6,6 z" fill="{l.fg}" opacity="0.55"/>'
    if l.deboss:
        left = left.replace(f'fill="{l.fg}" opacity="0.9"', f'fill="none" stroke="{l.fg}" stroke-width="{l.sw(3)}" stroke-linejoin="round"')
        right = right.replace(f'fill="{l.fg}" opacity="0.55"', f'fill="none" stroke="{l.fg}" stroke-width="{l.sw(3)}" stroke-linejoin="round"')
    return left + right + dot(l, C - 15, C - 5, 2.8)


def e_hand(l):
    d = f"M{C - 22},{C - 2} C{C - 22},{C + 18} {C + 22},{C + 18} {C + 22},{C - 2}"
    return f'<path d="{d}" fill="none" stroke="{l.fg}" stroke-width="{l.sw(4.4)}" stroke-linecap="round"/>' + dot(l, C, C - 2, 7)


def e_brackets(l):
    out = ""
    for side in (-1, 1):
        x = C + side * 20
        d = f"M{x},{C - 20} Q{x + side * 9},{C} {x},{C + 20}"
        out += f'<path d="{d}" fill="none" stroke="{l.fg}" stroke-width="{l.sw(4)}" stroke-linecap="round"/>'
    return out + dot(l, C, C, 6.5)


DIRECTIONS = [
    ("A", "The voice", "The app speaks in Fraunces; the icon is its typography.",
     [("Fraunces M", a_monogram), ("Lowercase m", a_lowercase), ("Quotation mark", a_quote), ("m.", a_fullstop)]),
    ("B", "Listening", "Someone paying full attention.",
     [("Dot in a ring", b_ring), ("Ripples", b_ripples), ("Level meter", b_level), ("Ear", b_ear)]),
    ("C", "Memory", "What is kept across sessions.",
     [("Thread", c_thread), ("Page", c_page), ("Bookmark", c_ribbon), ("Stacked sessions", c_stack)]),
    ("D", "Safe", "Private, on this phone only.",
     [("Keyhole", d_keyhole), ("Door", d_door), ("Dial", d_dial), ("Box within a box", d_box)]),
    ("E", "Presence", "Two people in a room.",
     [("Two circles", e_two), ("Two voices", e_speech), ("Held", e_hand), ("( \u2022 )", e_brackets)]),
]


DEBOSS = ('<filter id="deboss" x="-10%" y="-10%" width="120%" height="120%"><feOffset dx="0" dy="0.9"/><feGaussianBlur stdDeviation="0.5"/>'
          '<feComposite operator="out" in="SourceGraphic"/><feComponentTransfer><feFuncA type="linear" slope="0.35"/></feComponentTransfer>'
          '<feBlend in="SourceGraphic" mode="normal"/></filter>')


def render(look, draw):
    body = draw(look)
    if look.duo:
        # Two passes: the mark in red, nudged off register, then the ink pass multiplied over it, as a riso print overlaps.
        red = Look("red", "", "", PAPER, REC, REC, weight=look.w, font=look.font)
        body = f'<g transform="translate(1.6 1.4)" opacity="0.9">{draw(red)}</g><g style="mix-blend-mode:multiply">{body}</g>'
    if look.deboss:
        body = f"<defs>{DEBOSS}</defs><g filter=\"url(#deboss)\">{body}</g>"
    return icon(look.bg, body)


def font_face(name, file):
    with open(os.path.join(FONTS, file), "rb") as f:
        data = base64.b64encode(f.read()).decode()
    return f"@font-face{{font-family:'{name}';src:url(data:font/ttf;base64,{data}) format('truetype');font-weight:100 900;}}"


def main():
    data, n = [], 0
    for key, dname, dabout, concepts in DIRECTIONS:
        for ci, (cname, draw) in enumerate(concepts):
            for look in STYLES:
                n += 1
                data.append({"n": n, "dir": key, "concept": cname, "ci": ci + 1, "style": look.key, "svg": render(look, draw)})
    assert n == 100, n
    meta = {
        "directions": [{"key": k, "name": nm, "about": ab, "concepts": [c for c, _ in cs]} for k, nm, ab, cs in DIRECTIONS],
        "styles": [{"key": s.key, "name": s.name, "about": s.about} for s in STYLES],
    }
    fonts = font_face("Fraunces", "fraunces.ttf") + font_face("FrauncesItalic", "fraunces_italic.ttf") + font_face("Geist", "geist.ttf") + font_face("GeistMono", "geist_mono.ttf")
    # The icons are drawn as standalone SVGs, so they carry their own fonts when rasterised; in the page, the page's fonts serve them.
    with open(os.path.join(HERE, "template.html")) as f:
        page = f.read()
    page = page.replace("/*FONTS*/", fonts).replace("[/*ICONS*/]", json.dumps(data)).replace("{/*META*/}", json.dumps(meta))
    out = os.path.join(HERE, "index.html")
    with open(out, "w") as f:
        f.write(page)
    print(out, len(page) // 1024, "KB")


if __name__ == "__main__":
    main()
