#!/usr/bin/env python3
"""Cut Magpie's launcher and brand assets out of design/magpie-tile-source.jpg.

The source is the app tile artwork: a white line-art magpie above the wordmark,
on a near-black circuit-board field inside a grey rounded frame.

Everything below is derived from the artwork, not hand-placed:

  ic_launcher_background   the tile's interior field, full bleed on the 108dp canvas
  ic_launcher_foreground   the white mark (bird + wordmark), scaled so every inked
                           pixel lands inside the 66dp safe circle of any mask
  ic_launcher_monochrome   the bird alone, same safe-circle fit, for themed icons
  magpie_tile              the whole tile with rounded corners, for the app header
  playstore icon           the whole tile, square, 512px

Run: python3 scripts/render-magpie-icons.py   (needs Pillow)
"""

from __future__ import annotations

import math
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sys.exit("Pillow is required: pip install pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "design", "magpie-tile-source.jpg")
RES = os.path.join(ROOT, "magpie", "src", "main", "res")
ART = os.path.join(ROOT, "art")
DESIGN = os.path.join(ROOT, "design")

# Adaptive icon geometry, in the 108dp canvas units Android defines.
CANVAS_DP = 108.0
SAFE_RADIUS_DP = 33.0  # the 66dp circle every launcher mask keeps

DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def luminance(p):
    return 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]


def saturation(p):
    hi = max(p)
    return 0.0 if hi == 0 else (hi - min(p)) / hi


