"""Параметрические варианты зданий для scripts/gen-buildings.py: по 20 зданий каждого типа.

Модуль получает от gen-buildings.py класс B (холст) и sym (символ палитры для данных блока) через объект G и строит здания
из общей «коробки»: стены, угловые колонны, окна, дверь, этажи с лестницей, крыша трёх видов, дополнения (веранда, балкон,
навес, труба, сад, забор). Внутри - обстановка под тип здания. Здания разных вариантов отличаются размером, этажностью,
материалами, крышей, дополнениями и планировкой.
"""
import hashlib
import random

# материалы: стена, угол, пол, крыша (имя для _stairs/_slab), дверь, цоколь
STYLES = {
    "oak":        dict(wall="oak_planks", corner="oak_log[axis=y]", floor="spruce_planks", roof="spruce", door="oak", base="cobblestone"),
    "stone":      dict(wall="stone_bricks", corner="polished_andesite", floor="smooth_stone", roof="stone_brick", door="spruce", base="stone"),
    "brick":      dict(wall="bricks", corner="stone_bricks", floor="oak_planks", roof="brick", door="dark_oak", base="stone_bricks"),
    "spruce":     dict(wall="spruce_planks", corner="spruce_log[axis=y]", floor="dark_oak_planks", roof="dark_oak", door="spruce", base="cobblestone"),
    "sand":       dict(wall="smooth_sandstone", corner="cut_sandstone", floor="sandstone", roof="smooth_sandstone", door="birch", base="sandstone"),
    "modern":     dict(wall="white_concrete", corner="gray_concrete", floor="smooth_quartz", roof="stone", door="birch", base="gray_concrete"),
    "quartz":     dict(wall="quartz_block", corner="quartz_pillar[axis=y]", floor="smooth_quartz", roof="quartz", door="birch", base="smooth_quartz"),
    "deepslate":  dict(wall="deepslate_bricks", corner="polished_deepslate", floor="deepslate_tiles", roof="deepslate_tile", door="dark_oak", base="cobbled_deepslate"),
    "cobble":     dict(wall="cobblestone", corner="oak_log[axis=y]", floor="oak_planks", roof="cobblestone", door="oak", base="mossy_cobblestone"),
    "terracotta": dict(wall="white_terracotta", corner="orange_terracotta", floor="smooth_stone", roof="red_nether_brick", door="acacia", base="bricks"),
    "mud":        dict(wall="mud_bricks", corner="stripped_mangrove_log[axis=y]", floor="mangrove_planks", roof="mud_brick", door="mangrove", base="packed_mud"),
    "cherry":     dict(wall="cherry_planks", corner="cherry_log[axis=y]", floor="stripped_cherry_log[axis=y]", roof="cherry", door="cherry", base="stone_bricks"),
    "copper":     dict(wall="cut_copper", corner="copper_block", floor="smooth_stone", roof="cut_copper", door="warped", base="stone_bricks"),
    "birch":      dict(wall="birch_planks", corner="birch_log[axis=y]", floor="oak_planks", roof="birch", door="birch", base="cobblestone"),
}
STYLE_KEYS = list(STYLES)

FACING_OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}


class Ctx:
    """Холст здания и сокращения для палитры."""

    def __init__(self, G, b, p):
        self.G, self.b, self.p = G, b, p
        self.S = STYLES[p["style"]]
        self.x0, self.x1, self.z0, self.z1 = p["x0"], p["x1"], p["z0"], p["z1"]
        self.n = p["floors"]
        self.fh = p.get("fh", 4)
        self.ct = 1 + self.fh * self.n          # верхнее перекрытие
        self.ry = self.ct + 1                    # начало крыши
        self.rng = random.Random(p["seed"])
        self.door_x = p["door_x"]

    def m(self, data):
        if ":" not in data:
            data = "minecraft:" + data
        return self.G.sym(data)

    def fy(self, k):
        return 1 + self.fh * k

    def empty(self, x, y, z):
        return (x, y, z) not in self.b.v

    def put(self, x, y, z, data):
        """Ставит блок, только если клетка свободна и внутри холста."""
        if self.empty(x, y, z):
            self.b.set(x, y, z, self.m(data))

    def interior(self, x, z):
        return self.x0 < x < self.x1 and self.z0 < z < self.z1


# ---------------------------------------------------------------- коробка


