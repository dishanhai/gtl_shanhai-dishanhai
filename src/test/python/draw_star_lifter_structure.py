"""Draw orthographic engineering plates from the Builder export and production mesh."""

from argparse import ArgumentParser
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
from io import BytesIO

import nbtlib as nbt
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.spatial.transform import Rotation

from compile_star_lifter_structure import mesh_wedge, read_mesh


COLORS = dict(beam="#8292c5", armor="#65568a", ceramic="#a2b7d4", socket="#c7b4f4",
              conduit="#eabf73", magnet="#a58de8", extractor="#7ed4f1", stellar="#99caff",
              terminal="#537892", interface="#6de6c6", star_lifter="#fff1ad", manifold="#eabf73",
              inner_latch="#9ba7df")
BG = "#131b2b"
INK = "#e0ecff"
MUTED = "#a9b9d6"
FACES = ((0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7))


def font(size):
    fonts = Path("C:/Windows/Fonts")
    for path in (fonts / "msyh.ttc", Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")):
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def card(title, note, size=(850, 760)):
    picture = Image.new("RGB", size, BG)
    draw = ImageDraw.Draw(picture)
    draw.text((26, 20), title, font=font(25), fill=INK)
    draw.text((27, 58), note, font=font(15), fill=MUTED)
    return picture, draw


def view(right, up, look, origin, scale):
    axes = np.array([right, up, look], dtype=float)
    axes /= np.linalg.norm(axes, axis=1)[:, None]

    def project(points):
        q = np.asarray(points) @ axes.T
        q[..., 0] = q[..., 0] * scale + origin[0]
        q[..., 1] = -q[..., 1] * scale + origin[1]
        return q

    return project


def paint_blocks(draw, points, colors, project, cell, uniform=None):
    world = project(points)
    half = cell / 2
    for i in np.argsort(world[:, 2]):
        x, y, _ = world[i]
        draw.rectangle((x-half, y-half, x+half, y+half), fill=uniform or colors[i])


def paint_mesh(draw, rings, scale, radius, project, wire=False):
    faces = []
    for params, bands in rings:
        rotation = Rotation.from_quat(params[-4:])
        for surface, dims in bands:
            points = project(mesh_wedge(rotation, scale, dims))
            for face in FACES:
                patch = points[list(face)]
                faces.append((patch[:, 2].mean(), [tuple(p[:2]) for p in patch], COLORS[surface.lower()]))
    origin = project([(0, 0, 0)])[0]
    screen_radius = radius * np.linalg.norm(project(np.eye(3))[:,0] - origin[0])
    ellipse = (origin[0]-screen_radius, origin[1]-screen_radius,
               origin[0]+screen_radius, origin[1]+screen_radius)
    for front in (False, True):
        if front:
            draw.ellipse(ellipse, outline="#69e9ff" if wire else None, fill=None if wire else COLORS["stellar"])
        for depth, vertices, tint in sorted(faces, key=lambda face: face[0]):
            if (depth >= 0) == front:
                draw.polygon(vertices, fill=None if wire else tint, outline="#69e9ff" if wire else None)


def cabin_plates(builder_export, geometry_path, output):
    output.mkdir(parents=True, exist_ok=True)
    data = json.loads(geometry_path.read_text(encoding="utf-8"))
    controller = np.asarray(data["controller"])
    export = nbt.load(builder_export)
    fixed = {tuple(p["pos"]) for p in data["builder_plan"]}
    ring = []
    cabin = []
    tints = []
    for block in export["blocks"]:
        pos = tuple(map(int, block["pos"]))
        if pos in fixed:
            material = str(export["palette"][int(block["state"])]["Name"]).split(":")[1]
            cabin.append(np.asarray(pos) - controller)
            tints.append(COLORS[material.removeprefix("star_lifter_")])
        elif pos[0] >= controller[0] - 19 and abs(pos[2] - controller[2]) <= 15:
            ring.append(np.asarray(pos) - controller)
    cabin = np.asarray(cabin)
    tints = np.asarray(tints)
    ring = np.asarray(ring)
    front = view((0,0,1), (0,1,0), (1,0,0), (425,355), 31)
    side = view((1,0,0), (0,1,0), (0,0,1), (605,355), 48)

    plate, draw = card("摘星者固定主控舱 · 正面", "薄荷色为六个可更换仓位，金白色为固定汇流槽", (850, 650))
    paint_blocks(draw, cabin, tints, front, 28)
    plate.save(output / "cabin-front.png")

    plate, draw = card("摘星者固定主控舱 · 侧面", "蓝灰色为外环静态模板，彩色为固定构件", (850, 650))
    paint_blocks(draw, ring, None, side, 45, "#3e4a65")
    paint_blocks(draw, cabin, tints, side, 45)
    plate.save(output / "cabin-side.png")

    plate, draw = card("汇流脊柱 · 四层剖面", "从外侧操作面向环体方向剖切，保留中央设备口和上下承力肋", (980, 950))
    for column, x in enumerate((1, 0, -1, -2)):
        origin = (252 + 476 * (column % 2), 294 + 400 * (column // 2))
        proj = view((0,0,1), (0,1,0), (1,0,0), origin, 19)
        mask = cabin[:,0] == x
        paint_blocks(draw, cabin[mask], tints[mask], proj, 17)
        draw.text((origin[0]-190,origin[1]-162), f"X = {controller[0]+x}", font=font(19), fill=INK)
    plate.save(output / "cabin-section.png")

    plate, draw = card("固定区域体积 · 改造前后", "只显示固定体，从同一侧斜视，未绘制动态环体", (1160, 680))
    before = (cabin[:,0] == 0) & (np.abs(cabin[:,1]) <= 1) & (np.abs(cabin[:,2]) <= 1)
    for origin, keep, label in (((300,365), before, "原 3×3 操作面"),
                                ((865,365), np.ones(len(cabin), dtype=bool), "固定舱、内部支撑及输运槽")):
        proj = view((.55,0,-1),(-.25,1,-.2),(1,.4,.55),origin,23)
        paint_blocks(draw, cabin[keep], tints[keep], proj, 20)
        draw.text((origin[0]-110,585), label, font=font(18), fill=INK)
    plate.save(output / "cabin-before-after.png")

    plate, draw = card("动静分区 · 主控侧", "彩色固定区与灰蓝色的外环接管区均来自 Builder 导出", (850, 650))
    under = view((0,0,1),(-1,0,0),(0,1,0),(425,445),29)
    paint_blocks(draw, ring, None, under, 27, "#3e4a65")
    paint_blocks(draw, cabin, tints, under, 27)
    plate.save(output / "cabin-partitions.png")

    detail = view((1,0,0),(0,1,0),(0,0,1),(755,355),36)
    is_latch = tints == COLORS["inner_latch"]
    radius = data["rings"][-1]["radial_limits"][1]
    for side, cut, name in (("右侧", 9, "cabin-coupling.png"), ("左侧", -13, "cabin-coupling-left.png")):
        plate, draw = card(f"{side}闭合扣箍 · 径向剖面", "左为内侧锁梁，右接主控舱，外环截面从中通过", (850, 700))
        part = cabin[:,2] == cut
        resting = ring[ring[:,2] == cut]
        paint_blocks(draw, resting, None, detail, 35, "#3e4a65")
        paint_blocks(draw, cabin[part & ~is_latch], tints[part & ~is_latch], detail, 35)
        for x, y, _ in detail(cabin[part & is_latch]):
            draw.rectangle((x-11.25, y-18, x+11.25, y+18), fill=COLORS["inner_latch"])
        radial_x = (radius**2 - (cut + controller[2] - data["center"][2])**2)**.5
        point = detail((radial_x + data["center"][0] - controller[0], 0, 0))
        draw.line((point[0],point[1]-100,point[0],point[1]+100),fill="#f8b267",width=3)
        draw.text((35,653), f"固定方块至所有环体的最小净距下界  {min(data['fixed_clearance']):.3f} 格",
                  font=font(18), fill=INK)
        plate.save(output / name)


def make_plates(mesh, export, data_dir, output):
    output.mkdir(parents=True, exist_ok=True)
    g = json.loads((data_dir / "star-lifter-geometry.json").read_text(encoding="utf-8"))
    bank = np.load(data_dir / "star-lifter-ring-cells.npz")
    scale, star, rings = read_mesh(mesh)
    data = nbt.load(export)
    palette = [str(p["Name"]).split(":")[1].removeprefix("star_lifter_") for p in data["palette"]]
    points = np.asarray([list(block["pos"]) for block in data["blocks"]], dtype=int) - g["center"]
    colors = [COLORS[palette[int(block["state"])]] for block in data["blocks"]]
    size = " × ".join(map(str, g["size"]))
    projection = view((1,0,-1), (-.42,1,-.42), (1,.84,1), (425,410), 3.55)
    static, draw = card("Star Lifter | Builder block structure", f"{size} blocks including access space · {len(points):,} placed blocks")
    paint_blocks(draw, points, colors, projection, 4)
    static.save(output / "static.png")
    dynamic, draw = card("Star Lifter | Production mesh at phase zero", "Exact ring band geometry · same origin, scale and camera")
    paint_mesh(draw, rings, scale, scale*star, projection)
    dynamic.save(output / "dynamic-mesh.png")
    pair = Image.new("RGB", (1700, 760), BG)
    pair.paste(static, (0, 0))
    pair.paste(dynamic, (850, 0))
    pair.save(output / "comparison.png")
    overlay, draw = card("Star Lifter | Block and mesh overlay", "Solid voxels · translucent cyan mesh · phase zero")
    paint_blocks(draw, points, colors, projection, 4)
    transparent = Image.new("RGBA", overlay.size)
    paint_mesh(ImageDraw.Draw(transparent), rings, scale, scale*star, projection, wire=True)
    transparent.putalpha(transparent.getchannel("A").point(lambda value: value * .46))
    overlay.paste(transparent, (0, 0), transparent)
    overlay.save(output / "overlay.png")

    for name, right, up, front in (("XY", (1,0,0), (0,1,0), (0,0,1)),
                                  ("XZ", (1,0,0), (0,0,-1), (0,1,0)),
                                  ("YZ", (0,0,1), (0,1,0), (1,0,0))):
        plate, draw = card(f"Star Lifter | {name}", f"Structure center {tuple(g['center'])} in Builder coordinates")
        paint_blocks(draw, points, colors, view(right, up, front, (425,410), 3.55), 3.55)
        plate.save(output / f"plane-{name}.png")
    for index, (params, _) in enumerate(rings):
        ring = bank["points"][bank["masks"] & (1 << index) != 0]
        R = Rotation.from_quat(params[-4:])
        right, normal, up = R.apply(np.eye(3))
        rr, rw, rh = g["rings"][index]["radius_width_height"]
        plate, draw = card(f"Star Lifter | Ring {index+1}", f"R {rr:.2f} · W {rw:.2f} · H {rh:.2f} blocks · {len(ring):,} voxels")
        paint_blocks(draw, ring, None, view(right, up, normal, (425,410), 3.55), 3.55, "#9abbf3")
        plate.save(output / f"ring-{index+1}.png")
        ring = R.inv().apply(ring)
        radius = np.hypot(ring[:,0], ring[:,2])
        angle = np.mod(np.arctan2(ring[:,2],ring[:,0]), 2*np.pi)
        plate, draw = card(f"Star Lifter | Ring {index+1} unwrap", "X = 0–360° azimuth · Y = radial offset from centerline", (1120, 300))
        for a, r in zip(angle, radius):
            x = 34 + a * 1040/(2*np.pi)
            y = 185 - (r-rr)*10
            draw.rectangle((x,y,x+2,y+2), fill="#9abbf3")
        plate.save(output / f"ring-{index+1}-unwrap.png")
        plate, draw = card(f"Star Lifter | Ring {index+1} cross-section", "Rib in the first bay · X = radial offset · Y = normal to plane", (710, 470))
        at_rib = (angle * rr < 2) | (2*np.pi*rr - angle*rr < .5)
        for r, height in zip(radius[at_rib], ring[at_rib,1]):
            x, y = 355 + (r-rr)*21, 250-height*21
            draw.rectangle((x-9,y-9,x+9,y+9), fill="#9abbf3")
        plate.save(output / f"ring-{index+1}-section.png")

    controller = np.asarray(g["controller"])-g["center"]
    coords = np.max(np.abs(points-controller), axis=1) <= 4
    plate, draw = card("Star Lifter | East service node",
                       f"Controller {tuple(g['controller'])} · {len(g['interfaces'])} hatch positions · access from +X")
    node = view((0,0,1), (0,1,0), (1,0,0), (425,390), 62)
    paint_blocks(draw, points[coords]-controller, np.asarray(colors)[coords], node, 57)
    for x,y,z in g["interfaces"]:
        px,py,_ = node(np.array([x,y,z])-g["controller"])
        draw.text((px-26,py-7), f"{y},{z}", fill=BG, font=font(12))
    draw.text((36,670), "Gold = controller · Mint = reserved interface · Steel blue = fixed node", font=font(16), fill=INK)
    plate.save(output / "service-node.png")

    with ZipFile(output / "y-layer-blueprints.zip", "w", ZIP_DEFLATED) as sheets:
        for y in range(g["size"][1]):
            layer = points[:,1] == y-g["center"][1]
            plate, draw = card(f"Star Lifter | Layer Y={y}",
                               f"Builder local coordinates · X east / Z south · {layer.sum():,} blocks", (1010, 1070))
            proj = view((1,0,0), (0,0,-1), (0,1,0), (55+5*g["center"][0],128+5*g["center"][2]), 5)
            paint_blocks(draw, points[layer], np.asarray(colors)[layer], proj, 4)
            for n,(name,tint) in enumerate(COLORS.items()):
                x,y0=27+(n%6)*160, 1008+(n//6)*24
                draw.rectangle((x,y0,x+12,y0+12),fill=tint)
                draw.text((x+16,y0-3),name,fill=INK,font=font(13))
            buffer=BytesIO()
            plate.save(buffer, format="PNG")
            sheets.writestr(f"Y-{y:03}.png", buffer.getvalue())


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    for argument in ("mesh", "builder_nbt", "geometry_dir", "output"):
        parser.add_argument(argument, type=Path)
    parser.add_argument("--cabin-only", action="store_true")
    args = parser.parse_args()
    if not args.cabin_only:
        make_plates(args.mesh, args.builder_nbt, args.geometry_dir, args.output)
    cabin_plates(args.builder_nbt, args.geometry_dir / "star-lifter-geometry.json", args.output)
