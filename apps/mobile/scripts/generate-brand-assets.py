#!/usr/bin/env python3
"""Render Doran's code-defined mark for native launchers (requires Pillow).

The geometry follows DoranMark in lib/ui/theme.dart. No photo, generated art,
remote asset or private data is used. App icon dimensions follow the existing
Android density and iOS Contents.json specifications.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
FOG = '#F5F7F8'
INK = '#315C78'


def mark_image(pixels: int, inset: float = .22) -> Image.Image:
    scale = 4
    canvas = Image.new('RGB', (pixels * scale, pixels * scale), FOG)
    d = ImageDraw.Draw(canvas)
    w = pixels * scale * (1 - inset * 2)
    origin = pixels * scale * inset
    def point(x, y):
        return origin + x * w, origin + y * w
    width = max(1, round(w / 16))
    left, top = point(.08, .08)
    right, bottom = point(.58, .43)
    half = width / 2
    # Pillow outlines grow inward; expand the box so its stroke centre matches
    # Flutter Canvas.drawRRect, whose stroke straddles the declared rectangle.
    d.rounded_rectangle((left-half, top-half, right+half, bottom+half),
                        radius=w*.1+half, outline=INK, width=width)
    def line(start, end):
        a, b = point(*start), point(*end)
        d.line((a, b), fill=INK, width=width)
        radius = width / 2
        for x, y in (a, b):
            d.ellipse((x-radius, y-radius, x+radius, y+radius), fill=INK)
    line((.2, .43), (.16, .55))
    line((.43, .6), (.92, .6))
    line((.43, .76), (.92, .76))
    line((.43, .92), (.76, .92))
    return canvas.resize((pixels, pixels), Image.Resampling.LANCZOS)


def main():
    android = ROOT / 'android/app/src/main/res'
    for density, pixels in {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}.items():
        mark_image(pixels).save(android / f'mipmap-{density}/ic_launcher.png')
    ios = ROOT / 'ios/Runner/Assets.xcassets'
    appicon = ios / 'AppIcon.appiconset'
    specifications = json.loads((appicon / 'Contents.json').read_text())
    emitted = set()
    for item in specifications['images']:
        name = item['filename']
        if name in emitted:
            continue
        pixels = round(float(item['size'].split('x')[0]) * float(item['scale'].rstrip('x')))
        mark_image(pixels).save(appicon / name)
        emitted.add(name)
    # One quiet native mark, not a simulated application screen.
    for scale in (1, 2, 3):
        suffix = '' if scale == 1 else f'@{scale}x'
        mark_image(96 * scale, inset=.08).save(ios / f'LaunchImage.imageset/LaunchImage{suffix}.png')
    print(f'Rendered 5 Android icons, {len(emitted)} iOS icons and 3 launch images')


if __name__ == '__main__':
    main()
