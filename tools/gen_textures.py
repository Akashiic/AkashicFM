#!/usr/bin/env python3
"""Gera as texturas 16x16 do AkashicFM (arte original). Uso: python3 tools/gen_textures.py"""
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "akashicfm", "textures")
rng = random.Random(1907)  # fixo: a saída é reprodutível


def jitter(c, amt):
    d = rng.randint(-amt, amt)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + (255,)


def wood(img, base=(122, 84, 48), dark=(96, 64, 36)):
    """Madeira com veios horizontais."""
    for y in range(16):
        row = dark if y % 4 == 3 else base
        for x in range(16):
            img.putpixel((x, y), jitter(row, 6))


def frame(img, color):
    for i in range(16):
        for p in ((i, 0), (i, 15), (0, i), (15, i)):
            img.putpixel(p, jitter(color, 4))


def radio_front():
    img = Image.new("RGBA", (16, 16))
    wood(img)
    frame(img, (70, 46, 26))
    # Tela (a TESR escreve aqui): x 2..13, y 4..7, com moldura metálica em volta.
    for x in range(1, 15):
        img.putpixel((x, 3), (150, 150, 156, 255))
        img.putpixel((x, 8), (110, 110, 116, 255))
    for y in range(3, 9):
        img.putpixel((1, y), (150, 150, 156, 255))
        img.putpixel((14, y), (110, 110, 116, 255))
    for y in range(4, 8):
        for x in range(2, 14):
            img.putpixel((x, y), (14, 30, 18, 255) if (x + y) % 2 else (18, 36, 22, 255))
    # Grade do alto-falante.
    for y in range(10, 14):
        for x in range(2, 10):
            img.putpixel((x, y), (40, 28, 18, 255) if (x + y) % 2 == 0 else (88, 60, 34, 255))
    # Dois botões.
    for cx, cy in ((12, 11), (12, 13)):
        img.putpixel((cx, cy), (200, 200, 205, 255))
        img.putpixel((cx + 1, cy), (150, 150, 155, 255))
    img.putpixel((11, 11), (230, 60, 40, 255))  # LED
    return img


def radio_side():
    img = Image.new("RGBA", (16, 16))
    wood(img)
    frame(img, (70, 46, 26))
    for y in range(5, 11):  # entradas de ar
        if y % 2 == 1:
            for x in range(4, 12):
                img.putpixel((x, y), (52, 34, 20, 255))
    return img


def radio_top():
    img = Image.new("RGBA", (16, 16))
    wood(img)
    frame(img, (70, 46, 26))
    for x in range(4, 12):  # alça
        img.putpixel((x, 7), (60, 60, 64, 255))
        img.putpixel((x, 8), (40, 40, 44, 255))
    img.putpixel((13, 2), (200, 200, 205, 255))  # base da antena
    img.putpixel((13, 3), (120, 120, 125, 255))
    return img


def speaker_front():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), jitter((44, 44, 48), 3))
    frame(img, (24, 24, 26))
    cx = cy = 7.5
    for y in range(16):
        for x in range(16):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d <= 6.6:
                if d > 5.1:
                    c = (120, 120, 126)  # borda do cone
                elif d > 2.4:
                    c = (24, 24, 26) if int(d * 2) % 2 == 0 else (34, 34, 38)
                elif d > 1.2:
                    c = (70, 70, 76)
                else:
                    c = (150, 150, 158)  # domo
                img.putpixel((x, y), c + (255,))
    return img


def speaker_side():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), jitter((44, 44, 48), 3))
    frame(img, (24, 24, 26))
    for y in (4, 11):
        for x in range(4, 12):
            img.putpixel((x, y), (30, 30, 33, 255))
    return img


def tuner():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    body, edge, light = (70, 78, 92, 255), (40, 44, 54, 255), (110, 120, 138, 255)
    for y in range(5, 15):
        for x in range(4, 11):
            img.putpixel((x, y), body)
    for y in range(5, 15):
        img.putpixel((4, y), light)
        img.putpixel((10, y), edge)
    for x in range(4, 11):
        img.putpixel((x, 5), light)
        img.putpixel((x, 14), edge)
    for y in range(6, 9):  # visor
        for x in range(5, 10):
            img.putpixel((x, y), (40, 200, 90, 255) if y == 7 and x in (6, 8) else (16, 60, 30, 255))
    img.putpixel((6, 11), (220, 60, 50, 255))  # botões
    img.putpixel((8, 11), (230, 200, 60, 255))
    img.putpixel((7, 13), (200, 200, 210, 255))
    for i, y in enumerate(range(0, 5)):  # antena
        img.putpixel((9 + (1 if i < 2 else 0), y), (190, 190, 200, 255))
    img.putpixel((10, 0), (240, 80, 70, 255))
    return img


def main():
    out = {
        "blocks/radio_front.png": radio_front(),
        "blocks/radio_side.png": radio_side(),
        "blocks/radio_top.png": radio_top(),
        "blocks/speaker_front.png": speaker_front(),
        "blocks/speaker_side.png": speaker_side(),
        "items/tuner.png": tuner(),
    }
    for rel, img in out.items():
        path = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path, optimize=True)
        print("ok", rel)


if __name__ == "__main__":
    main()
