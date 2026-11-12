#!/usr/bin/env python
"""Seed the server library with a few demo series (runs on the dev host)."""
from __future__ import annotations

import math
import os
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

LIBRARY = Path(os.environ.get("COMII_LIBRARY", "/root/dev/library"))
PAGE_SIZE = (900, 1350)
PALETTES = [
    ((12, 24, 64), (86, 156, 214), (240, 246, 255)),
    ((34, 22, 12), (255, 168, 92), (255, 244, 232)),
    ((8, 40, 32), (52, 199, 169), (232, 255, 248)),
    ((44, 14, 44), (214, 92, 168), (255, 236, 250)),
]


def gradient(size, start, end):
    width, height = size
    base = Image.new("RGB", size, start)
    draw = ImageDraw.Draw(base)
    for y in range(height):
        ratio = y / max(1, height - 1)
        color = tuple(int(start[i] + (end[i] - start[i]) * ratio) for i in range(3))
        draw.line([(0, y), (width, y)], fill=color)
    return base


def font(size: int) -> ImageFont.ImageFont:
    for candidate in (
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    ):
        if os.path.exists(candidate):
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def cover(title: str, subtitle: str, palette_index: int) -> Image.Image:
    start, end, ink = PALETTES[palette_index % len(PALETTES)]
    image = gradient((700, 1050), start, end)
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([48, 48, 652, 1002], radius=28, outline=ink, width=3)
    draw.text((80, 120), "COMII", font=font(34), fill=ink)
    y = 420
    for line in title.split("\n"):
        draw.text((80, y), line, font=font(64), fill=ink)
        y += 78
    draw.text((80, 900), subtitle, font=font(30), fill=ink)
    return image


def page(series: str, index: int, total: int, palette_index: int) -> Image.Image:
    start, end, ink = PALETTES[palette_index % len(PALETTES)]
    image = gradient(PAGE_SIZE, start, end)
    draw = ImageDraw.Draw(image)
    width, height = PAGE_SIZE
    for i in range(3):
        offset = (index * 70 + i * 220) % (height + 300)
        alpha_color = tuple(min(255, c + 30 + i * 18) for c in end)
        draw.ellipse([-200 + i * 240, offset - 200, 420 + i * 240, offset + 220], outline=alpha_color, width=6)
    draw.text((70, 70), series, font=font(38), fill=ink)
    draw.text((70, height - 150), f"PAGE {index:02d} / {total:02d}", font=font(44), fill=ink)
    draw.line([(70, height - 190), (width - 70, height - 190)], fill=ink, width=3)
    return image


SERIES = [
    ("作者A", "001_星海旅人", "STAR\nVOYAGE", "vol. 01 — 17 pages", 7, 0),
    ("作者A", "002_午后书店", "AFTERNOON\nBOOKS", "vol. 02 — 5 pages", 5, 1),
    ("作者B", "001_深海来信", "LETTERS\nFROM DEEP", "vol. 01 — 6 pages", 6, 2),
]


def main() -> None:
    random.seed(7)
    LIBRARY.mkdir(parents=True, exist_ok=True)
    for author, name, title, subtitle, count, palette_index in SERIES:
        directory = LIBRARY / author / name
        directory.mkdir(parents=True, exist_ok=True)
        cover(title, subtitle, palette_index).save(directory / "cover.jpg", "JPEG", quality=90)
        for index in range(1, count + 1):
            image = page(name.split("_", 1)[-1], index, count, palette_index)
            image.save(directory / f"{index:03d}.jpg", "JPEG", quality=88)
        print(f"seeded {directory} ({count + 1} files)")

    loose = LIBRARY / "单卷试读"
    loose.mkdir(exist_ok=True)
    for index in range(1, 4):
        page("单卷试读", index, 3, 3).save(loose / f"p{index:02d}.png", "PNG")
    print(f"seeded {loose}")


if __name__ == "__main__":
    main()