def shell(c):
    b, S, m = c.b, c.S, c.m
    wall, corner, floor = m(S["wall"]), m(S["corner"]), m(S["floor"])
    base = m(S["base"])
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    b.fill(0, 0, 0, b.sx - 1, 0, b.sz - 1, m("stone_bricks"))
    # сад: трава и цветы вокруг, дорожка к двери
    if c.p.get("garden"):
        flowers = ["poppy", "dandelion", "azure_bluet", "cornflower", "allium"]
        for x in range(b.sx):
            for z in range(b.sz):
                if not (x0 <= x <= x1 and z0 <= z <= z1):
                    b.set(x, 0, z, m("grass_block"))
                    if (x * 7 + z * 3) % 5 == 0 and abs(x - c.door_x) > 1:
                        b.set(x, 1, z, m(flowers[(x + z) % len(flowers)]))
        for z in range(z1 + 1, b.sz):
            b.set(c.door_x, 0, z, m("dirt_path"))
    for k in range(c.n):
        fy = c.fy(k)
        # пол
        b.fill(x0, fy, z0, x1, fy, z1, floor)
        for y in range(fy + 1, fy + c.fh):
            b.walls(x0, y, z0, x1, y, z1, wall)
            for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
                b.set(x, y, z, corner)
        b.walls(x0, fy, z0, x1, fy, z1, base if k == 0 else wall)
        for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
            b.set(x, fy, z, corner)
        # окна
        step = c.p.get("window_step", 2)
        wy = fy + 2 if c.fh <= 5 else fy + 3
        glass = m(c.p.get("glass", "glass"))
        for x in range(x0 + 2, x1 - 1, step):
            b.set(x, wy, z0, glass)
            if not (k == 0 and abs(x - c.door_x) <= 0):
                b.set(x, wy, z1, glass)
        for z in range(z0 + 2, z1 - 1, step):
            b.set(x0, wy, z, glass)
            b.set(x1, wy, z, glass)
    # потолок
    b.fill(x0, c.ct, z0, x1, c.ct, z1, floor)
    b.walls(x0, c.ct, z0, x1, c.ct, z1, wall)
    for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        b.set(x, c.ct, z, corner)
    # дверь и ступенька
    b.set(c.door_x, 1, z1, floor)
    b.door(c.door_x, 2, z1, "south", S["door"])
    b.stair(c.door_x, 1, z1 + 1, "stone_brick", "north")
    # лестница между этажами: вдоль западной стены, ступени вверх к югу
    if c.n > 1 and not c.p.get("own_stairs"):
        for k in range(c.n - 1):
            fy = c.fy(k)
            for i in range(c.fh):
                b.stair(x0 + 1, fy + 1 + i, z0 + 1 + i, "oak", "south")
            for i in range(c.fh - 1):
                b.set(x0 + 1, fy + c.fh, z0 + 1 + i, None)
    # лампы под потолком
    for k in range(c.n):
        fy = c.fy(k)
        for (fx, fz) in ((0.3, 0.5), (0.7, 0.5), (0.5, 0.3), (0.5, 0.75)):
            x = x0 + 1 + int((x1 - x0 - 2) * fx)
            z = z0 + 1 + int((z1 - z0 - 2) * fz)
            if c.empty(x, fy + c.fh - 1, z):
                b.set(x, fy + c.fh - 1, z, m("lantern[hanging=true]"))


def roof(c):
    b, S, m = c.b, c.S, c.m
    kind = c.p["roof"]
    x0, x1, z0, z1, ry = c.x0, c.x1, c.z0, c.z1, c.ry
    mat = S["roof"]
    wall = m(S["wall"])
    if kind == "gable_x":
        b.gable_roof_x(x0, x1, z0, z1, ry, mat)
        a, bb, i = z0 - 1, z1 + 1, 0
        while a + 1 <= bb - 1:
            for z in range(a + 1, bb):
                b.set(x0, ry + i, z, wall)
                b.set(x1, ry + i, z, wall)
            a += 1
            bb -= 1
            i += 1
    elif kind == "gable_z":
        b.gable_roof_z(x0, x1, z0, z1, ry, mat)
        a, bb, i = x0 - 1, x1 + 1, 0
        while a + 1 <= bb - 1:
            for x in range(a + 1, bb):
                b.set(x, ry + i, z0, wall)
                b.set(x, ry + i, z1, wall)
            a += 1
            bb -= 1
            i += 1
    elif kind == "hip":
        i = 0
        while True:
            xa, xb, za, zb = x0 - 1 + i, x1 + 1 - i, z0 - 1 + i, z1 + 1 - i
            y = ry + i
            if xa > xb or za > zb:
                break
            if xa == xb or za == zb:
                for x in range(xa, xb + 1):
                    for z in range(za, zb + 1):
                        b.slab(x, y, z, mat)
                break
            for x in range(xa, xb + 1):
                b.stair(x, y, za, mat, "south")
                b.stair(x, y, zb, mat, "north")
            for z in range(za + 1, zb):
                b.stair(xa, y, z, mat, "east")
                b.stair(xb, y, z, mat, "west")
            i += 1
    else:  # flat: парапет
        b.walls(x0, ry, z0, x1, ry, z1, wall)
        for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
            b.set(x, ry, z, m(S["corner"]))
        if c.p.get("rooftop"):
            b.set(x0 + 2, ry, z0 + 2, m("cauldron"))
            b.set(x1 - 2, ry, z0 + 2, m("lightning_rod[facing=up,powered=false,waterlogged=false]"))


