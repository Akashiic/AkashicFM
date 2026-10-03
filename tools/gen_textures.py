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


def metal(img, base=(118, 122, 128), dark=(92, 96, 102)):
    """Chapa de aço com emendas verticais."""
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), jitter(dark if x in (5, 10) else base, 5))


def rivets(img):
    for p in ((1, 1), (14, 1), (1, 14), (14, 14)):
        img.putpixel(p, (170, 174, 180, 255))


def transmitter_front():
    img = Image.new("RGBA", (16, 16))
    metal(img)
    frame(img, (64, 68, 74))
    rivets(img)
    # Visor (a TESR escreve a frequência aqui): mesma posição da tela da rádio, x 2..13, y 4..7.
    for x in range(1, 15):
        img.putpixel((x, 3), (40, 42, 46, 255))
        img.putpixel((x, 8), (150, 154, 160, 255))
    for y in range(3, 9):
        img.putpixel((1, y), (40, 42, 46, 255))
        img.putpixel((14, y), (150, 154, 160, 255))
    for y in range(4, 8):
        for x in range(2, 14):
            img.putpixel((x, y), (26, 16, 6, 255) if (x + y) % 2 else (32, 20, 8, 255))
    # Dois mostradores redondos e a luz "no ar".
    for cx in (4, 9):
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            img.putpixel((cx + dx, 11 + dy), (210, 210, 200, 255) if (dx, dy) != (1, 1) else (60, 60, 60, 255))
    img.putpixel((13, 11), (240, 50, 40, 255))
    img.putpixel((13, 12), (150, 30, 24, 255))
    for x in range(3, 13):  # grade de ventilação
        img.putpixel((x, 13), (54, 58, 64, 255) if x % 2 else (80, 84, 90, 255))
    return img


def transmitter_side():
    img = Image.new("RGBA", (16, 16))
    metal(img)
    frame(img, (64, 68, 74))
    rivets(img)
    for y in range(4, 12):  # aletas de dissipação
        if y % 2 == 0:
            for x in range(3, 13):
                img.putpixel((x, y), (60, 64, 70, 255))
    return img


def transmitter_top():
    img = Image.new("RGBA", (16, 16))
    metal(img)
    frame(img, (64, 68, 74))
    rivets(img)
    # Base da antena no centro (x/z 6..9, onde o mastro encaixa).
    for y in range(5, 11):
        for x in range(5, 11):
            edge = x in (5, 10) or y in (5, 10)
            img.putpixel((x, y), (150, 154, 160, 255) if edge else (40, 42, 46, 255))
    return img


def antenna():
    """Mastro treliçado. O bloco só usa a faixa central (x 6..9): os montantes e as diagonais ficam nela."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), jitter((70, 74, 80), 4))
    for y in range(16):
        img.putpixel((6, y), jitter((190, 194, 200), 5))
        img.putpixel((9, y), jitter((150, 154, 160), 5))
        # diagonal em zigue-zague entre os montantes, período de 4 px
        d = 7 + (y % 4 if y % 4 < 2 else 3 - y % 4)
        img.putpixel((d, y), (170, 60, 50, 255) if y % 8 < 4 else (220, 220, 225, 255))
    return img


def portable_radio():
    """Rádio portátil: caixa com alça, grade do alto-falante, visor verde e antena."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(5, 15):
        for x in range(2, 14):
            img.putpixel((x, y), jitter((60, 64, 72), 4))
    for x in range(2, 14):  # bordas
        img.putpixel((x, 5), (96, 100, 108, 255))
        img.putpixel((x, 14), (36, 38, 44, 255))
    for y in range(5, 15):
        img.putpixel((2, y), (96, 100, 108, 255))
        img.putpixel((13, y), (36, 38, 44, 255))
    for x in range(5, 11):  # alça
        img.putpixel((x, 3), (120, 124, 130, 255))
    img.putpixel((5, 4), (120, 124, 130, 255))
    img.putpixel((10, 4), (120, 124, 130, 255))
    for x in range(4, 12):  # visor
        img.putpixel((x, 7), (60, 200, 110, 255) if x % 3 else (40, 150, 80, 255))
    for y in range(9, 13):  # grade
        for x in range(4, 9):
            img.putpixel((x, y), (30, 32, 36, 255) if (x + y) % 2 == 0 else (80, 84, 90, 255))
    img.putpixel((10, 10), (220, 60, 50, 255))  # botões
    img.putpixel((11, 12), (200, 200, 210, 255))
    for i, y in enumerate(range(0, 5)):  # antena
        img.putpixel((12 + (1 if i < 2 else 0), y), (190, 190, 200, 255))
    return img


