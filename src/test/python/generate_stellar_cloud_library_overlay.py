"""山海：绘制星律云端样板库主控的 16×16 像素动画图集。"""

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw


SIZE = 16
FRAME_COUNT = 16
OUTPUT = (Path(__file__).resolve().parents[3] / "src/main/resources/assets/gt_shanhai/"
          "textures/block/machine/part/cloud_pattern_library")
FRAME = (52, 68, 86)
BEVEL = (91, 110, 127)
WELL = (13, 21, 37)
TRACK = (31, 60, 81)
SOCKET = (56, 100, 123)
CYAN = ((36, 108, 140), (48, 180, 206), (105, 229, 242), (205, 252, 255))
VIOLET = ((77, 65, 133), (129, 104, 201), (177, 145, 246), (235, 216, 255))


def pulse(base, glow, path, frame, colors):
    """尾迹逐像素沿闭合路径移动，两个图层使用同一组坐标。"""
    for offset, color in enumerate(colors):
        point = path[(frame + offset - len(colors) + 1) % len(path)]
        base.point(point, fill=color)
        glow.point(point, fill=color)


def panel(base):
    base.rectangle((0, 0, 15, 15), fill=(24, 33, 48))
    base.rectangle((1, 1, 14, 14), fill=FRAME)
    base.line((1, 1, 14, 1), fill=BEVEL)
    base.line((1, 2, 1, 14), fill=(69, 88, 104))
    base.line((2, 14, 14, 14), fill=(31, 42, 59))
    base.line((14, 2, 14, 13), fill=(31, 42, 59))
    base.rectangle((2, 2, 13, 13), fill=WELL, outline=(29, 42, 59))
    base.point([(2, 2), (13, 2), (2, 13), (13, 13)], fill=(138, 155, 167))


def front(base, glow, frame):
    # 三层档案格，扫描光标来回扫过，底部节点反向发送数据。
    for y in (4, 7, 10):
        base.rectangle((4, y, 11, y + 1), fill=TRACK)
        base.point((4, y), fill=(112, 97, 180))
        base.point((11, y), fill=SOCKET)
    scan = 4 + min(frame, FRAME_COUNT - 1 - frame)
    for y in (4, 7, 10):
        base.point((scan, y), fill=CYAN[3])
        glow.point((scan, y), fill=CYAN[3])
        base.point((scan, y + 1), fill=CYAN[1])
        glow.point((scan, y + 1), fill=CYAN[1])
    footer = tuple((x, 12) for x in range(4, 13)) + tuple((x, 11) for x in range(11, 4, -1))
    pulse(base, glow, footer, -frame, VIOLET)


def back(base, glow, frame):
    path = (
        (4, 6), (5, 5), (6, 5), (7, 6), (8, 7), (9, 7), (10, 7), (11, 7),
        (11, 8), (10, 9), (9, 9), (8, 9), (7, 8), (6, 7), (5, 8), (4, 7),
    )
    base.rectangle((3, 4, 5, 11), outline=SOCKET)
    base.rectangle((10, 5, 12, 10), outline=SOCKET)
    base.line((4, 5, 4, 10), fill=TRACK)
    base.point(path, fill=TRACK)
    pulse(base, glow, path, frame, CYAN)
    pulse(base, glow, path, frame + 8, VIOLET)


def side(base, glow, frame):
    rail = tuple((x, 6) for x in range(4, 12)) + tuple((x, 7) for x in range(11, 3, -1))
    lower = tuple((x, y + 3) for x, y in rail)
    base.line((3, 5, 12, 5), fill=SOCKET)
    base.line((3, 11, 12, 11), fill=SOCKET)
    base.point(rail + lower, fill=TRACK)
    pulse(base, glow, rail, frame, CYAN)
    pulse(base, glow, lower, 15 - frame, VIOLET)


def top(base, glow, frame):
    orbit = (
        (6, 5), (7, 5), (8, 5), (9, 5), (10, 6), (10, 7), (10, 8), (10, 9),
        (9, 10), (8, 10), (7, 10), (6, 10), (5, 9), (5, 8), (5, 7), (5, 6),
    )
    base.point(orbit, fill=TRACK)
    base.line((7, 3, 7, 12), fill=TRACK)
    base.line((3, 7, 12, 7), fill=TRACK)
    base.rectangle((6, 6, 9, 9), fill=SOCKET, outline=FRAME)
    base.rectangle((7, 7, 8, 8), fill=CYAN[0])
    pulse(base, glow, orbit, frame, CYAN)
    pulse(base, glow, orbit, frame + 8, VIOLET)


def bottom(base, glow, frame):
    path = (
        (5, 5), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5), (10, 6), (10, 7),
        (10, 8), (9, 8), (8, 8), (7, 8), (6, 8), (5, 8), (5, 7), (5, 6),
    )
    for y in (4, 11):
        base.line((4, y, 11, y), fill=FRAME)
        base.point([(4, y), (11, y)], fill=SOCKET)
    base.point(path, fill=TRACK)
    base.rectangle((7, 6, 8, 8), fill=FRAME)
    pulse(base, glow, path, -frame, VIOLET)
    pulse(base, glow, path, 8 - frame, CYAN)


def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    metadata = json.dumps({"animation": {"frametime": 2, "interpolate": False}}, indent=2) + "\n"
    for draw_face in (front, back, side, top, bottom):
        atlas_size = (SIZE, SIZE * FRAME_COUNT)
        normal_atlas = Image.new("RGBA", atlas_size)
        emissive_atlas = Image.new("RGBA", atlas_size)
        for frame in range(FRAME_COUNT):
            normal = Image.new("RGBA", (SIZE, SIZE))
            emissive = Image.new("RGBA", (SIZE, SIZE))
            base_draw = ImageDraw.Draw(normal)
            panel(base_draw)
            draw_face(base_draw, ImageDraw.Draw(emissive), frame)
            normal_atlas.paste(normal, (0, frame * SIZE))
            emissive_atlas.paste(emissive, (0, frame * SIZE))
        name = f"overlay_{draw_face.__name__}"
        for atlas, suffix in ((normal_atlas, ""), (emissive_atlas, "_emissive")):
            path = output / f"{name}{suffix}.png"
            atlas.save(path)
            path.with_suffix(".png.mcmeta").write_text(metadata, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, nargs="?", default=OUTPUT)
    generate(parser.parse_args().output)