def extras(c):
    b, m = c.b, c.m
    x0, x1, z0, z1, dx = c.x0, c.x1, c.z0, c.z1, c.door_x
    ex = c.p.get("extras", ())
    S = c.S
    if "porch" in ex:
        for x in range(dx - 1, dx + 2):
            for z in (z1 + 1, z1 + 2):
                b.slab(x, 4, z, "spruce")
        for x in (dx - 1, dx + 1):
            for y in (2, 3):
                b.set(x, y, z1 + 2, m("oak_fence"))
    if "balcony" in ex and c.n >= 2:
        fy = c.fy(1)
        for x in range(dx - 1, dx + 2):
            b.slab(x, fy, z1 + 1, "stone_brick")
            b.set(x, fy + 1, z1 + 1, m("oak_fence"))
        b.set(dx - 1, fy + 1, z1 + 1, m("oak_fence"))
        b.set(dx + 1, fy + 1, z1 + 1, m("oak_fence"))
        b.set(dx, fy + 1, z1 + 1, None)
        b.set(dx, fy + 1, z1, None)
        b.set(dx, fy + 2, z1, None)
        b.door(dx, fy + 1, z1, "south", S["door"])
    if "awning" in ex:
        colors = c.p.get("awning", ("red_wool", "white_wool"))
        for x in range(x0, x1 + 1):
            b.set(x, 4, z1 + 1, m(colors[x % 2]))
        for x in (x0, x1):
            for y in (2, 3):
                b.set(x, y, z1 + 1, m("oak_fence"))
    if "fence" in ex:
        for x in range(b.sx):
            for z in range(b.sz):
                if (x in (0, b.sx - 1) or z in (0, b.sz - 1)) and not (abs(x - dx) <= 1 and z == b.sz - 1):
                    b.set(x, 1, z, m("oak_fence"))
    if "pillars" in ex:
        for x in (x0, x0 + 2, x1 - 2, x1):
            for y in range(2, c.ry - 1):
                b.set(x, y, z1 + 1, m("quartz_pillar[axis=y]"))
        for x in range(x0, x1 + 1):
            b.set(x, c.ry - 1, z1 + 1, m("quartz_slab[type=bottom]"))
    if "chimney" in ex and c.n >= 1:
        cx, cz = x1 - 1, z0 + 1
        top = max(y for (_, y, _) in b.v) + 1
        for y in range(2, top + 1):
            b.set(cx, y, cz, m("bricks" if S["wall"] != "bricks" else "stone_bricks"))
        b.set(cx, 2, cz, m("furnace[facing=south,lit=false]"))
        b.set(cx, top + 1, cz, m("campfire[facing=north,lit=false,signal_fire=false,waterlogged=false]"))
    if "sign" in ex:
        b.set(dx + 2, 3, z1 + 1, m("lantern[hanging=false]"))
        b.set(dx + 2, 2, z1 + 1, m("oak_fence"))
    if "tower" in ex:
        # башенка на углу: на 3 блока выше крыши, с флагом
        for y in range(c.ry, c.ry + 6):
            b.set(x1, y, z0, m(S["corner"]))
        b.set(x1, c.ry + 6, z0, m("lightning_rod[facing=up,powered=false,waterlogged=false]"))


# ---------------------------------------------------------------- обстановка


def table(c, x, y, z, chairs=True):
    c.put(x, y, z, "oak_fence")
    c.put(x, y + 1, z, "oak_pressure_plate[powered=false]")
    if chairs:
        c.put(x - 1, y, z, "oak_stairs[facing=east,half=bottom,shape=straight]")
        c.put(x + 1, y, z, "oak_stairs[facing=west,half=bottom,shape=straight]")


def bed(c, x, y, z, facing="south", color="red"):
    dx, dz = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}[facing]
    if c.empty(x, y, z) and c.empty(x + dx, y, z + dz) and c.interior(x, z) and c.interior(x + dx, z + dz):
        c.b.bed(x, y, z, facing, color)


