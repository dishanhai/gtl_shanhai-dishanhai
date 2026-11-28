"""Voxelize the production Star Lifter mesh, then compile the Builder NBT to GT resources.

Dependencies: nbtlib, numpy, scipy, Pillow. The 'mesh' input is stdout from
StarLifterMeshExport. Save its NBT through Builder, installing the node plan
with set_blocks, before running the 'compile' command on the Builder export.
"""

import argparse
from collections import Counter
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path
import struct

import nbtlib as nbt
import numpy as np
from scipy.ndimage import label
from scipy.spatial.transform import Rotation


ID = "star_lifter"
AIR = "minecraft:air"
HOST = f"gt_shanhai:{ID}"
NODE = "gt_shanhai:star_lifter_terminal"
PORT = "gt_shanhai:star_lifter_interface"
MANIFOLD = "gt_shanhai:star_lifter_manifold"
INNER_LATCH = "gt_shanhai:star_lifter_inner_latch"
MATERIALS = {surface: f"gt_shanhai:star_lifter_{surface.lower()}" for surface in
             ("BEAM", "ARMOR", "CERAMIC", "SOCKET", "CONDUIT", "MAGNET", "EXTRACTOR", "STELLAR")}
# Functional faces win where a narrow band shares a cube with a support.
PRIORITY = {surface: index for index, surface in enumerate(MATERIALS)}
SURFACES = tuple(MATERIALS)
FACE_INDICES = ((0, 1, 2), (0, 3, 4), (0, 1, 4), (2, 3, 6), (1, 2, 5), (0, 3, 7))
EDGE_INDICES = ((0, 1), (1, 2), (2, 3), (3, 0), (0, 4))
WORLD_AXES = np.eye(3)


def service_cabin(controller):
    """Fixed operations deck, split transfer spine and the non-contact outer-ring pickups."""
    x0, y0, z0 = map(int, controller)
    cabin = {}

    def put(x, y, z, material):
        cabin[x0 + x, y0 + y, z0 + z] = MATERIALS.get(material, material)

    for side in (-1, 1):
        # Extend the central cross into one contiguous equipment panel.
        put(0, 0, side * 2, PORT)
        put(0, 0, side * 5, "SOCKET")
        put(0, 0, side * 6, "SOCKET")
        put(0, 0, side * 7, "MAGNET")
        put(0, 0, side * 8, "CERAMIC")
        for z in (3, 4):
            put(0, 0, side * z, "BEAM")
        for y in (-1, 1):
            for z, material in enumerate(("ARMOR", MANIFOLD, MANIFOLD, "BEAM", "EXTRACTOR", "SOCKET"), 2):
                put(0, y, side * z, material)
            put(0, y, side * 8, "ARMOR")
            put(0, y, side * 9, "BEAM")
        put(0, 0, side * 9, "MAGNET")
        put(0, 0, side * 10, "CERAMIC")

    for side in (-1, 1):
        # Both spines turn inward above and below the full swept service-node profile.
        for z in range(-7, 8):
            put(0, side * 2, z, "ARMOR" if z % 3 == 0 else "BEAM")
            if abs(z) <= 6:
                put(0, side * 3, z, "EXTRACTOR" if z in (-4, 0, 4) else MANIFOLD)
                put(-1, side * 4, z, "ARMOR" if z % 3 == 0 else "BEAM")
                if z % 3 == 0:
                    put(0, side * 4, z, "SOCKET")
        for z in range(-6, 7):
            put(-1, side * 5, z, MANIFOLD if abs(z) <= 4 else "ARMOR")
        for z in (-6, -3, 0, 3, 6):
            put(-2, side * 5, z, "SOCKET" if z == 0 else "BEAM")
        for z in (-6, 0, 6):
            put(-2, side * 6, z, "MAGNET" if z == 0 else "CERAMIC")
        for z, inner in ((-8, -7), (8, 7)):
            put(0, side * 2, z, "BEAM")
            put(0, side * 3, z, "SOCKET")
            put(0, side, z, "EXTRACTOR")
            put(-1, side * 4, z, "CERAMIC")
            put(-1, side * 4, inner, MANIFOLD)

    # Both cuffs follow the centerline of the control face, including their inner latch columns.
    for side in (-1, 1):
        first = side * 9
        second = side * 10
        for up in (-1, 1):
            for z in range(side * 8, second + side, side):
                put(0, up * 2, z, "BEAM" if z % 2 else "ARMOR")
                put(0, up, z, "ARMOR" if z in (first, second) else "BEAM")
            for z in (first, second):
                for x, y, surface in ((0, 3, "ARMOR"), (-1, 3, "SOCKET"), (-1, 4, "ARMOR"),
                                      (-2, 4, "BEAM"), (-2, 5, "ARMOR"), (-2, 6, "BEAM"), (-3, 6, "SOCKET")):
                    put(x, up * y, z, surface)
                for x in range(-18, -2):
                    put(x, up * 7, z, "ARMOR" if x in (-18, -14, -10, -6) else "BEAM")
        for z in (first, second):
            put(0, 0, z, "MAGNET")
            for y in range(-6, 7):
                put(-18, y, z, INNER_LATCH)
    return cabin


