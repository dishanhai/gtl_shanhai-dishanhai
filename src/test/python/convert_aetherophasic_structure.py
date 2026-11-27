"""山海：将以太相引擎的 Java 结构 NBT 无损转为调色板／切片 JSON。

需要 nbtlib；运行：python convert_aetherophasic_structure.py 输入.nbt 输出.json
切片顺序为 [z][y][x]，空格表示 NBT 中未记录的位置，不裁剪空白边界。
"""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import nbtlib


def convert(source, destination):
    root = nbtlib.load(source).unpack()
    expected_keys = {"BuilderMCP", "DataVersion", "blocks", "entities", "palette", "size"}
    if set(root) != expected_keys or root["entities"]:
        raise ValueError("Unsupported root fields or entities; conversion would lose data")
    size = root["size"]
    palette = root["palette"]
    if len(size) != 3 or min(size) <= 0 or not 0 < len(palette) <= 26:
        raise ValueError("Invalid structure size or palette")
    controller_state = next(i for i, state in enumerate(palette)
                            if state["Name"] == "gtlcore:spacetimebendingcore")
    hatch_state = next(i for i, state in enumerate(palette)
                       if state["Name"] == "gtlcore:opv_hermetic_casing")
    width, height, depth = size
    cells = [[bytearray(b" " * width) for _ in range(height)] for _ in range(depth)]
    controllers = []
    counts = Counter()
    for block in root["blocks"]:
        if set(block) != {"pos", "state"}:
            raise ValueError("Block entity or extra block fields would be lost")
        x, y, z = block["pos"]
        state = block["state"]
        if not (0 <= x < width and 0 <= y < height and 0 <= z < depth
                and 0 <= state < len(palette)):
            raise ValueError(f"Out-of-range block: {block}")
        if cells[z][y][x] != ord(" "):
            raise ValueError(f"Duplicate block: {block['pos']}")
        cells[z][y][x] = ord("A") + state
        counts[state] += 1
        if state == controller_state:
            controllers.append(block["pos"])
    if len(controllers) != 1 or counts[hatch_state] != 6:
        raise ValueError("Expected one controller marker and six hatch markers")
    result = {
        "format_version": 1,
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "DataVersion": root["DataVersion"],
        "BuilderMCP": root["BuilderMCP"],
        "size": size,
        "palette": palette,
        "controller_symbol": chr(ord("A") + controller_state),
        "controller": controllers[0],
        "hatch_symbol": chr(ord("A") + hatch_state),
        "aisles": [[row.decode("ascii") for row in aisle] for aisle in cells],
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Converted {len(root['blocks'])} blocks, {len(palette)} states, size {size}")
    print(f"Controller {controllers[0]}, source SHA-256 {result['source_sha256']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    convert(args.source, args.destination)
