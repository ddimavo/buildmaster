#!/usr/bin/env python3
"""Генератор шаблонов зданий IndustryReborn (формат .ir.json) и изометрических превью.

Здания придуманы и нарисованы кодом, чужих построек здесь нет: лицензия - та же, что у плагина.
Запуск: python3 scripts/gen-buildings.py   (нужен Pillow для превью)
Результат: src/main/resources/buildings/*.ir.json, docs/buildings/*.png, docs/IndustryReborn-buildings.zip
"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/buildings")
PREV = os.path.join(ROOT, "docs/buildings")

# символ -> данные блока
PALETTE = {
    "p": "minecraft:oak_planks", "l": "minecraft:oak_log[axis=y]", "s": "minecraft:stone_bricks", "c": "minecraft:cobblestone",
    "b": "minecraft:bricks", "g": "minecraft:glass", "w": "minecraft:white_concrete", "r": "minecraft:red_concrete",
    "d": "minecraft:dark_oak_planks", "D": "minecraft:dark_oak_log[axis=y]", "q": "minecraft:quartz_block", "Q": "minecraft:quartz_pillar[axis=y]",
    "i": "minecraft:iron_block", "o": "minecraft:copper_block", "O": "minecraft:cut_copper", "y": "minecraft:gold_block",
    "t": "minecraft:smooth_stone", "T": "minecraft:stone", "h": "minecraft:hay_block", "k": "minecraft:barrel[facing=up]",
    "B": "minecraft:bookshelf", "f": "minecraft:oak_fence", "F": "minecraft:iron_bars", "n": "minecraft:lantern[hanging=false]",
    "N": "minecraft:lantern[hanging=true]", "m": "minecraft:mossy_cobblestone", "a": "minecraft:andesite", "A": "minecraft:polished_andesite",
    "e": "minecraft:spruce_planks", "E": "minecraft:spruce_log[axis=y]", "u": "minecraft:blue_concrete", "x": "minecraft:light_gray_concrete",
    "z": "minecraft:gray_concrete", "j": "minecraft:smooth_quartz", "v": "minecraft:stripped_oak_log[axis=y]", "R": "minecraft:red_wool",
    "W": "minecraft:white_wool", "Y": "minecraft:yellow_wool", "U": "minecraft:cyan_wool", "K": "minecraft:black_concrete",
    "G": "minecraft:grass_block", "H": "minecraft:chiseled_stone_bricks", "I": "minecraft:cracked_stone_bricks",
}
STAIRS = {  # (материал, facing, half) -> символ создаётся по требованию
}
EXTRA = {}


def sym(data):
    """Символ для произвольных данных блока (создаётся при необходимости)."""
    for k, v in list(PALETTE.items()) + list(EXTRA.items()):
        if v == data:
            return k
    for ch in "0123456789!#$%&*+=?@^_~;:<>[]{}()|abcdefghijklmnopqrstuvwxyz" + "".join(chr(c) for c in range(0xC0, 0x100) if c not in (0xD7, 0xF7)):
        if ch not in PALETTE and ch not in EXTRA:
            EXTRA[ch] = data
            return ch
    raise RuntimeError("закончились символы")


class B:
    def __init__(self, name, title, category, sx, sy, sz, desc=""):
        self.name, self.title, self.category = name, title, category
        self.sx, self.sy, self.sz = sx, sy, sz
        self.desc = desc
        self.v = {}

    def set(self, x, y, z, ch):
        if 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz:
            if ch == " " or ch is None:
                self.v.pop((x, y, z), None)
            else:
                self.v[(x, y, z)] = ch

    def fill(self, x1, y1, z1, x2, y2, z2, ch):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.set(x, y, z, ch)

    def walls(self, x1, y1, z1, x2, y2, z2, ch):
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    if x in (x1, x2) or z in (z1, z2):
                        self.set(x, y, z, ch)

    def stair(self, x, y, z, mat, facing, half="bottom", shape="straight"):
        self.set(x, y, z, sym(f"minecraft:{mat}_stairs[facing={facing},half={half},shape={shape}]"))

    def slab(self, x, y, z, mat, typ="bottom"):
        self.set(x, y, z, sym(f"minecraft:{mat}_slab[type={typ}]"))

    def door(self, x, y, z, facing, mat="oak"):
        self.set(x, y, z, sym(f"minecraft:{mat}_door[facing={facing},half=lower,hinge=left,open=false]"))
        self.set(x, y + 1, z, sym(f"minecraft:{mat}_door[facing={facing},half=upper,hinge=left,open=false]"))

    def bed(self, x, y, z, facing, color="red"):
        dx, dz = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}[facing]
        self.set(x, y, z, sym(f"minecraft:{color}_bed[facing={facing},part=foot]"))
        self.set(x + dx, y, z + dz, sym(f"minecraft:{color}_bed[facing={facing},part=head]"))

    def gable_roof_x(self, x1, x2, z1, z2, y, mat="oak", overhang=1):
        """Двускатная крыша: конёк вдоль X, скаты к z1 и z2."""
        zz1, zz2 = z1 - overhang, z2 + overhang
        yy = y
        a, b = zz1, zz2
        while a <= b:
            for x in range(x1 - overhang, x2 + overhang + 1):
                if a == b:
                    self.slab(x, yy, a, mat)
                else:
                    self.stair(x, yy, a, mat, "south")
                    self.stair(x, yy, b, mat, "north")
            a += 1
            b -= 1
            yy += 1

    def gable_roof_z(self, x1, x2, z1, z2, y, mat="oak", overhang=1):
        xx1, xx2 = x1 - overhang, x2 + overhang
        yy = y
        a, b = xx1, xx2
        while a <= b:
            for z in range(z1 - overhang, z2 + overhang + 1):
                if a == b:
                    self.slab(a, yy, z, mat)
                else:
                    self.stair(a, yy, z, mat, "east")
                    self.stair(b, yy, z, mat, "west")
            a += 1
            b -= 1
            yy += 1

    def data(self):
        pal = {}
        for k, v in PALETTE.items():
            pal[k] = v
        used = {ch for ch in self.v.values()}
        extra = getattr(self, "pal_extra", EXTRA)
        pal = {k: (PALETTE.get(k) or extra[k]) for k in used}
        layers = []
        for y in range(self.sy):
            rows = []
            for z in range(self.sz):
                rows.append("".join(self.v.get((x, y, z), ".") for x in range(self.sx)))
            layers.append(rows)
        while layers and all(set("".join(r)) <= {"."} for r in layers[-1:]):
            layers.pop()
        return {"format": 1, "name": self.name, "title": self.title, "category": self.category, "description": self.desc,
                "size": [self.sx, len(layers), self.sz], "foundation": "minecraft:stone_bricks", "palette": pal, "layers": layers}


def pad_floor(b, x1, z1, x2, z2, ch="s"):
    b.fill(x1, 0, z1, x2, 0, z2, ch)


# ------------------------------------------------------------------ здания

def house_small():
    b = B("house-small", "Небольшой дом", "house", 9, 8, 9, "Уютный дом с двускатной крышей, спальней и камином.")
    pad_floor(b, 0, 0, 8, 8)
    b.fill(1, 1, 1, 7, 1, 7, "p")
    b.walls(1, 1, 1, 7, 4, 7, "p")
    for y in range(1, 5):
        for (x, z) in ((1, 1), (7, 1), (1, 7), (7, 7)):
            b.set(x, y, z, "l")
    b.walls(1, 1, 1, 7, 1, 7, "c")
    b.fill(2, 1, 2, 6, 4, 6, " ")
    b.fill(2, 1, 2, 6, 1, 6, "p")
    for z in (3, 5):
        b.set(1, 3, z, "g"); b.set(7, 3, z, "g")
    for x in (3, 5):
        b.set(x, 3, 1, "g"); b.set(x, 3, 7, "g")
    b.door(4, 2, 7, "south")
    b.set(4, 1, 7, "p")
    b.set(4, 1, 8, "s"); b.set(4, 1, 8, "s")
    b.gable_roof_x(1, 7, 1, 7, 5)
    b.fill(1, 5, 1, 1, 5, 7, "p"); b.fill(7, 5, 1, 7, 5, 7, "p")
    b.fill(1, 6, 2, 1, 6, 6, "p"); b.fill(7, 6, 2, 7, 6, 6, "p")
    b.fill(1, 7, 3, 1, 7, 5, "p"); b.fill(7, 7, 3, 7, 7, 5, "p")
    b.bed(5, 2, 2, "south", "red")
    b.set(2, 2, 2, "B"); b.set(2, 3, 2, "B")
    b.fill(2, 2, 5, 2, 4, 5, "c")  # камин
    b.set(2, 2, 5, " ")
    b.fill(6, 5, 5, 6, 8, 5, "c")  # труба
    b.set(3, 4, 4, "N")
    return b


def shop_small():
    b = B("shop-small", "Небольшой магазин", "shop", 11, 8, 9, "Лавка с полосатым навесом, прилавком и стеллажами.")
    pad_floor(b, 0, 0, 10, 8)
    b.fill(1, 1, 1, 9, 1, 7, "t")
    b.walls(1, 1, 1, 9, 4, 7, "b")
    b.fill(2, 1, 2, 8, 4, 6, " ")
    b.fill(2, 1, 2, 8, 1, 6, "p")
    for x in range(2, 9):
        b.set(x, 3, 7, "g"); b.set(x, 2, 7, "g")
    b.fill(4, 2, 7, 6, 3, 7, " ")           # вход и витрина
    b.set(5, 1, 7, "p")
    b.door(5, 2, 7, "south")
    b.set(3, 2, 7, "g"); b.set(7, 2, 7, "g")
    b.fill(1, 5, 1, 9, 5, 7, "x")
    b.fill(0, 5, 7, 10, 5, 9, "x")
    for x in range(0, 11):
        for z in range(7, 10):
            b.set(x, 4 if z == 9 else 5, z, "R" if x % 2 == 0 else "W") if z == 9 else None
        b.set(x, 4, 9, "R" if x % 2 == 0 else "W")
        b.set(x, 5, 8, "R" if x % 2 == 0 else "W")
        b.set(x, 5, 9, "R" if x % 2 == 0 else "W")
    b.fill(0, 5, 7, 10, 5, 7, "x")
    b.fill(2, 2, 3, 8, 2, 3, "k")           # прилавок
    b.fill(2, 2, 2, 8, 3, 2, "B")           # стеллаж
    b.set(3, 4, 4, "N"); b.set(7, 4, 4, "N")
    b.fill(0, 1, 6, 0, 3, 6, "l")
    return b


def workshop_small():
    b = B("workshop-small", "Малая мастерская", "workshop", 13, 9, 11, "Кирпичная мастерская с большими воротами, трубой и навесом.")
    pad_floor(b, 0, 0, 12, 10)
    b.fill(1, 1, 1, 11, 1, 9, "t")
    b.walls(1, 1, 1, 11, 5, 9, "s")
    b.fill(2, 1, 2, 10, 5, 8, " ")
    b.fill(2, 1, 2, 10, 1, 8, "t")
    for x in (1, 11):
        for z in (1, 9):
            b.fill(x, 1, z, x, 5, z, "D")
    b.fill(5, 1, 9, 7, 3, 9, " ")           # ворота
    b.fill(5, 4, 9, 7, 4, 9, "d")
    for y in range(1, 4):
        b.set(5, y, 9, "f"); b.set(7, y, 9, "f")
    for z in (3, 5, 7):
        b.set(1, 3, z, "g"); b.set(11, 3, z, "g")
    b.fill(0, 6, 0, 12, 6, 10, "z")
    b.fill(1, 7, 1, 11, 7, 9, "z")
    b.fill(2, 8, 2, 10, 8, 8, "z")
    b.fill(9, 8, 8, 9, 12, 8, "b")          # труба
    b.fill(2, 2, 2, 4, 2, 2, "k")           # бочки с сырьём
    b.fill(2, 2, 3, 2, 2, 5, "k")
    b.fill(8, 2, 2, 10, 2, 2, "h")
    b.set(6, 5, 5, "N")
    b.fill(0, 1, 5, 0, 3, 5, "D"); b.fill(0, 4, 4, 0, 4, 6, "d")  # навес сбоку
    b.set(0, 1, 4, "D"); b.set(0, 1, 6, "D")
    return b


def workshop_forge():
    b = B("workshop-forge", "Кузница", "workshop", 15, 10, 11, "Просторная кузница с медной отделкой, трубой и погрузочной площадкой.")
    pad_floor(b, 0, 0, 14, 10)
    b.fill(1, 1, 1, 13, 1, 9, "a")
    b.walls(1, 1, 1, 13, 5, 9, "A")
    b.fill(2, 1, 2, 12, 5, 8, " ")
    b.fill(2, 1, 2, 12, 1, 8, "a")
    for x in (1, 7, 13):
        for z in (1, 9):
            b.fill(x, 1, z, x, 5, z, "E")
    for x in range(2, 13):
        b.set(x, 5, 1, "O"); b.set(x, 5, 9, "O")
    b.fill(6, 1, 9, 8, 3, 9, " ")
    b.fill(6, 4, 9, 8, 4, 9, "o")
    for y in range(1, 4):
        b.set(6, y, 9, "F"); b.set(8, y, 9, "F")
    for z in (3, 4, 6, 7):
        b.set(1, 3, z, "F"); b.set(13, 3, z, "F")
    b.gable_roof_x(1, 13, 1, 9, 6, "stone_brick", 1)
    b.fill(11, 7, 8, 11, 14, 8, "b")
    b.fill(10, 15, 7, 12, 15, 9, "b"); b.fill(11, 15, 8, 11, 15, 8, " ")
    b.fill(2, 2, 2, 5, 2, 2, "k")
    b.fill(2, 2, 3, 2, 2, 6, "k")
    b.fill(9, 2, 2, 12, 2, 4, "i")
    b.fill(9, 3, 2, 12, 3, 2, "i")
    b.set(7, 5, 5, "N"); b.set(4, 5, 5, "N"); b.set(10, 5, 5, "N")
    return b


def warehouse_small():
    b = B("warehouse-small", "Малый склад", "warehouse", 15, 8, 11, "Склад с широкими воротами, рядами бочек и сеном.")
    pad_floor(b, 0, 0, 14, 10)
    b.fill(1, 1, 1, 13, 1, 9, "t")
    b.walls(1, 1, 1, 13, 5, 9, "e")
    for x in (1, 13):
        for z in (1, 9):
            b.fill(x, 1, z, x, 5, z, "E")
    b.fill(2, 1, 2, 12, 5, 8, " ")
    b.fill(2, 1, 2, 12, 1, 8, "t")
    b.fill(6, 1, 9, 8, 4, 9, " ")
    b.fill(6, 5, 9, 8, 5, 9, "E")
    for x in range(3, 12, 2):
        b.set(x, 4, 1, "g"); b.set(x, 4, 9, "g")
    b.gable_roof_x(1, 13, 1, 9, 6, "spruce", 1)
    for x in range(2, 7, 2):
        for z in (2, 3):
            b.fill(x, 2, z, x, 3, z, "k")
    for x in range(9, 13, 1):
        b.fill(x, 2, 2, x, 3, 2, "h")
    b.fill(10, 2, 5, 12, 2, 7, "k")
    b.set(7, 5, 5, "N")
    b.fill(5, 1, 10, 9, 1, 10, "s")
    return b


def warehouse_large():
    b = B("warehouse-large", "Большой склад", "warehouse", 21, 9, 15, "Двухворотный склад с центральным проходом и стеллажами.")
    pad_floor(b, 0, 0, 20, 14)
    b.fill(1, 1, 1, 19, 1, 13, "t")
    b.walls(1, 1, 1, 19, 6, 13, "x")
    for x in (1, 10, 19):
        for z in (1, 13):
            b.fill(x, 1, z, x, 6, z, "z")
    b.fill(2, 1, 2, 18, 6, 12, " ")
    b.fill(2, 1, 2, 18, 1, 12, "t")
    for gx in (4, 14):
        b.fill(gx, 1, 13, gx + 2, 4, 13, " ")
        b.fill(gx, 5, 13, gx + 2, 5, 13, "z")
        for y in range(1, 5):
            b.set(gx, y, 13, "F"); b.set(gx + 2, y, 13, "F")
    b.fill(0, 7, 0, 20, 7, 14, "z")
    b.fill(1, 8, 1, 19, 8, 13, "z")
    for x in range(3, 18, 3):
        b.set(x, 5, 1, "g"); b.set(x, 5, 13, "g")
    for z in (3, 4):
        b.fill(2, 2, z, 8, 3, z, "k")
        b.fill(12, 2, z, 18, 3, z, "k")
    for z in (9, 10):
        b.fill(2, 2, z, 8, 2, z, "h")
        b.fill(12, 2, z, 18, 3, z, "B")
    b.set(10, 6, 7, "N")
    return b


def restaurant_small():
    b = B("restaurant-small", "Ресторан", "restaurant", 13, 8, 11, "Ресторан с залом, барной стойкой и летней террасой.")
    pad_floor(b, 0, 0, 12, 10)
    b.fill(1, 1, 1, 9, 1, 8, "p")
    b.walls(1, 1, 1, 9, 4, 8, "w")
    b.fill(2, 1, 2, 8, 4, 7, " ")
    b.fill(2, 1, 2, 8, 1, 7, "p")
    for y in range(1, 5):
        for (x, z) in ((1, 1), (9, 1), (1, 8), (9, 8)):
            b.set(x, y, z, "D")
    for z in (3, 4, 6):
        b.set(1, 3, z, "g"); b.set(9, 3, z, "g")
    for x in (3, 4, 6, 7):
        b.set(x, 3, 1, "g"); b.set(x, 3, 8, "g")
    b.set(5, 1, 8, "p")
    b.door(5, 2, 8, "south")
    b.fill(0, 5, 0, 10, 5, 9, "r")
    b.fill(1, 6, 1, 9, 6, 8, "r")
    # бар
    b.fill(2, 2, 2, 8, 2, 2, "k")
    b.fill(2, 3, 2, 8, 3, 2, "B")
    # столы и стулья
    for (x, z) in ((3, 5), (7, 5), (3, 7), (7, 7)):
        b.set(x, 2, z, "f"); b.set(x, 3, z, sym("minecraft:oak_pressure_plate"))
        b.stair(x - 1, 2, z, "oak", "east"); b.stair(x + 1, 2, z, "oak", "west")
    # терраса
    b.fill(10, 1, 3, 12, 1, 8, "p")
    for z in range(3, 9):
        b.set(12, 2, z, "f")
    for x in (10, 11):
        b.set(x, 2, 8, "f")
    b.set(11, 2, 5, "f"); b.set(11, 3, 5, sym("minecraft:oak_pressure_plate"))
    b.set(5, 4, 4, "N")
    return b


def hotel_small():
    b = B("hotel-small", "Малый отель", "hotel", 19, 13, 13, "Двухэтажный отель: холл с ресепшеном, коридоры и шесть номеров, у каждого своя дверь.")
    pad_floor(b, 0, 0, 18, 12)
    # первый этаж
    b.fill(1, 1, 1, 17, 1, 11, "q")
    b.walls(1, 1, 1, 17, 11, 11, "j")
    b.fill(2, 1, 2, 16, 10, 10, " ")
    for x in (1, 17):
        for z in (1, 11):
            b.fill(x, 1, z, x, 10, z, "Q")
    for x in (6, 12):
        b.fill(x, 1, 1, x, 10, 1, "Q")
    # перекрытие и крыша-терраса
    b.fill(1, 5, 1, 17, 5, 11, "q")
    b.fill(1, 10, 1, 17, 10, 11, "q")
    # коридор: по центру вдоль X (z=6), номера по сторонам
    for floor, y0 in ((0, 1), (1, 6)):
        # стена коридора с дверями
        for x in range(2, 17):
            b.set(x, y0, 5, "j"); b.set(x, y0 + 1, 5, "j"); b.set(x, y0 + 2, 5, "j"); b.set(x, y0 + 3, 5, "j")
            b.set(x, y0, 7, "j"); b.set(x, y0 + 1, 7, "j"); b.set(x, y0 + 2, 7, "j"); b.set(x, y0 + 3, 7, "j")
        # перегородки между номерами
        for x in (7, 12):
            for z in range(2, 5):
                b.fill(x, y0, z, x, y0 + 3, z, "j")
            for z in range(8, 11):
                b.fill(x, y0, z, x, y0 + 3, z, "j")
        # двери номеров (в сторону коридора)
        for x in (4, 9, 14):
            b.set(x, y0, 5, " "); b.set(x, y0 + 1, 5, " ")
            b.door(x, y0, 5, "south", "birch")
            b.set(x, y0, 7, " "); b.set(x, y0 + 1, 7, " ")
            b.door(x, y0, 7, "north", "birch")
        # мебель номеров
        for x in (3, 8, 13):
            b.bed(x, y0, 2, "south", "light_blue")
            b.bed(x, y0, 10, "north", "light_blue")
        for x in (5, 10, 15):
            b.set(x, y0, 2, "B"); b.set(x, y0, 10, "B")
        # окна
        for x in (4, 9, 14):
            b.set(x, y0 + 1, 1, "g"); b.set(x, y0 + 2, 1, "g")
            b.set(x, y0 + 1, 11, "g"); b.set(x, y0 + 2, 11, "g")
        # свет в коридоре
        for x in (5, 10, 15):
            b.set(x, y0 + 3, 6, "N")
    # холл на первом этаже: вход с торца
    b.fill(1, 1, 6, 1, 2, 6, " ")
    b.fill(1, 3, 6, 1, 3, 6, "y")
    b.door(1, 1, 6, "west", "dark_oak")
    b.fill(2, 1, 6, 16, 1, 6, "R")           # дорожка
    b.fill(15, 1, 6, 15, 2, 6, "y")          # ресепшен
    b.fill(16, 1, 6, 16, 1, 6, "q")
    # лестница наверх (между этажами)
    for i in range(4):
        b.stair(16, 1 + i, 3 + i if False else 6, "quartz", "west") if False else None
    b.fill(16, 5, 3, 16, 5, 4, " ")
    b.stair(16, 1, 4, "quartz", "north"); b.stair(16, 2, 3, "quartz", "north"); b.fill(16, 1, 3, 16, 1, 3, "q")
    b.fill(16, 2, 4, 16, 4, 4, " ")
    b.fill(16, 5, 3, 16, 5, 3, " ")
    # балконы и ограда второго этажа
    b.fill(2, 10, 2, 16, 10, 10, "q")
    for x in range(1, 18):
        b.set(x, 11, 1, "F"); b.set(x, 11, 11, "F")
    for z in range(1, 12):
        b.set(1, 11, z, "F"); b.set(17, 11, z, "F")
    b.fill(3, 11, 5, 15, 11, 7, "j")
    b.fill(2, 11, 3, 16, 11, 9, " ")
    b.fill(3, 11, 5, 15, 12, 7, " ")
    b.fill(3, 11, 5, 15, 11, 7, "q")
    for x in (5, 9, 13):
        b.set(x, 12, 6, "N")
    return b


def bank():
    b = B("bank", "Банк", "bank", 15, 11, 13, "Строгое здание с колоннами и золотой отделкой, внутри хранилище.")
    pad_floor(b, 0, 0, 14, 12)
    b.fill(1, 1, 1, 13, 1, 11, "t")
    b.fill(3, 1, 11, 11, 1, 12, "s")
    b.walls(2, 1, 1, 12, 7, 10, "s")
    b.fill(3, 1, 2, 11, 7, 9, " ")
    b.fill(3, 1, 2, 11, 1, 9, "t")
    for x in (2, 6, 8, 12):
        b.fill(x, 1, 1, x, 7, 1, "Q")
    for x in (3, 5, 9, 11):
        b.fill(x, 1, 11, x, 6, 11, "Q")
    b.fill(2, 7, 11, 12, 7, 11, "H")
    b.fill(7, 1, 10, 7, 3, 10, " ")
    b.door(7, 1, 10, "south", "dark_oak") if False else None
    b.fill(6, 1, 10, 8, 4, 10, " ")
    b.fill(6, 4, 10, 8, 4, 10, "y")
    for z in (3, 5, 7):
        b.set(2, 3, z, "g"); b.set(12, 3, z, "g")
    b.fill(2, 8, 0, 12, 8, 11, "H")
    b.fill(3, 9, 1, 11, 9, 10, "s")
    b.fill(5, 10, 2, 9, 10, 9, "s")
    b.fill(4, 1, 2, 10, 1, 2, "y")          # хранилище вдоль задней стены
    b.fill(4, 2, 2, 10, 3, 2, "i")
    b.fill(6, 2, 2, 8, 3, 2, "F")
    b.fill(4, 2, 5, 10, 2, 5, "B")
    b.set(7, 6, 6, "N"); b.set(4, 6, 6, "N"); b.set(10, 6, 6, "N")
    return b


def power_plant():
    b = B("power-plant", "Малая электростанция", "power_plant", 17, 16, 13, "Корпус с генераторным залом, высокой трубой и громоотводами.")
    pad_floor(b, 0, 0, 16, 12)
    b.fill(1, 1, 1, 15, 1, 11, "t")
    b.walls(1, 1, 1, 11, 6, 11, "x")
    b.fill(2, 1, 2, 10, 6, 10, " ")
    b.fill(2, 1, 2, 10, 1, 10, "t")
    for x in (1, 6, 11):
        for z in (1, 11):
            b.fill(x, 1, z, x, 6, z, "z")
    b.fill(5, 1, 11, 7, 3, 11, " ")
    b.fill(5, 4, 11, 7, 4, 11, "i")
    for y in range(1, 4):
        b.set(5, y, 11, "F"); b.set(7, y, 11, "F")
    for z in (3, 5, 7, 9):
        b.set(1, 4, z, "g"); b.set(11, 4, z, "g")
    b.fill(0, 7, 0, 12, 7, 12, "z")
    b.fill(2, 3, 3, 4, 5, 8, "i")           # генераторы
    b.fill(8, 3, 3, 10, 5, 8, "i")
    b.fill(2, 2, 3, 4, 2, 8, "o"); b.fill(8, 2, 3, 10, 2, 8, "o")
    b.fill(6, 2, 3, 6, 2, 9, "F")
    # пристройка и труба
    b.fill(12, 1, 2, 15, 4, 8, "b")
    b.fill(13, 1, 3, 14, 4, 7, " ")
    b.fill(13, 5, 5, 14, 5, 5, "b")
    b.fill(13, 5, 4, 13, 14, 4, "b"); b.fill(14, 5, 4, 14, 14, 4, "b")
    b.fill(13, 5, 5, 14, 14, 5, "b")
    b.fill(13, 6, 4, 14, 13, 5, "b")
    b.fill(13, 15, 4, 14, 15, 5, "b"); b.fill(13, 14, 4, 14, 14, 5, "b")
    b.fill(13, 14, 4, 14, 14, 5, "b")
    for (x, z) in ((1, 1), (11, 1), (1, 11), (11, 11)):
        b.fill(x, 8, z, x, 12, z, "F")
        b.set(x, 13, z, sym("minecraft:lightning_rod[facing=up]"))
    b.set(6, 6, 6, "N")
    return b


def house_two_floors():
    b = B("house-large", "Дом с мансардой", "house", 11, 10, 9, "Двухэтажный дом: гостиная внизу, спальня и балкон наверху.")
    pad_floor(b, 0, 0, 10, 8)
    b.fill(1, 1, 1, 9, 1, 7, "p")
    b.walls(1, 1, 1, 9, 6, 7, "w")
    b.fill(2, 1, 2, 8, 6, 6, " ")
    b.fill(2, 1, 2, 8, 1, 6, "p")
    for y in range(1, 7):
        for (x, z) in ((1, 1), (9, 1), (1, 7), (9, 7)):
            b.set(x, y, z, "D")
    b.fill(2, 4, 2, 8, 4, 6, "p")
    b.walls(1, 1, 1, 9, 1, 7, "c")
    for z in (3, 5):
        for y in (2, 5):
            b.set(1, y, z, "g"); b.set(9, y, z, "g")
    for x in (3, 7):
        for y in (2, 5):
            b.set(x, y, 1, "g"); b.set(x, y, 7, "g")
    b.set(5, 1, 7, "p"); b.door(5, 2, 7, "south")
    b.gable_roof_x(1, 9, 1, 7, 7, "dark_oak", 1)
    b.stair(8, 2, 5, "oak", "west"); b.stair(8, 3, 5, "oak", "west") if False else None
    b.fill(8, 1, 3, 8, 3, 3, " ")
    b.fill(8, 4, 3, 8, 4, 3, " ")
    for i in range(3):
        b.stair(8, 2 + i, 2 + i, "oak", "south")
    b.fill(8, 4, 5, 8, 4, 5, "p")
    b.bed(3, 5, 3, "south", "blue")
    b.set(3, 5, 5, "B")
    b.set(5, 3, 4, "N"); b.set(5, 6, 4, "N")
    b.fill(2, 5, 7, 8, 5, 7, "w")
    b.fill(2, 4, 8, 8, 4, 8, "p")
    for x in range(2, 9):
        b.set(x, 5, 8, "f")
    return b


ALL = [house_small, house_two_floors, shop_small, workshop_small, workshop_forge, warehouse_small, warehouse_large,
       restaurant_small, hotel_small, bank, power_plant]

# ------------------------------------------------------------------ превью (изометрия)

COLORS = {
    "oak_planks": (196, 158, 98), "oak_log": (110, 85, 50), "stone_bricks": (125, 125, 125), "cobblestone": (110, 110, 110),
    "bricks": (150, 85, 70), "glass": (170, 210, 230), "white_concrete": (230, 232, 232), "red_concrete": (170, 50, 50),
    "dark_oak_planks": (70, 50, 30), "dark_oak_log": (55, 40, 25), "quartz_block": (235, 230, 222), "quartz_pillar": (232, 228, 220),
    "iron_block": (210, 210, 210), "copper_block": (190, 110, 80), "cut_copper": (195, 115, 85), "gold_block": (245, 205, 50),
    "smooth_stone": (155, 155, 155), "stone": (125, 125, 125), "hay_block": (200, 170, 40), "barrel": (120, 90, 55),
    "bookshelf": (140, 105, 70), "oak_fence": (190, 150, 90), "iron_bars": (150, 150, 155), "lantern": (255, 190, 90),
    "mossy_cobblestone": (95, 120, 95), "andesite": (135, 135, 135), "polished_andesite": (150, 150, 150),
    "spruce_planks": (115, 85, 50), "spruce_log": (60, 45, 28), "blue_concrete": (45, 70, 160), "light_gray_concrete": (150, 150, 145),
    "gray_concrete": (65, 70, 75), "smooth_quartz": (238, 234, 226), "stripped_oak_log": (170, 135, 85), "red_wool": (170, 45, 45),
    "white_wool": (235, 235, 235), "yellow_wool": (240, 200, 50), "cyan_wool": (40, 130, 140), "black_concrete": (20, 22, 26),
    "grass_block": (95, 150, 60), "chiseled_stone_bricks": (130, 130, 132), "cracked_stone_bricks": (115, 115, 115),
    "birch_door": (215, 200, 150), "oak_door": (160, 125, 70), "dark_oak_door": (70, 50, 30), "lightning_rod": (200, 120, 90),
    "oak_pressure_plate": (196, 158, 98), "light_blue_bed": (80, 150, 210), "red_bed": (170, 50, 50), "blue_bed": (50, 80, 170),
}


def color_of(data):
    name = data.split("[")[0].replace("minecraft:", "")
    for suffix in ("_stairs", "_slab"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            name = {"stone_brick": "stone_bricks", "quartz": "quartz_block", "dark_oak": "dark_oak_planks", "oak": "oak_planks",
                    "spruce": "spruce_planks"}.get(name, name)
    if name.endswith("_door"):
        return (140, 100, 60)
    if name.endswith("_trapdoor"):
        return (110, 80, 50)
    for cand in (name, name + "s", name + "_planks", name + "_block"):
        if cand in COLORS:
            return COLORS[cand]
    return COLORS.get(name, (180, 90, 200))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def render(b, path, scale=3, cut=False):
    from PIL import Image, ImageDraw
    d = b.data()
    pal = d["palette"]
    layers = d["layers"]
    sy, sz, sx = len(layers), len(layers[0]), len(layers[0][0])
    cw = 12 * scale        # ширина куба на экране
    ch = cw // 2           # высота ромба
    hh = int(cw * 0.58)    # высота куба
    W = (sx + sz) * cw // 2 + 40
    H = (sx + sz) * ch // 2 + sy * hh + 60
    img = Image.new("RGB", (W * 2, H * 2), (110, 150, 200))
    dr = ImageDraw.Draw(img)
    # небо-градиент и земля
    for yy in range(H * 2):
        t = yy / (H * 2)
        dr.line([(0, yy), (W * 2, yy)], fill=(int(120 + 70 * t), int(165 + 50 * t), int(215 - 20 * t)))
    ox = sz * cw // 2 + 20
    oy = sy * hh + 30

    def project(x, y, z):
        return ox + (x - z) * cw // 2, oy + (x + z) * ch // 2 - y * hh

    for x in range(sx):
        for z in range(sz):
            for y in range(sy):
                c = layers[y][z][x]
                if c == ".":
                    continue
                if cut and x >= sx // 2 + 1 and z >= sz // 2 + 1:
                    continue        # разрез: убираем ближний к камере угол, чтобы был виден интерьер
                data = pal[c]
                base = color_of(data)
                name = data.split("[")[0]
                top_h = 1.0
                if name.endswith("_slab") and "type=bottom" in data:
                    top_h = 0.5
                thin = any(k in name for k in ("fence", "iron_bars", "pressure_plate", "lantern", "lightning_rod"))
                bw = cw if not thin else cw // 3
                px, py = project(x, y, z)
                px += 0
                hgt = int(hh * top_h) if not thin else hh
                if "pressure_plate" in name:
                    hgt = hh // 8
                x0 = px
                y0 = py
                if thin:
                    # столбик по центру клетки
                    dx = (cw - bw) // 2
                    pts_top = [(x0, y0 + ch // 2 - ch // 2 + 0), (x0 + bw // 2, y0 + ch // 4), (x0, y0 + ch // 2), (x0 - bw // 2, y0 + ch // 4)]
                    cx0, cy0 = x0, y0 + ch // 4
                    top = [(cx0, cy0 - bw // 4 - hgt), (cx0 + bw // 2, cy0 - hgt), (cx0, cy0 + bw // 4 - hgt), (cx0 - bw // 2, cy0 - hgt)]
                    left = [(cx0 - bw // 2, cy0 - hgt), (cx0, cy0 + bw // 4 - hgt), (cx0, cy0 + bw // 4), (cx0 - bw // 2, cy0)]
                    right = [(cx0 + bw // 2, cy0 - hgt), (cx0, cy0 + bw // 4 - hgt), (cx0, cy0 + bw // 4), (cx0 + bw // 2, cy0)]
                else:
                    top = [(x0, y0 - hgt + 0), (x0 + cw // 2, y0 - hgt + ch // 2), (x0, y0 - hgt + ch), (x0 - cw // 2, y0 - hgt + ch // 2)]
                    left = [(x0 - cw // 2, y0 - hgt + ch // 2), (x0, y0 - hgt + ch), (x0, y0 + ch), (x0 - cw // 2, y0 + ch // 2)]
                    right = [(x0 + cw // 2, y0 - hgt + ch // 2), (x0, y0 - hgt + ch), (x0, y0 + ch), (x0 + cw // 2, y0 + ch // 2)]
                if "glass" in name:
                    dr.polygon([(a * 2, b2 * 2) for a, b2 in top], fill=shade(base, 1.05), outline=shade(base, 0.7))
                    dr.polygon([(a * 2, b2 * 2) for a, b2 in left], fill=shade(base, 0.9), outline=shade(base, 0.65))
                    dr.polygon([(a * 2, b2 * 2) for a, b2 in right], fill=shade(base, 0.8), outline=shade(base, 0.6))
                    continue
                dr.polygon([(a * 2, b2 * 2) for a, b2 in top], fill=shade(base, 1.12), outline=shade(base, 0.8))
                dr.polygon([(a * 2, b2 * 2) for a, b2 in left], fill=shade(base, 0.82), outline=shade(base, 0.62))
                dr.polygon([(a * 2, b2 * 2) for a, b2 in right], fill=shade(base, 0.62), outline=shade(base, 0.5))
    # обрезка по содержимому
    bbox = img.getbbox()
    img = img.resize((W, H), Image.LANCZOS)
    img.save(path, quality=82) if path.endswith(".jpg") else img.save(path)


EXTRA_COLORS = {
    "smooth_sandstone": (222, 205, 150), "cut_sandstone": (216, 199, 146), "sandstone": (218, 203, 145), "smooth_quartz": (236, 232, 226),
    "polished_andesite": (132, 136, 132), "deepslate_bricks": (70, 70, 74), "polished_deepslate": (72, 72, 76), "deepslate_tiles": (54, 54, 58),
    "cobbled_deepslate": (77, 77, 81), "white_terracotta": (209, 178, 161), "orange_terracotta": (161, 83, 37), "mud_bricks": (137, 104, 79),
    "packed_mud": (142, 106, 79), "stripped_mangrove_log": (119, 54, 54), "mangrove_planks": (118, 54, 52), "cherry_planks": (226, 178, 172),
    "cherry_log": (54, 33, 44), "stripped_cherry_log": (215, 145, 148), "cut_copper": (192, 107, 79), "copper_block": (192, 107, 80),
    "birch_planks": (192, 175, 121), "birch_log": (216, 215, 210), "gray_concrete": (55, 58, 62), "mossy_cobblestone": (100, 118, 100),
    "dirt_path": (148, 122, 65), "poppy": (200, 40, 30), "dandelion": (240, 220, 40), "azure_bluet": (230, 230, 220), "cornflower": (70, 90, 200),
    "allium": (180, 110, 200), "flower_pot": (150, 90, 70), "cauldron": (60, 60, 64), "lava_cauldron": (200, 90, 20), "smoker": (110, 100, 90),
    "furnace": (120, 120, 120), "blast_furnace": (100, 100, 108), "anvil": (60, 60, 64), "grindstone": (140, 140, 140), "crafting_table": (150, 110, 70),
    "smithing_table": (60, 50, 60), "fletching_table": (190, 160, 110), "chest": (170, 120, 50), "lectern": (160, 120, 80), "redstone_lamp": (150, 90, 50),
    "lever": (130, 130, 130), "redstone_block": (170, 20, 10), "iron_block": (216, 216, 216), "gold_block": (246, 208, 61), "campfire": (90, 60, 30),
    "stripped_oak_log": (176, 143, 85), "orange_wool": (240, 118, 19), "blue_wool": (53, 57, 157), "green_wool": (84, 109, 27), "yellow_wool": (248, 197, 39),
    "cyan_wool": (21, 137, 145), "purple_wool": (121, 42, 172), "red_wool": (160, 39, 34), "white_wool": (233, 236, 236), "dark_oak_trapdoor": (70, 50, 30),
    "spruce_planks": (114, 84, 48), "spruce_log": (58, 37, 16), "bookshelf": (150, 115, 70), "barrel": (140, 100, 55), "hay_block": (200, 170, 40),
    "iron_bars": (140, 140, 145), "red_bed": (160, 39, 34), "blue_bed": (53, 57, 157), "green_bed": (84, 109, 27), "yellow_bed": (248, 197, 39),
    "white_bed": (233, 236, 236), "grass_block": (95, 150, 60), "smooth_stone": (160, 160, 160), "stone": (125, 125, 125), "quartz_slab": (235, 230, 222),
}
EXTRA_COLORS.update({"red_nether_bricks": (70, 28, 32), "deepslate_tile": (54, 54, 58), "mud_brick": (137, 104, 79), "cherry": (226, 178, 172),
                     "birch": (192, 175, 121), "dark_oak": (70, 50, 30), "stone_brick": (125, 125, 125), "brick": (150, 85, 70), "spruce": (114, 84, 48)})
COLORS.update({k: v for k, v in EXTRA_COLORS.items() if k not in COLORS})


def catalog_sheet(builds, path, title):
    """Лист-каталог: превью зданий одного типа сеткой."""
    from PIL import Image, ImageDraw
    import tempfile
    cols, tw = 5, 360
    thumbs = []
    for b in builds:
        tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        tmp.close()
        render(b, tmp.name, scale=2)
        im = Image.open(tmp.name).convert("RGB")
        os.unlink(tmp.name)
        r = tw / im.width
        thumbs.append((b, im.resize((tw, max(1, int(im.height * r))), Image.LANCZOS)))
    th = max(t[1].height for t in thumbs) + 26
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows * th + 40), (30, 34, 40))
    dr = ImageDraw.Draw(sheet)
    dr.text((10, 10), title, fill=(255, 255, 255))
    for i, (b, im) in enumerate(thumbs):
        x, y = (i % cols) * tw, 40 + (i // cols) * th
        sheet.paste(im, (x, y))
        dr.text((x + 6, y + im.height + 6), b.name + "  " + str(b.sx) + "x" + str(b.sz), fill=(230, 230, 230))
    sheet.save(path, quality=85)


def main():
    import tempfile
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import gen_variants
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(PREV, exist_ok=True)
    made = []
    datas = {}
    for fn in ALL:
        b = fn()
        b.pal_extra = dict(EXTRA)
        datas[b.name] = b.data()
        made.append(b)
    existing = {}
    for b in made:
        existing[b.category] = existing.get(b.category, 0) + 1
    specs = gen_variants.specs()
    seen = set()
    for cat, lst in specs.items():
        n = existing.get(cat, 0)
        assert n + len(lst) == 20, (cat, n, len(lst))
        for i, (title, p) in enumerate(lst):
            n += 1
            name = f"{cat.replace('_', '-')}-{n:02d}"
            desc = f"{title}: {p['x1']}x{p['z1']}, этажей {p['floors']}."
            b = gen_variants.build_one(sys.modules[__name__], cat, name, title, desc, p)
            d = b.data()
            key = json.dumps(d["layers"], ensure_ascii=False)
            assert key not in seen, "повтор здания " + name
            seen.add(key)
            datas[name] = d
            made.append(b)
    titles = {}
    for b in made:
        assert b.title not in titles, "повтор названия " + b.title
        titles[b.title] = b.name
    # очистка старых файлов, чтобы не оставалось лишнего
    for f in os.listdir(OUT):
        if f.endswith(".ir.json"):
            os.unlink(os.path.join(OUT, f))
    for b in made:
        d = datas[b.name]
        with open(os.path.join(OUT, b.name + ".ir.json"), "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
        print("OK", b.name, d["size"], len(b.v), "блоков")
    with open(os.path.join(OUT, "index.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(b.name for b in made) + "\n")
    if "--no-preview" not in sys.argv:
        tmp = tempfile.mkdtemp()
        by_cat = {}
        for b in made:
            render(b, os.path.join(tmp, b.name + ".jpg"), scale=2)
            render(b, os.path.join(tmp, b.name + "-cutaway.jpg"), scale=2, cut=True)
            by_cat.setdefault(b.category, []).append(b)
        for old in os.listdir(PREV):
            os.unlink(os.path.join(PREV, old))
        for cat, lst in by_cat.items():
            catalog_sheet(lst, os.path.join(PREV, "catalog-" + cat.replace("_", "-") + ".jpg"), "IndustryReborn: " + cat + " (" + str(len(lst)) + ")")
        zpath = os.path.join(ROOT, "docs/IndustryReborn-buildings.zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
            for b in made:
                z.write(os.path.join(OUT, b.name + ".ir.json"), "buildings/" + b.name + ".ir.json")
                z.write(os.path.join(tmp, b.name + ".jpg"), "previews/" + b.name + ".jpg")
                z.write(os.path.join(tmp, b.name + "-cutaway.jpg"), "previews/" + b.name + "-cutaway.jpg")
            for f in sorted(os.listdir(PREV)):
                z.write(os.path.join(PREV, f), "catalog/" + f)
            z.writestr("README.txt", "Здания IndustryReborn (формат .ir.json), по 20 каждого типа. Положите файлы в plugins/IndustryReborn/buildings/ "
                       "и выполните /district build reload.\nЗдания нарисованы генератором scripts/gen-buildings.py; лицензия та же, что у плагина.\n")
        print("zip", zpath)


if __name__ == "__main__":
    main()