def headphones_icon():
    """Fone: arco e as duas conchas."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    band = [(4, 6), (4, 5), (5, 4), (5, 3), (6, 2), (7, 2), (8, 2), (9, 2), (10, 3), (10, 4), (11, 5), (11, 6)]
    for p in band:
        img.putpixel(p, (70, 74, 82, 255))
        img.putpixel((p[0], p[1] + 1) if p[1] < 6 else p, (110, 114, 122, 255))
    for cx in (2, 11):
        for y in range(7, 13):
            for x in range(cx, cx + 3):
                img.putpixel((x, y), jitter((40, 42, 48), 3))
        for y in range(8, 12):
            img.putpixel((cx + (2 if cx == 2 else 0), y), (76, 176, 106, 255))  # almofada
    return img


def headphones_armor():
    """Textura de armadura (64x32, camada 1): arco no topo da cabeça e conchas nas laterais."""
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    # Cabeça no modelo de armadura: topo em (8..15, 0..7), laterais em (0..7, 8..15) e (16..23, 8..15).
    for x in range(8, 16):
        for y in range(2, 6):
            img.putpixel((x, y), jitter((60, 64, 72), 4))
    for x0 in (0, 16):  # lateral direita e esquerda
        for y in range(8, 16):
            img.putpixel((x0 + 3, y), (70, 74, 82, 255))  # arco descendo
            img.putpixel((x0 + 4, y), (70, 74, 82, 255))
        for y in range(11, 16):
            for x in range(x0 + 1, x0 + 7):
                img.putpixel((x, y), jitter((40, 42, 48), 3))
        for y in range(12, 15):
            for x in range(x0 + 2, x0 + 6):
                img.putpixel((x, y), (76, 176, 106, 255))
    return img


def ipod():
    """iPod: corpo claro de cantos arredondados, tela azulada em cima e a roda de clique embaixo."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(1, 15):
        for x in range(4, 12):
            corner = (x in (4, 11)) and (y in (1, 14))
            if not corner:
                img.putpixel((x, y), jitter((228, 230, 234), 3))
    for y in range(2, 14):  # contorno: luz à esquerda, sombra à direita e embaixo
        img.putpixel((4, y), (250, 250, 252, 255))
        img.putpixel((11, y), (170, 174, 182, 255))
    for x in range(5, 11):
        img.putpixel((x, 1), (250, 250, 252, 255))
        img.putpixel((x, 14), (160, 164, 172, 255))
    for y in range(2, 7):  # tela
        for x in range(5, 11):
            img.putpixel((x, y), (70, 120, 190, 255) if y > 2 else (110, 160, 220, 255))
    img.putpixel((6, 4), (230, 240, 255, 255))  # "texto" na tela
    img.putpixel((7, 4), (230, 240, 255, 255))
    img.putpixel((9, 5), (230, 240, 255, 255))
    ring = [(6, 9), (7, 8), (8, 8), (9, 9), (9, 10), (9, 11), (8, 12), (7, 12), (6, 11), (6, 10)]
    for p in ring:  # roda de clique
        img.putpixel(p, (150, 154, 162, 255))
    for p in ((7, 9), (8, 9), (7, 11), (8, 11), (7, 10), (8, 10)):
        img.putpixel(p, (196, 200, 206, 255))
    img.putpixel((7, 10), (236, 238, 242, 255))  # botão central
    img.putpixel((8, 10), (236, 238, 242, 255))
    return img


def main():
    out = {
        "blocks/radio_front.png": radio_front(),
        "blocks/radio_side.png": radio_side(),
        "blocks/radio_top.png": radio_top(),
        "blocks/speaker_front.png": speaker_front(),
        "blocks/speaker_side.png": speaker_side(),
        "items/tuner.png": tuner(),
        # Fase 6 (sempre no fim: o gerador aleatório é compartilhado, e as texturas antigas não podem mudar).
        "blocks/transmitter_front.png": transmitter_front(),
        "blocks/transmitter_side.png": transmitter_side(),
        "blocks/transmitter_top.png": transmitter_top(),
        "blocks/antenna.png": antenna(),
        # Fase 6b (no fim, pelo mesmo motivo).
        "items/portable_radio.png": portable_radio(),
        "items/headphones.png": headphones_icon(),
        "models/armor/headphones.png": headphones_armor(),
        "items/ipod.png": ipod(),
    }
    for rel, img in out.items():
        path = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path, optimize=True)
        print("ok", rel)


if __name__ == "__main__":
    main()