def find_tile(im):
    """Locate the grey rounded frame, and the interior it encloses."""
    w, h = im.size
    px = im.load()

    def runs_along(fixed, horizontal):
        n = w if horizontal else h
        hits = []
        for i in range(n):
            p = px[i, fixed] if horizontal else px[fixed, i]
            if 45 < luminance(p) < 120 and saturation(p) < 0.22:
                hits.append(i)
        if not hits:
            raise SystemExit("could not find the tile frame in the artwork")
        grouped, run = [], [hits[0]]
        for v in hits[1:]:
            if v == run[-1] + 1:
                run.append(v)
            else:
                grouped.append((run[0], run[-1]))
                run = [v]
        grouped.append((run[0], run[-1]))
        # the frame edges are the longest runs at each end
        return [g for g in grouped if g[1] - g[0] >= 8]

    horiz = runs_along(h // 2, True)
    vert = runs_along(w // 2, False)
    x0, x1 = horiz[0][0], horiz[-1][1]
    y0, y1 = vert[0][0], vert[-1][1]
    thickness = round((horiz[0][1] - horiz[0][0] + vert[0][1] - vert[0][0]) / 2)
    return (x0, y0, x1 + 1, y1 + 1), thickness


def corner_radius(im, tile, thickness):
    """Radius of the frame's rounded corner, measured off the top edge."""
    x0, y0, x1, y1 = tile
    px = im.load()
    probe_y = y0 + max(2, thickness // 6)
    for x in range(x0, x1):
        p = px[x, probe_y]
        if 45 < luminance(p) < 120 and saturation(p) < 0.22:
            inset = x - x0
            return max(inset + thickness, int(0.10 * (x1 - x0)))
    return int(0.20 * (x1 - x0))


def ink_alpha(im, region):
    """Alpha for the white mark: bright, unsaturated pixels only.

    The circuit traces are strongly teal and the frame is mid-grey, so a
    luminance ramp gated on saturation isolates the mark without a hard
    threshold and keeps the line art's antialiasing.
    """
    crop = im.crop(region)
    w, h = crop.size
    px = crop.load()
    alpha = Image.new("L", (w, h), 0)
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            lum = luminance(p)
            if lum <= 120:
                continue
            bright = min(1.0, (lum - 120.0) / 90.0)
            grey = min(1.0, max(0.0, (0.42 - saturation(p)) / 0.18))
            a = bright * grey
            if a > 0:
                ap[x, y] = int(round(255 * a))
    return alpha


def solid_points(alpha, step=2, cutoff=140):
    clean = alpha.filter(ImageFilter.MedianFilter(5))
    w, h = clean.size
    cp = clean.load()
    pts = [
        (x, y)
        for y in range(0, h, step)
        for x in range(0, w, step)
        if cp[x, y] >= cutoff
    ]
    if not pts:
        raise SystemExit("no ink found in the artwork")
    return pts, clean


def row_bands(clean, cutoff=140):
    """Contiguous vertical bands of ink — the bird, then the wordmark."""
    w, h = clean.size
    cp = clean.load()
    filled = [any(cp[x, y] >= cutoff for x in range(0, w, 2)) for y in range(h)]
    bands, start = [], None
    for y, f in enumerate(filled):
        if f and start is None:
            start = y
        elif not f and start is not None:
            if y - start >= 8:
                bands.append((start, y))
            start = None
    if start is not None:
        bands.append((start, h))
    return bands


def best_circle(pts):
    """Centre that minimises the enclosing radius, found by coarse-to-fine search."""
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx, cy = (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    step = span / 8.0
    best = (cx, cy, max(math.hypot(x - cx, y - cy) for x, y in pts))
    while step > 0.5:
        improved = True
        while improved:
            improved = False
            for dx in (-step, 0, step):
                for dy in (-step, 0, step):
                    if dx == 0 and dy == 0:
                        continue
                    nx, ny = best[0] + dx, best[1] + dy
                    r = max(math.hypot(x - nx, y - ny) for x, y in pts)
                    if r < best[2] - 1e-6:
                        best = (nx, ny, r)
                        improved = True
        step /= 2.0
    return best


def render_mark(alpha, pts, size):
    """White mark on a transparent 108dp canvas, fitted to the safe circle."""
    cx, cy, radius = best_circle(pts)
    scale = (SAFE_RADIUS_DP / CANVAS_DP) * size / radius
    w, h = alpha.size
    scaled = alpha.resize(
        (max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS
    )
    white = Image.new("RGBA", scaled.size, (255, 255, 255, 0))
    white.putalpha(scaled)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(
        white, (round(size / 2 - cx * scale), round(size / 2 - cy * scale))
    )
    return canvas


def outer_field(im, tile, tone):
    """A square of the circuit field from outside the tile.

    The field inside the tile is mostly bare — knocking the mark and its drop
    shadow out of it leaves a near-empty plate. The artwork outside the frame is
    the same pattern, uninterrupted, so the background layer comes from there.
    It is denser than the interior, so it gets pulled back toward the field's own
    tone: texture at icon size, not decoration competing with the bird.
    """
    band = im.crop((0, 0, im.size[0], tile[1]))
    side = band.size[1]
    px = band.load()
    best, best_score = 0, -1
    for x0 in range(0, band.size[0] - side + 1, 8):
        score = sum(
            1
            for y in range(0, side, 4)
            for x in range(x0, x0 + side, 4)
            if saturation(px[x, y]) > 0.30 and px[x, y][1] > px[x, y][0]
        )
        if score > best_score:
            best, best_score = x0, score
    crop = band.crop((best, 0, best + side, side))
    return Image.blend(crop, Image.new("RGB", crop.size, tone), 0.46)


def field_tone(interior_crop):
    """The tile's flat background tone — the median of its unlit pixels."""
    px = interior_crop.load()
    w, h = interior_crop.size
    darks = [
        px[x, y]
        for y in range(0, h, 4)
        for x in range(0, w, 4)
        if luminance(px[x, y]) < 40
    ]
    if not darks:
        raise SystemExit("no dark field tone found inside the tile")
    return tuple(sorted(c[i] for c in darks)[len(darks) // 2] for i in range(3))


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, im.size[0] - 1, im.size[1] - 1], radius=radius, fill=255
    )
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def write(path, im, **kwargs):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, **kwargs)
    print(f"  {os.path.relpath(path, ROOT):58s} {im.size[0]}x{im.size[1]}")


def main():
    im = Image.open(SOURCE).convert("RGB")
    tile, thickness = find_tile(im)
    radius = corner_radius(im, tile, thickness)
    x0, y0, x1, y1 = tile
    interior = (x0 + thickness, y0 + thickness, x1 - thickness, y1 - thickness)
    print(f"tile {tile} frame {thickness}px corner r={radius}px")

    alpha = ink_alpha(im, interior)
    pts, clean = solid_points(alpha)
    bands = row_bands(clean)
    print(f"ink bands (bird, then wordmark): {bands}")
    if len(bands) < 2:
        sys.exit("expected the bird and the wordmark as separate bands")
    bird_end = bands[-1][0]
    bird_alpha = alpha.crop((0, 0, alpha.size[0], bird_end))
    bird_pts, _ = solid_points(bird_alpha)

    print("launcher layers:")
    tone = field_tone(im.crop(interior))
    field = outer_field(im, tile, tone)
    print(f"field tone #{tone[0]:02X}{tone[1]:02X}{tone[2]:02X}, field {field.size[0]}px")
    for density, size in DENSITIES.items():
        write(
            os.path.join(RES, f"mipmap-{density}", "ic_launcher_background.webp"),
            field.resize((size, size), Image.LANCZOS),
            quality=92,
            method=6,
        )
        write(
            os.path.join(RES, f"mipmap-{density}", "ic_launcher_foreground.png"),
            render_mark(alpha, pts, size),
            optimize=True,
        )
        write(
            os.path.join(RES, f"mipmap-{density}", "ic_launcher_monochrome.png"),
            render_mark(bird_alpha, bird_pts, size),
            optimize=True,
        )

    # Status bar icon: the bird tight-cropped, white, small padding. Android
    # keeps only the alpha channel and tints it, so shape is all that matters.
    print("status bar icon:")
    # Harder cut than the launcher layer needs: the faintest tail of the ink
    # ramp is circuit trace, invisible over the dark field but not over a
    # tinted status bar.
    solid_bird = bird_alpha.filter(ImageFilter.MedianFilter(3)).point(
        lambda v: 0 if v < 105 else min(255, round((v - 105) * 255 / 95))
    )
    bird = solid_bird.crop(solid_bird.getbbox())
    for density, size in {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}.items():
        inner = round(size * 0.86)
        w, h = bird.size
        scale = inner / max(w, h)
        shaped = bird.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
        stat = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        layer = Image.new("RGBA", shaped.size, (255, 255, 255, 0))
        layer.putalpha(shaped)
        stat.alpha_composite(
            layer, ((size - shaped.size[0]) // 2, (size - shaped.size[1]) // 2)
        )
        write(
            os.path.join(RES, f"drawable-{density}", "ic_stat_magpie.png"),
            stat,
            optimize=True,
        )

    print("brand + store:")
    whole = im.crop(tile)
    write(
        os.path.join(RES, "drawable-nodpi", "magpie_tile.webp"),
        rounded(whole.resize((512, 512), Image.LANCZOS), round(512 * radius / (x1 - x0))),
        quality=92,
        method=6,
        lossless=False,
        exact=True,
    )
    write(
        os.path.join(ART, "magpie-ic_launcher-playstore.png"),
        whole.resize((512, 512), Image.LANCZOS),
        optimize=True,
    )

    # A preview of the composed icon under the two masks that matter, so the
    # result can be checked from a phone without installing anything.
    print("preview:")
    size = 432
    composed = Image.new("RGBA", (size, size))
    composed.alpha_composite(field.resize((size, size), Image.LANCZOS).convert("RGBA"))
    composed.alpha_composite(render_mark(alpha, pts, size))
    circle = Image.new("L", (size, size), 0)
    ImageDraw.Draw(circle).ellipse([0, 0, size - 1, size - 1], fill=255)
    squircle = Image.new("L", (size, size), 0)
    ImageDraw.Draw(squircle).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=round(size * 0.24), fill=255
    )
    strip = Image.new("RGBA", (size * 2 + 48, size), (0, 0, 0, 0))
    for i, mask in enumerate((circle, squircle)):
        shaped = composed.copy()
        shaped.putalpha(mask)
        strip.alpha_composite(shaped, (i * (size + 48), 0))
    write(os.path.join(DESIGN, "magpie-icon-preview.png"), strip, optimize=True)


if __name__ == "__main__":
    main()