def verify_fixed_clearance(fixed, scale, rings):
    """Conservative clearance over every pose, including the inset inner latch's real box."""
    points = np.asarray(list(fixed), dtype=float)
    bounds = np.array([(.3125, .5, .3125) if material == INNER_LATCH else (.5, .5, .5)
                       for material in fixed.values()])
    near = np.maximum(np.abs(points) - bounds, 0)
    far = np.abs(points) + bounds
    clearance = []
    for parameters, bands in rings:
        limits = np.asarray([band for _, band in bands]) * scale
        if parameters[5]:
            # Rotation keeps axial projection and distance to the axis constant. Bound each
            # prism by those coordinates instead of treating the entire sweep as a sphere.
            azimuth = math.radians(parameters[4])
            axis = np.array([math.cos(azimuth), 0, math.sin(azimuth)])
            tangent = np.array([-axis[2], 0, axis[0]])
            rotation = Rotation.from_quat(parameters[-4:])
            vertices = np.array([mesh_wedge(rotation, scale, band) for _, band in bands])
            axial = vertices @ axis
            radius = np.hypot(vertices @ tangent, vertices[:, :, 1]).max(axis=1)
            center = (points @ axis)[:, None]
            half_width = (bounds @ np.abs(axis))[:, None]
            axial_gap = np.maximum(center - half_width - axial.max(axis=1), axial.min(axis=1) - center - half_width)
            radial_near = np.hypot(np.maximum(np.abs(points @ tangent) - bounds @ np.abs(tangent), 0), near[:, 1])
            radial_gap = radial_near[:, None] - radius
            clearance.append(float(np.hypot(np.maximum(axial_gap, 0), np.maximum(radial_gap, 0)).min()))
        else:
            # Outer ring rotates around Y. Each chordal prism stays inside this radial/height interval.
            inner = limits[:, 2] * np.cos((limits[:, 1] - limits[:, 0]) / (2 * scale))
            radial = np.maximum(np.hypot(near[:, 0], near[:, 2])[:, None] - limits[:, 3],
                                inner - np.hypot(far[:, 0], far[:, 2])[:, None])
            vertical = np.maximum(points[:, 1, None] - .5 - limits[:, 5],
                                  limits[:, 4] - points[:, 1, None] - .5)
            clearance.append(float(np.maximum(radial, vertical).min()))
    if min(clearance) < .25:  # Covers the existing eighth-block collision raster and its tolerance.
        raise ValueError(f"Fixed control cabin enters the ring clearance margin: {clearance}")
    return clearance


def read_mesh(path):
    rings = []
    with path.open(encoding="utf-8") as stream:
        for key, *fields in csv.reader(stream):
            values = list(map(float, fields))
            if key == "scale":
                scale, star = values
            elif key == "ring":
                rings.append((values, []))
            else:
                rings[-1][1].append((key, values))
    return scale, star, rings


def mesh_wedge(rotation, scale, dimensions):
    a, b, inner, outer, bottom, top = dimensions
    vertices = [(r * math.cos(angle), y, r * math.sin(angle)) for y in (bottom, top)
                for r, angle in ((inner, a), (outer, a), (outer, b), (inner, b))]
    return rotation.apply(vertices) * scale


def intersecting_cells(vertices):
    """Separating-axis test between the exact chordal prism and unit cells."""
    bottom = np.floor(vertices.min(axis=0) + .5).astype(int)
    top = np.ceil(vertices.max(axis=0) + .5).astype(int)
    candidates = np.stack(np.meshgrid(*(np.arange(a, b) for a, b in zip(bottom, top)), indexing="ij"), axis=-1)
    candidates = candidates.reshape((-1, 3))
    normals = [np.cross(vertices[b] - vertices[a], vertices[c] - vertices[a]) for a, b, c in FACE_INDICES]
    axes = np.concatenate((normals, WORLD_AXES, *(np.cross(vertices[b] - vertices[a], WORLD_AXES)
                                                   for a, b in EDGE_INDICES)))
    lengths = np.linalg.norm(axes, axis=1)
    axes = axes[lengths > 1e-8] / lengths[lengths > 1e-8, None]
    prism = vertices @ axes.T
    projection = candidates @ axes.T
    padding = np.abs(axes).sum(axis=1) * .5
    # A single touching edge or face has zero interior volume.
    overlaps = (projection + padding > prism.min(axis=0) + 1e-7) & (projection - padding < prism.max(axis=0) - 1e-7)
    return candidates[overlaps.all(axis=1)]


