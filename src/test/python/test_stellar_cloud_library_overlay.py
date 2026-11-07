"""山海：星律云端样板库主控贴图资源回归。"""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageChops


ROOT = Path(__file__).resolve().parents[3]
OVERLAY = "block/machine/part/cloud_pattern_library"
ASSETS = ROOT / "src/main/resources/assets/gt_shanhai/textures" / OVERLAY
MACHINE = ROOT / ("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/"
                  "StellarCloudLibraryMachine.java")
FACES = ("front", "back", "side", "top", "bottom")


def frames(path):
    with Image.open(path) as atlas:
        if atlas.size != (16, 256) or atlas.mode != "RGBA":
            raise ValueError(f"{path.name}: expected a 16x256 RGBA atlas")
        return [atlas.crop((0, y, 16, y + 16)) for y in range(0, 256, 16)]


class StellarCloudLibraryOverlayTest(unittest.TestCase):
    def test_controller_uses_its_own_overlay(self):
        source = MACHINE.read_text(encoding="utf-8")
        self.assertTrue(f'new ResourceLocation("gt_shanhai", "{OVERLAY}")' in source,
                        "cloud library controller must reference its own overlay")

    def test_face_atlases_and_emissive_layers(self):
        face_images = set()
        for face in FACES:
            with self.subTest(face=face):
                normal = ASSETS / f"overlay_{face}.png"
                emissive = ASSETS / f"overlay_{face}_emissive.png"
                face_images.add(normal.read_bytes())
                for path in (normal, emissive):
                    metadata = json.loads(path.with_suffix(".png.mcmeta").read_text(encoding="utf-8"))
                    self.assertEqual({"frametime": 2, "interpolate": False}, metadata["animation"])

                normal_frames = frames(normal)
                emissive_frames = frames(emissive)
                self.assertEqual(16, len({frame.tobytes() for frame in normal_frames}))
                self.assertEqual(16, len({frame.tobytes() for frame in emissive_frames}))
                for base, glow in zip(normal_frames, emissive_frames):
                    self.assertEqual((255, 255), base.getchannel("A").getextrema())
                    self.assertEqual((0, 255), glow.getchannel("A").getextrema())
                    self.assertTrue(all(base.getpixel((x, y)) == glow.getpixel((x, y))
                                        for y in range(16) for x in range(16)
                                        if glow.getpixel((x, y))[3] == 255))

                for sequence in (normal_frames, emissive_frames):
                    changes = [ImageChops.difference(a.convert("RGB"), b.convert("RGB")).convert("L")
                               .point(lambda pixel: int(pixel > 0)).histogram()[1]
                               for a, b in zip(sequence, sequence[1:] + sequence[:1])]
                    self.assertTrue(all(changes))
                    self.assertLessEqual(changes[-1], max(changes[:-1]))
        self.assertEqual(len(FACES), len(face_images))

    def test_generator_reproduces_the_checked_in_atlases(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(__file__).with_name("generate_stellar_cloud_library_overlay.py")
            subprocess.run([sys.executable, str(script), directory], check=True)
            output = Path(directory)
            self.assertEqual(20, len(list(ASSETS.iterdir())))
            self.assertEqual({path.name for path in ASSETS.iterdir()},
                             {path.name for path in output.iterdir()})
            for path in ASSETS.iterdir():
                generated = output / path.name
                if path.suffix == ".png":
                    self.assertEqual([frame.tobytes() for frame in frames(path)],
                                     [frame.tobytes() for frame in frames(generated)])
                else:
                    self.assertEqual(json.loads(path.read_text(encoding="utf-8")),
                                     json.loads(generated.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
