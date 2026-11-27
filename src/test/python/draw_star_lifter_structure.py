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
              terminal="#537892", interface="#6de6c6", star_lifter="#fff1ad")
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
                       f"Controller {tuple(g['controller'])} · four reserved interfaces · access from +X")
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
    args = parser.parse_args()
    make_plates(args.mesh, args.builder_nbt, args.geometry_dir, args.output)