def furnish_house(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    for k in range(c.n):
        y = c.fy(k) + 1
        bedroom = k >= 1 or c.n == 1 and c.p.get("bed", True)
        if k == 0:
            c.put(x1 - 1, y, z0 + 1, "crafting_table")
            c.put(x1 - 2, y, z0 + 1, "furnace[facing=south,lit=false]")
            c.put(x1 - 3, y, z0 + 1, "barrel[facing=up,open=false]")
            for yy in (y, y + 1):
                c.put(x0 + 4, yy, z0 + 1, "bookshelf")
            table(c, (x0 + x1) // 2 + 1, y, (z0 + z1) // 2 + 1)
        if bedroom:
            bed(c, x1 - 2, y, z0 + 2 if k else z1 - 3, "south", c.rng.choice(["red", "blue", "green", "white", "yellow"]))
            if x1 - x0 >= 9:
                bed(c, x0 + 4, y, z1 - 3, "south", "white")
            c.put(x1 - 1, y, z0 + 2 if k else z1 - 3, "chest[facing=west,type=single,waterlogged=false]")
        if k == 0 and c.n > 1:
            c.put(x1 - 1, y, z1 - 1, "barrel[facing=up,open=false]")


def furnish_shop(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    y = 2
    for x in range(x0 + 1, x1):
        for yy in (y, y + 1):
            c.put(x, yy, z0 + 1, "bookshelf" if (x + yy) % 3 else "barrel[facing=south,open=false]")
    cz = z0 + (z1 - z0) // 2
    for x in range(x0 + 2, x1 - 1):
        c.put(x, y, cz, "smooth_stone")
        c.put(x, y + 1, cz, "oak_pressure_plate[powered=false]" if x % 2 else "flower_pot")
    for x in range(x0 + 2, x1 - 1, 3):
        c.put(x, y, z1 - 2, "barrel[facing=up,open=false]")
        c.put(x, y + 1, z1 - 2, "oak_pressure_plate[powered=false]")
    c.put(x1 - 1, y, cz + 1, "oak_stairs[facing=west,half=bottom,shape=straight]")
    for k in range(1, c.n):
        yy = c.fy(k) + 1
        for x in range(x0 + 3, x1 - 1, 2):
            c.put(x, yy, z0 + 1, "barrel[facing=up,open=false]")
            c.put(x, yy, z0 + 2, "chest[facing=south,type=single,waterlogged=false]")
        bed(c, x1 - 2, yy, z1 - 3, "south", "blue")


def furnish_workshop(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    y = 2
    items = ["blast_furnace[facing=south,lit=false]", "furnace[facing=south,lit=false]", "smithing_table", "anvil[facing=east]",
             "grindstone[face=floor,facing=south]", "crafting_table", "cauldron", "barrel[facing=up,open=false]", "fletching_table"]
    for i, it in enumerate(items):
        x = x0 + 1 + i
        if x < x1:
            c.put(x, y, z0 + 1, it)
    for z in range(z0 + 3, z1 - 1, 2):
        c.put(x1 - 1, y, z, "barrel[facing=west,open=false]")
        c.put(x0 + 3, y, z, "chest[facing=east,type=single,waterlogged=false]")
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2 + 1
    c.put(cx, y, cz, "smooth_stone")
    c.put(cx + 1, y, cz, "smooth_stone")
    c.put(cx, y + 1, cz, "anvil[facing=north]")
    c.put(cx + 1, y + 1, cz, "oak_pressure_plate[powered=false]")
    if c.p.get("forge"):
        c.put(x0 + 2, y, z1 - 2, "lava_cauldron")
        c.put(x0 + 3, y, z1 - 2, "iron_block")
        c.put(x0 + 4, y, z1 - 2, "iron_block")
    # большие ворота
    if c.p.get("gate"):
        gx = c.door_x
        for xx in (gx - 1, gx + 1):
            for yy in (2, 3):
                c.b.set(xx, yy, z1, None)
            c.b.set(xx, 2, z1, c.m("dark_oak_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]"))
            c.b.set(xx, 3, z1, c.m("dark_oak_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]"))


def furnish_restaurant(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    y = 2
    # кухня
    c.put(x0 + 1, y, z0 + 1, "smoker[facing=south,lit=false]")
    c.put(x0 + 2, y, z0 + 1, "furnace[facing=south,lit=false]")
    c.put(x0 + 3, y, z0 + 1, "cauldron")
    c.put(x0 + 4, y, z0 + 1, "crafting_table")
    for x in range(x0 + 1, x0 + 6):
        c.put(x, y, z0 + 3, "smooth_stone")
        c.put(x, y + 1, z0 + 3, "oak_pressure_plate[powered=false]" if x % 2 else "flower_pot")
    # барная стойка и бочки
    for z in range(z0 + 2, z1 - 2):
        c.put(x1 - 2, y, z, "dark_oak_planks")
        c.put(x1 - 1, y, z, "barrel[facing=west,open=false]")
    # зал: столики
    for x in range(x0 + 3, x1 - 3, 3):
        for z in range(z0 + 5, z1 - 1, 3):
            table(c, x, y, z)
    for k in range(1, c.n):
        yy = c.fy(k) + 1
        for x in range(x0 + 3, x1 - 2, 3):
            for z in range(z0 + 2, z1 - 1, 3):
                table(c, x, yy, z)


def furnish_warehouse(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    gx = c.door_x
    # ворота: вынимаем 3 в ширину и 3 в высоту
    for xx in range(gx - 1, gx + 2):
        for yy in (2, 3, 4):
            c.b.set(xx, yy, z1, None)
        c.b.set(xx, 1, z1, c.m(c.S["floor"]))
    for xx in (gx - 1, gx + 1):
        for yy in (2, 3):
            c.b.set(xx, yy, z1, c.m("dark_oak_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]"))
    for xx in range(gx - 1, gx + 2):
        c.b.set(xx, 5, z1, c.m(c.S["wall"]))
    stack = ["barrel[facing=up,open=false]", "chest[facing=south,type=single,waterlogged=false]", "hay_block[axis=y]", "barrel[facing=up,open=false]"]
    aisle = c.p.get("aisle", 4)
    rows = 0
    for x in range(x0 + 1, x1):
        if (x - x0) % aisle == 0:
            continue
        rows += 1
        for z in range(z0 + 1, z1 - 3):
            item = stack[(x + z) % len(stack)]
            c.put(x, 2, z, item)
            if (x + z) % 3:
                c.put(x, 3, z, "barrel[facing=up,open=false]")
    if c.n > 1:
        pass


def furnish_hotel(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    zc = (z0 + z1) // 2
    b = c.b
    # лестница вдоль коридора (на запад): заменяет стандартную
    for k in range(c.n - 1):
        fy = c.fy(k)
        for i in range(c.fh):
            b.stair(x0 + 1 + i, fy + 1 + i, zc, "oak", "east")
        for i in range(c.fh - 1):
            b.set(x0 + 1 + i, fy + c.fh, zc, None)
    # холл первого этажа: ресепшен и диваны
    for x in range(x0 + 6, x0 + 11):
        c.put(x, 2, zc - 3, "dark_oak_planks")
        c.put(x, 3, zc - 3, "oak_pressure_plate[powered=false]" if x % 2 else "lantern[hanging=false]")
    c.put(x0 + 2, 2, z1 - 2, "oak_stairs[facing=east,half=bottom,shape=straight]")
    c.put(x0 + 3, 2, z1 - 2, "oak_stairs[facing=east,half=bottom,shape=straight]")
    for x in range(x0 + 12, x1 - 1, 3):
        table(c, x, 2, z1 - 3)
    # этажи с номерами
    w = c.p.get("room_w", 4)
    for k in range(1, c.n):
        fy = c.fy(k)
        y = fy + 1
        px = x0 + 5
        j = 0
        while px + w + 1 <= x1:
            # перегородки между номерами (север и юг)
            for yy in range(y, y + c.fh - 1):
                for z in list(range(z0 + 1, zc - 1)) + list(range(zc + 2, z1)):
                    c.b.set(px, yy, z, c.m(c.S["wall"]))
            j += 1
            px += w + 1
        # перегородка вдоль коридора с дверями
        for x in range(x0 + 5, x1):
            for yy in range(y, y + c.fh - 1):
                b.set(x, yy, zc - 1, c.m(c.S["wall"]))
                b.set(x, yy, zc + 1, c.m(c.S["wall"]))
        px = x0 + 5
        while px + w + 1 <= x1:
            mid = px + (w + 1) // 2
            for (zz, face) in ((zc - 1, "south"), (zc + 1, "north")):
                b.set(mid, y, zz, None)
                b.set(mid, y + 1, zz, None)
                b.door(mid, y, zz, face, c.S["door"])
            # мебель
            bed(c, px + 1, y, z0 + 1, "south", c.rng.choice(["red", "blue", "green", "yellow", "white"]))
            bed(c, px + 1, y, z1 - 1 - 1, "north", c.rng.choice(["red", "blue", "green", "yellow", "white"]))
            c.put(px + 2, y, z0 + 1, "barrel[facing=up,open=false]")
            c.put(px + 2, y, z1 - 1, "barrel[facing=up,open=false]")
            px += w + 1


def furnish_bank(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    zc = z0 + (z1 - z0) // 2
    # операционная стойка с решёткой
    for x in range(x0 + 2, x1 - 1):
        if x == (x0 + x1) // 2:
            continue
        c.put(x, 2, zc, "quartz_block")
        c.put(x, 3, zc, "iron_bars")
    # хранилище в дальнем углу
    vx0, vx1, vz0, vz1 = x1 - 5, x1 - 1, z0 + 1, z0 + 4
    for x in range(vx0, vx1 + 1):
        for z in range(vz0, vz1 + 1):
            edge = x in (vx0, vx1) or z in (vz0, vz1)
            for y in (2, 3, 4):
                if edge:
                    c.b.set(x, y, z, c.m("iron_block"))
                elif y == 2:
                    c.b.set(x, y, z, c.m("gold_block") if (x + z) % 2 else c.m("barrel[facing=up,open=false]"))
                else:
                    c.b.set(x, y, z, None)
    vdx = (vx0 + vx1) // 2
    for y in (2, 3):
        c.b.set(vdx, y, vz1, None)
    c.b.door(vdx, 2, vz1, "south", "iron")
    # скамьи для клиентов
    for x in range(x0 + 2, x0 + 7, 2):
        c.put(x, 2, z1 - 2, "quartz_stairs[facing=north,half=bottom,shape=straight]")
    # кабинеты на верхних этажах
    for k in range(1, c.n):
        y = c.fy(k) + 1
        table(c, x0 + 4, y, z0 + 3, chairs=False)
        for yy in (y, y + 1):
            c.put(x1 - 1, yy, z0 + 1, "bookshelf")
            c.put(x1 - 2, yy, z0 + 1, "bookshelf")
        c.put(x0 + 4, y, z0 + 4, "dark_oak_stairs[facing=north,half=bottom,shape=straight]")


def furnish_power(c):
    x0, x1, z0, z1 = c.x0, c.x1, c.z0, c.z1
    y = 2
    # ряд генераторов
    for x in range(x0 + 2, x1 - 1, 3):
        c.put(x, y, z0 + 2, "iron_block")
        c.put(x, y + 1, z0 + 2, "redstone_block")
        c.put(x, y + 2, z0 + 2, "iron_block")
        c.put(x, y + 3, z0 + 2, "lightning_rod[facing=up,powered=false,waterlogged=false]")
        c.put(x, y, z0 + 4, "blast_furnace[facing=south,lit=false]")
        for z in range(z0 + 3, z0 + 4):
            c.put(x, y, z, "iron_bars")
    # цистерны
    for x in range(x0 + 2, x1 - 1, 4):
        for z in range(z1 - 4, z1 - 1):
            c.put(x, y, z, "copper_block")
            c.put(x + 1, y, z, "copper_block")
            c.put(x, y + 1, z, "copper_block")
            c.put(x + 1, y + 1, z, "copper_block")
    # пульт управления
    for x in range(x1 - 5, x1 - 1):
        c.put(x, y, z1 - 6, "smooth_stone")
        c.put(x, y + 1, z1 - 6, "redstone_lamp[lit=false]" if x % 2 else "lever[face=floor,facing=north,powered=false]")
    c.put(x1 - 3, y, z1 - 5, "lectern[facing=north,has_book=false,powered=false]")
    # дымовая труба внутри здания, выходящая через крышу
    chx, chz = x1 - 2, z0 + 2
    top = c.ry + c.p.get("stack", 6)
    for yy in range(y, top + 1):
        for (ddx, ddz) in ((0, 0), (1, 0), (0, 1), (1, 1)):
            if c.interior(chx + ddx, chz + ddz) or yy > c.ct:
                c.b.set(chx + ddx, yy, chz + ddz, c.m("bricks" if yy % 5 else "stone_bricks"))


FURNISH = dict(house=furnish_house, shop=furnish_shop, workshop=furnish_workshop, restaurant=furnish_restaurant,
               warehouse=furnish_warehouse, hotel=furnish_hotel, bank=furnish_bank, power_plant=furnish_power)


def build_one(G, cat, name, title, desc, p):
    w = p["x1"] - p["x0"] + 1
    d = p["z1"] - p["z0"] + 1
    fh = p.get("fh", 4)
    roof_h = max(w, d) // 2 + 3 + p.get("stack", 0) + (8 if "chimney" in p.get("extras", ()) else 0) + (7 if "tower" in p.get("extras", ()) else 0)
    sy = 1 + fh * p["floors"] + 1 + roof_h
    b = G.B(name, title, cat, p["x1"] + 2, min(sy, 48), p["z1"] + 4, desc)
    p = dict(p, seed=int(hashlib.md5(name.encode()).hexdigest()[:8], 16))
    if p["floors"] > 1 and p.get("own_stairs") is None and cat == "hotel":
        p["own_stairs"] = True
    G.EXTRA.clear()
    c = Ctx(G, b, p)
    shell(c)
    roof(c)
    extras(c)
    FURNISH[cat](c)
    b.pal_extra = dict(G.EXTRA)
    return b


# ---------------------------------------------------------------- наборы по 20 зданий

def specs():
    """Возвращает {категория: [(суффикс-названия, параметры)...]} - новые варианты (без уже существующих зданий)."""
    S = STYLE_KEYS

    def mk(i, w, d, floors, style, roof_kind, ex=(), **kw):
        p = dict(x0=1, x1=w, z0=1, z1=d, floors=floors, style=style, roof=roof_kind, door_x=1 + w // 2, extras=tuple(ex))
        p.update(kw)
        return p

    out = {}
    roofs = ["gable_x", "gable_z", "hip"]
    # ---- дома (3-20)
    house_names = ["Коттедж", "Бунгало", "Дом из дерева", "Усадьба", "Дом у реки", "Лесная избушка", "Таунхаус", "Охотничий домик", "Дом «Уют»",
                   "Дом «Крепость»", "Каменный дом", "Дом рыбака", "Дом ремесленника", "Дом садовода", "Особняк", "Дом «Просторный»",
                   "Кирпичный дом", "Белый дом"]
    out["house"] = []
    sizes = [(7, 7), (9, 7), (9, 9), (11, 9), (8, 8), (7, 9), (10, 8), (9, 11), (11, 11), (8, 10)]
    for i, t in enumerate(house_names):
        w, d = sizes[i % len(sizes)]
        fl = 1 + (i % 3 == 1) + (i % 5 == 4)
        d = max(d, 9) if fl > 1 else d
        ex = [["porch"], ["chimney", "porch"], ["balcony"], ["chimney"], ["porch", "fence"], ["tower"], ["chimney", "garden"]][i % 7]
        ex = [e for e in ex if e != "balcony" or fl > 1]
        out["house"].append((t, mk(i, w, d, fl, S[(i * 3 + 1) % len(S)], roofs[(i + i // 3) % 3] if i % 6 != 5 else "flat",
                                   ex, garden="garden" in ex or i % 4 == 0, window_step=2 + (i % 2))))
    # ---- магазины (2-20)
    shop_names = ["Булочная", "Мясная лавка", "Бакалея", "Оружейная лавка", "Аптека", "Книжная лавка", "Овощной магазин", "Цветочный магазин",
                  "Ювелирный магазин", "Магазин одежды", "Рыбная лавка", "Лавка зелий", "Универмаг", "Хозяйственный магазин", "Кондитерская",
                  "Лавка редкостей", "Магазин «Угол»", "Магазин «Перекрёсток»", "Торговый дом"]
    colors = [("red_wool", "white_wool"), ("blue_wool", "white_wool"), ("green_wool", "white_wool"), ("yellow_wool", "white_wool"),
              ("orange_wool", "white_wool"), ("cyan_wool", "white_wool"), ("purple_wool", "white_wool")]
    out["shop"] = []
    sizes = [(9, 7), (11, 7), (9, 9), (13, 9), (11, 9), (10, 8), (12, 8)]
    for i, t in enumerate(shop_names):
        w, d = sizes[i % len(sizes)]
        fl = 2 if i % 4 == 3 else 1
        d = max(d, 9) if fl > 1 else d
        ex = [["awning"], ["awning", "sign"], ["sign"], ["awning", "balcony"], ["awning", "chimney"]][i % 5]
        ex = [e for e in ex if e != "balcony" or fl > 1]
        out["shop"].append((t, mk(i, w, d, fl, S[(i * 5 + 2) % len(S)], roofs[i % 3] if i % 7 != 6 else "flat", ex,
                                  awning=colors[i % len(colors)], window_step=2 + (i % 2))))
    # ---- мастерские (3-20)
    ws_names = ["Столярная мастерская", "Гончарная мастерская", "Кожевенная мастерская", "Ткацкая мастерская", "Оружейная кузня", "Плотницкая",
                "Алхимическая лаборатория", "Ювелирная мастерская", "Каменотёсная", "Пекарня-мастерская", "Мастерская механика", "Стекольная мастерская",
                "Мельница", "Литейная", "Чеканная мастерская", "Мастерская картографа", "Лесопилка", "Большая мастерская"]
    out["workshop"] = []
    sizes = [(11, 9), (13, 9), (13, 11), (15, 11), (11, 11), (12, 9), (14, 10)]
    for i, t in enumerate(ws_names):
        w, d = sizes[i % len(sizes)]
        ex = [["chimney"], ["chimney", "sign"], ["porch", "chimney"], ["fence", "chimney"], ["sign"], ["chimney", "garden"]][i % 6]
        out["workshop"].append((t, mk(i, w, d, 1 + (i % 6 == 5), S[(i * 7 + 3) % len(S)], roofs[(i + 1) % 3], ex, forge=i % 3 == 0, gate=i % 2 == 0,
                                      window_step=2 + (i % 2), fh=4 + (i % 4 == 3))))
    # ---- рестораны (2-20)
    rs_names = ["Кафе", "Таверна", "Пиццерия", "Трактир", "Кофейня", "Бистро", "Стейк-хаус", "Чайный домик", "Закусочная", "Ресторан «Летний»",
                "Пивная", "Суши-бар", "Столовая", "Ресторан на двух этажах", "Бар", "Харчевня", "Гастропаб", "Ресторан «Вечерний»", "Банкетный зал"]
    out["restaurant"] = []
    sizes = [(11, 9), (13, 9), (13, 11), (15, 11), (11, 11), (12, 10), (14, 9)]
    for i, t in enumerate(rs_names):
        w, d = sizes[i % len(sizes)]
        fl = 2 if i % 5 == 3 else 1
        d = max(d, 11) if fl > 1 else d
        ex = [["porch", "chimney"], ["awning", "sign"], ["porch", "sign"], ["chimney", "fence"], ["awning"], ["sign", "garden"]][i % 6]
        out["restaurant"].append((t, mk(i, w, d, fl, S[(i * 5 + 4) % len(S)], roofs[(i + 2) % 3] if i % 8 != 7 else "flat", ex,
                                        awning=colors[(i + 2) % len(colors)], window_step=2 + (i % 2))))
    # ---- склады (3-20)
    wh_names = ["Склад зерна", "Склад инструментов", "Холодный склад", "Лесной склад", "Рудный склад", "Портовый склад", "Склад стройматериалов",
                "Оружейный склад", "Продуктовый склад", "Ангар", "Бондарня", "Склад «Высокий»", "Угольный склад", "Тканевый склад", "Сенной сарай",
                "Таможенный склад", "Склад-эллинг", "Складской комплекс"]
    out["warehouse"] = []
    sizes = [(13, 11), (15, 11), (15, 13), (17, 13), (19, 13), (21, 15), (13, 13), (17, 11), (19, 15)]
    for i, t in enumerate(wh_names):
        w, d = sizes[i % len(sizes)]
        ex = [[], ["sign"], ["fence"], ["sign", "fence"], ["chimney"]][i % 5]
        out["warehouse"].append((t, mk(i, w, d, 1, S[(i * 3 + 5) % len(S)], roofs[i % 2] if i % 5 != 4 else "flat", ex, aisle=3 + (i % 3),
                                       fh=5 + (i % 3 == 2), window_step=3)))
    # ---- отели (2-20)
    ht_names = ["Гостиница", "Постоялый двор", "Мотель", "Пансион", "Курортный отель", "Отель «Центральный»", "Загородный отель", "Бутик-отель",
                "Горный отель", "Приморский отель", "Хостел", "Гостевой дом", "Отель у вокзала", "Отель «Старый замок»", "Деловой отель", "Семейный отель",
                "Старинная гостиница", "Современный отель", "Отель «Люкс»"]
    out["hotel"] = []
    sizes = [(21, 13), (23, 13), (25, 13), (23, 15), (27, 15), (21, 15), (25, 15)]
    for i, t in enumerate(ht_names):
        w, d = sizes[i % len(sizes)]
        fl = 2 + (i % 3 == 1) + (i % 5 == 4)
        ex = [["porch"], ["pillars"], ["sign", "balcony"], ["tower"], ["balcony"], ["garden", "sign"]][i % 6]
        ex = [e for e in ex if e != "balcony" or fl > 1]
        out["hotel"].append((t, mk(i, w, d, fl, S[(i * 3 + 6) % len(S)], roofs[(i + 1) % 3] if i % 4 != 3 else "flat", ex, room_w=4 + (i % 2),
                                   garden=i % 3 == 0, rooftop=True)))
    # ---- банки (2-20)
    bk_names = ["Городской банк", "Сберегательная касса", "Торговый банк", "Банк «Золотой слиток»", "Малый банк", "Народный банк", "Центральное отделение",
                "Кредитный союз", "Банк с хранилищем", "Старый банк", "Современный банк", "Мраморный банк", "Каменный банк", "Банк-крепость",
                "Купеческий банк", "Строгий банк", "Банк на площади", "Двухэтажный банк", "Инвестиционный банк"]
    out["bank"] = []
    sizes = [(13, 11), (15, 11), (15, 13), (17, 13), (13, 13), (17, 11), (19, 13)]
    styles = ["quartz", "stone", "deepslate", "sand", "brick", "modern", "copper", "stone", "quartz", "cobble"]
    for i, t in enumerate(bk_names):
        w, d = sizes[i % len(sizes)]
        fl = 2 if i % 3 == 2 else 1
        ex = [["pillars"], ["pillars", "sign"], ["sign"], ["pillars", "tower"], ["fence", "sign"]][i % 5]
        out["bank"].append((t, mk(i, w, d, fl, styles[i % len(styles)], roofs[(i + 2) % 3] if i % 3 != 1 else "flat", ex, rooftop=i % 2 == 0)))
    # ---- электростанции (2-20)
    pw_names = ["Тепловая станция", "Угольная станция", "Малая ТЭЦ", "Подстанция", "Дизельная станция", "Генераторная", "Паровая станция",
                "Мини-ТЭС", "Трансформаторная подстанция", "Станция «Дымка»", "Котельная", "Энергоблок", "Резервная станция", "Станция «Север»",
                "Станция «Юг»", "Модульная станция", "Старая станция", "Новая станция", "Большая электростанция"]
    out["power_plant"] = []
    sizes = [(13, 11), (15, 11), (15, 13), (17, 13), (19, 13), (13, 13), (17, 15)]
    styles = ["brick", "stone", "deepslate", "modern", "copper", "cobble", "stone"]
    for i, t in enumerate(pw_names):
        w, d = sizes[i % len(sizes)]
        ex = [["sign"], ["fence"], [], ["fence", "sign"], ["tower"]][i % 5]
        out["power_plant"].append((t, mk(i, w, d, 1, styles[i % len(styles)], "flat" if i % 3 else "gable_x", ex, rooftop=True, fh=6 + (i % 2),
                                         stack=5 + (i % 5), window_step=3)))
    return out