def coordinate_bounds(cells):
    points = np.array(list(cells))
    return points.min(axis=0), points.max(axis=0)


def components(cells):
    low, high = coordinate_bounds(cells)
    grid = np.zeros(tuple(high - low + 1), dtype=bool)
    grid[tuple((np.array(list(cells)) - low).T)] = True
    ids, _ = label(grid)
    return sorted(np.bincount(ids.ravel())[1:].tolist(), reverse=True)


def clean_ring(cells):
    low, high = coordinate_bounds(cells)
    grid = np.zeros(tuple(high - low + 1), dtype=bool)
    grid[tuple((np.array(list(cells)) - low).T)] = True
    ids, _ = label(grid)
    counts = np.bincount(ids.ravel())
    isolated = [point for point in cells if counts[ids[tuple(point - low)]] == 1]
    for point in isolated:
        del cells[point]
    return len(isolated)


def block_tag(name, facing=None):
    tag = nbt.Compound({"Name": nbt.String(name)})
    if facing:
        tag["Properties"] = nbt.Compound({"facing": nbt.String(facing)})
    return tag


def mesh_stage(source, output):
    scale, star, rings = read_mesh(source)
    cells = {}
    ring_report = []
    owners = {}
    for index, (parameters, bands) in enumerate(rings):
        rotation = Rotation.from_quat(parameters[-4:])
        local = {}
        vertices = []
        for surface, dimensions in bands:
            wedge = mesh_wedge(rotation, scale, dimensions)
            vertices.extend(wedge)
            for point in intersecting_cells(wedge):
                point = tuple(point.tolist())
                if PRIORITY[surface] >= PRIORITY.get(local.get(point), -1):
                    local[point] = surface
        removed = clean_ring(local)
        for point, surface in local.items():
            owners[point] = owners.get(point, 0) | 1 << index
            if PRIORITY[surface] >= PRIORITY.get(cells.get(point), -1):
                cells[point] = surface
        vertices = np.asarray(vertices)
        azimuth = math.radians(parameters[4])
        axis = np.array([0, 1, 0] if parameters[6] else [math.cos(azimuth), 0, math.sin(azimuth)])
        spine = np.outer(vertices @ axis, axis)
        normal = vertices - spine
        excursion = np.hypot(normal, np.cross(axis, normal))
        low, high = coordinate_bounds(local)
        radials = [(band[2] * math.cos((band[1] - band[0]) * .5), band[3]) for _, band in bands]
        ring_report.append({
            "radius_width_height": [round(scale * value, 6) for value in parameters[:3]],
            "heading_axis_and_periods": parameters[3:7],
            "radial_limits": [round(min(a for a, _ in radials) * scale, 6),
                              round(max(b for _, b in radials) * scale, 6)],
            "mesh_bounds": np.array((vertices.min(axis=0), vertices.max(axis=0))).round(6).tolist(),
            "motion_bounds": np.array(((spine - excursion).min(axis=0),
                                        (spine + excursion).max(axis=0))).round(6).tolist(),
            "block_bounds": [low.tolist(), high.tolist()],
            "voxel_count": len(local),
            "components": components(local),
            "isolated_cells_removed": removed,
        })
    # A one-block photosphere preserves the outside profile and the empty star interior.
    radius = star * scale
    extent = math.ceil(radius)
    for p in itertools.product(range(-extent, extent + 1), repeat=3):
        near = sum(max(abs(c) - .5, 0)**2 for c in p)
        far = sum((abs(c) + .5)**2 for c in p)
        if near < radius**2 < far:
            cells[p] = "STELLAR"

    # The east service tip is the outermost actual mesh at its first reinforced bay.
    # The fixed mounting face is one grid plane beyond the swept radius.
    east_tip = max(range(len(rings)), key=lambda i: ring_report[i]["radial_limits"][1])
    shell = math.ceil(ring_report[east_tip]["radial_limits"][1] + .5)
    service_bands = [band for material, band in rings[east_tip][1]
                     if material == "MAGNET" and band[2] > rings[east_tip][0][0]]
    a, b, inner, outer, _, _ = service_bands[0]
    z = round((inner + outer) * .5 * scale * math.sin((a + b) * .5))
    controller = (shell, 0, z)
    port_cells = [(shell, y, z + offset) for y, offset in ((0, -1), (0, 1), (-1, 0), (1, 0))]
    shell_cells = [(shell, y, z + offset) for y in (-1, 1) for offset in (-1, 1)]
    cabin = service_cabin(controller)
    port_cells.extend(point for point, material in cabin.items() if material == PORT)
    fixed = dict.fromkeys([*shell_cells, *port_cells, controller])
    fixed.update(cabin)
    if components(fixed) != [len(fixed)]:
        raise ValueError("Fixed control cabin contains disconnected parts")
    clearance = verify_fixed_clearance(fixed, scale, rings)
    if set(cabin) & cells.keys():
        raise ValueError("Fixed control cabin overlaps the static ring mesh")
    if any(p[0] > controller[0] for p in cabin):
        raise ValueError("Fixed control cabin extends beyond the original face of the controller")
    # Shell models cull common faces. Use ordinary terminal frames where a fixed block
    # directly borders a voxel of the resting ring. Keep the matching side in the same material.
    for x, y, pz in cabin:
        neighbors = ((x + dx, y + dy, plane + dz) for plane in (pz, 2 * z - pz)
                     for dx, dy, dz in ((-1, 0, 0), (1, 0, 0), (0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1)))
        if cabin[x, y, pz] in MATERIALS.values() and any(p in cells for p in neighbors):
            cabin[x, y, pz] = NODE
    if any(cabin.get((x, y, 2 * z - pz)) != material for (x, y, pz), material in cabin.items()):
        raise ValueError("Fixed control cabin is not mirrored across the controller")
    # Ports retain a face for interaction and automation; those cells can contain pipes.
    access = [(shell + 1, y, pz) for _, y, pz in port_cells]
    air_cells = [(shell + 1, 0, z)]
    low, high = coordinate_bounds([*cells, *shell_cells, *air_cells])
    shift = lambda p: tuple(int(c) for c in np.asarray(p) - low)
    palette = [block_tag(MATERIALS[s]) for s in SURFACES]
    blocks = [nbt.Compound({"pos": nbt.List[nbt.Int](shift(point)), "state": nbt.Int(PRIORITY[surface])})
              for point, surface in sorted(cells.items(), key=lambda row: (row[0][1], row[0][2], row[0][0]))]
    root = nbt.File({"DataVersion": nbt.Int(3465), "palette": nbt.List[nbt.Compound](palette),
                     "blocks": nbt.List[nbt.Compound](blocks), "entities": nbt.List[nbt.Compound](),
                     "size": nbt.List[nbt.Int](high - low + 1)}, gzipped=True)
    output.mkdir(parents=True, exist_ok=True)
    root.save(output / "star-lifter-mesh-import.nbt")
    np.savez_compressed(output / "star-lifter-ring-cells.npz", points=np.array(list(owners)),
                        masks=np.array(list(owners.values()), dtype=np.uint8))
    plan = [{"pos": shift(controller), "state": {"name": HOST, "properties": {"facing": "east"}}}]
    plan.extend({"pos": shift(p), "state": PORT} for p in port_cells if p not in cabin)
    plan.extend({"pos": shift(p), "state": NODE} for p in shell_cells)
    plan.extend({"pos": shift(p), "state": block} for p, block in sorted(cabin.items()))
    section = {
        "scale": scale, "star_radius": radius, "center": shift((0, 0, 0)),
        "controller": shift(controller), "interfaces": [shift(p) for p in port_cells],
        "air": [shift(p) for p in air_cells], "access": [shift(p) for p in access],
        "builder_plan": plan, "fixed_clearance": clearance, "rings": ring_report,
        "size": (high - low + 1).tolist(), "ring_overlap": dict(Counter(
            str(mask) for mask in owners.values() if mask.bit_count() > 1)),
    }
    (output / "star-lifter-geometry.json").write_text(json.dumps(section, indent=2) + "\n", encoding="utf-8")
    print(f"{len(cells)} mesh blocks, center {section['center']}, controller {section['controller']}, size {section['size']}")


