#!/usr/bin/env python3
"""Cuts the static font instances in app/src/main/res/font from the variable masters in tools/fonts-src (OFL, see assets/licenses).
Run: python3 tools/make-static-fonts.py   (needs fonttools). The output is committed; the build never runs this.
Each instance is one (family, weight, italic, optical size) the app uses, with every axis pinned, so every device draws the same cut."""
import pathlib
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

root = pathlib.Path(__file__).resolve().parent
src = root / "fonts-src"
out = root.parent / "app/src/main/res/font"

# name -> (master, axis values)
F = {"wght": None}
INSTANCES = {
    "fraunces_display_260": ("fraunces.ttf", dict(wght=260, opsz=144, SOFT=40, WONK=0)),
    "fraunces_display_380": ("fraunces.ttf", dict(wght=380, opsz=144, SOFT=40, WONK=0)),
    "fraunces_display_italic_260": ("fraunces_italic.ttf", dict(wght=260, opsz=144, SOFT=40, WONK=0)),
    "fraunces_display_italic_380": ("fraunces_italic.ttf", dict(wght=380, opsz=144, SOFT=40, WONK=0)),
    "fraunces_text_340": ("fraunces.ttf", dict(wght=340, opsz=24, SOFT=40, WONK=0)),
    "fraunces_text_italic_340": ("fraunces_italic.ttf", dict(wght=340, opsz=24, SOFT=40, WONK=0)),
    "geist_400": ("geist.ttf", dict(wght=400)),
    "geist_500": ("geist.ttf", dict(wght=500)),
    "geist_600": ("geist.ttf", dict(wght=600)),
    "geist_mono_400": ("geist_mono.ttf", dict(wght=400)),
}
for name, (master, axes) in INSTANCES.items():
    f = TTFont(src / master)
    inst = instancer.instantiateVariableFont(f, axes, inplace=False)
    assert "fvar" not in inst, name
    inst.save(out / f"{name}.ttf")
    print(name, (out / f"{name}.ttf").stat().st_size)
