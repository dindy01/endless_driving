#!/usr/bin/env python3
"""Composites Robolectric UI layers (transparent PNGs) over headless GL frames for previews.
Usage: python3 tools/composite.py  (after ./gradlew test). Writes build/previews/*.png"""
import os
from PIL import Image

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
gl = os.path.join(root, "render/build/screenshots")
ui = os.path.join(root, "app/build/screenshots")
out = os.path.join(root, "build/previews")
os.makedirs(out, exist_ok=True)
pairs = [("01_attract", "ui_01_menu"), ("02_day_clear", "ui_02_hud"), ("04_night", "ui_02_hud"),
         ("05_rain", "ui_03_pause"), ("06_crash", "ui_04_game_over"), ("03_dusk", "ui_05_settings")]
for g, u in pairs:
    gp, up = os.path.join(gl, g + ".png"), os.path.join(ui, u + ".png")
    if not (os.path.exists(gp) and os.path.exists(up)):
        continue
    top = Image.open(up).convert("RGBA")
    base = Image.open(gp).convert("RGBA").resize(top.size)
    Image.alpha_composite(base, top).convert("RGB").save(os.path.join(out, f"{g}+{u}.png"))
    print("wrote", f"{g}+{u}.png")