def with_state(tag):
    return str(tag["Name"]), dict(tag.get("Properties", {}))


def binary_grid(aisles):
    rows = [row.encode("ascii") for aisle in aisles for row in aisle]
    dictionary = list(dict.fromkeys(rows))
    indexes = {row: i for i, row in enumerate(dictionary)}
    identifier = ID.encode("ascii")
    header = b"GTLASB3\x01" + struct.pack(">H", len(identifier)) + identifier
    grid = struct.pack(">BHHHH", 0, len(aisles), len(aisles[0]), len(rows[0]), len(dictionary))
    index_type = struct.Struct(">B" if len(dictionary) <= 256 else ">H")
    return header + grid + b"".join(dictionary) + b"".join(index_type.pack(indexes[row]) for row in rows)


def compile_resources(builder_export, geometry_path, assets):
    geometry = json.loads(geometry_path.read_text(encoding="utf-8"))
    doc = nbt.load(builder_export)
    width, height, depth = geometry["size"]
    aliases = dict(zip(MATERIALS.values(), "BCDEFGHI"))
    aliases.update({NODE: "J", PORT: "K", MANIFOLD: "L", INNER_LATCH: "M", HOST: "~", AIR: "."})
    cells = [[bytearray(b" " * width) for _ in range(height)] for _ in range(depth)]
    counts = Counter()
    palette = {}
    shell = {}
    expected = {tuple(entry["pos"]): entry["state"]["name"] if isinstance(entry["state"], dict) else entry["state"]
                for entry in geometry["builder_plan"]}
    low = (min(b["pos"][i] for b in doc["blocks"]) for i in range(3))
    if tuple(low) != (0, 0, 0):
        raise ValueError("Builder export must start at [0,0,0]")
    difference = {}
    for block in doc["blocks"]:
        x, y, z = map(int, block["pos"])
        ident, props = with_state(doc["palette"][int(block["state"])])
        symbol = aliases[ident]
        if (x, y, z) in expected and "B" <= symbol <= "H":
            symbol = symbol.lower()
        if cells[z][y][x] != 32 or symbol == ".":
            raise ValueError(f"Unexpected duplicate or air block at {(x, y, z)}")
        counts[ident] += 1
        cells[z][y][x] = ord(symbol)
        state = {"Name": ident}
        if props:
            state["Properties"] = props
        previous = palette.setdefault(symbol, state)
        if previous != state:
            raise ValueError(f"Multiple states for {symbol}: {previous} and {state}")
        if symbol in {"~", "J", "K", "L", "M"} or symbol.islower():
            difference[x, y, z] = ident
        else:
            shell[x, y, z] = ident, props
    if difference != expected or palette["~"].get("Properties", {}).get("facing") != "east":
        raise ValueError("Builder node does not match the planned controller, orientation and interface positions")
    source = nbt.load(geometry_path.with_name("star-lifter-mesh-import.nbt"))
    mesh_shell = {tuple(map(int, row["pos"])): with_state(source["palette"][int(row["state"])])
                  for row in source["blocks"]}
    if shell != mesh_shell:
        raise ValueError("Builder export changed blocks outside the service node")
    for x, y, z in geometry["access"]:
        if cells[z][y][x] != 32:
            raise ValueError(f"Hatch face was obstructed at {(x, y, z)}")
    # Builder omits empty cells by design. Only the listed machine access cells require air.
    for x, y, z in geometry["air"]:
        if cells[z][y][x] != 32:
            raise ValueError(f"Access path was obstructed at {(x, y, z)}")
        cells[z][y][x] = ord(".")
    aisles = [[row.decode("ascii") for row in aisle] for aisle in cells]
    data = {"format_version": 1, "id": ID, "source_sha256": hashlib.sha256(builder_export.read_bytes()).hexdigest(),
            "size": geometry["size"], "direction": ["front", "up", "right"], "front": "east",
            "center": geometry["center"], "controller": geometry["controller"], "interfaces": geometry["interfaces"],
            "air": geometry["air"], "interface_access": geometry["access"],
            "palette": palette | {".": {"Name": AIR}},
            "counts": dict(sorted(counts.items())), "aisles": aisles}
    json_path = assets / "gt_shanhai/multiblock/star_lifter.json"
    bin_path = assets / "gtladditions/structures/multiblock/star_lifter.bin"
    json_path.parent.mkdir(parents=True, exist_ok=True)
    bin_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    bin_path.write_bytes(binary_grid(aisles))
    print(f"{sum(counts.values())} blocks; JSON {json_path.stat().st_size} bytes; binary {bin_path.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="task", required=True)
    mesh = commands.add_parser("mesh")
    mesh.add_argument("source", type=Path)
    mesh.add_argument("output", type=Path)
    compile_gt = commands.add_parser("compile")
    compile_gt.add_argument("builder_export", type=Path)
    compile_gt.add_argument("geometry", type=Path)
    compile_gt.add_argument("assets", type=Path)
    args = parser.parse_args()
    if args.task == "mesh":
        mesh_stage(args.source, args.output)
    else:
        compile_resources(args.builder_export, args.geometry, args.assets)
