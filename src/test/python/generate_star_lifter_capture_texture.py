"""Bake the Star Lifter's blue-white plasma palette into a looping block atlas strip."""

import argparse
from pathlib import Path
import re

import numpy as np
from PIL import Image


FRAME_SIZE = 16
FRAME_COUNT = 64


def fract(values):
    return values - np.floor(values)


def hash_points(points):
    p = fract(points * np.array([.1031, .11369, .13787]))
    p += (p * (p[..., [1, 0, 2]] + 19.19)).sum(axis=-1, keepdims=True)
    return fract((p[..., 0] + p[..., 1]) * p[..., 2])


def noise(points):
    cell = np.floor(points)
    blend = fract(points)
    blend = blend * blend * (3 - 2 * blend)
    values = np.zeros(points.shape[:-1])
    for corner in np.ndindex((2, 2, 2)):
        values += hash_points(cell + corner) * np.where(corner, blend, 1 - blend).prod(axis=-1)
    return values


def smooth(start, end, values):
    t = np.clip((values - start) / (end - start), 0, 1)
    return (t * t * (3 - 2 * t))[..., None]


def capture_frames(shader):
    source = shader.read_text(encoding="utf-8").split("vec3 ionized =", 1)[1].split("float edge", 1)[0]
    dark, blue, white = (np.fromstring(color, sep=",") for color in re.findall(r"vec3\(([^)]*)\)", source))
    uv = (np.arange(FRAME_SIZE) + .5) * 2 * np.pi / FRAME_SIZE
    longitude, latitude = np.meshgrid(uv, uv)
    def field(u, v):
        # Torus coordinates tile in both texture axes.
        section = 1 + .34 * np.cos(v)
        return .28 * np.stack((section * np.cos(u), section * np.sin(u), .51 * np.sin(v)), axis=-1) + [.72, .63, .37]

    grain_field = field(longitude, latitude)

    def frame(phase):
        u = longitude + .29 * np.sin(phase + latitude)
        v = latitude + .23 * np.sin(phase - longitude)
        flow = field(u, v)
        drift = np.array([.39 * np.sin(phase), .45 * np.cos(phase), .18 * np.sin(phase * 2)])
        currents = noise(flow * 7 + .65 * drift)
        filaments = 1 - abs(2 * noise(flow * 19 + currents[..., None] * 1.35 + .83 * drift) - 1)
        # The smallest grains scintillate in place, keeping detail coherent at 16 pixels.
        granules = noise(grain_field * 57 + filaments[..., None] * .29 - .24 * drift)
        heat = .51 * filaments + .29 * granules + .20 * currents
        plasma = dark + (blue - dark) * smooth(.23, .63, heat)
        plasma += (white - plasma) * smooth(.52, .84, heat)
        return np.uint8(np.rint(plasma * 255))

    frames = [frame(index * 2 * np.pi / FRAME_COUNT) for index in range(FRAME_COUNT)]
    if not np.array_equal(frames[0], frame(2 * np.pi)):
        raise ValueError("Plasma phase does not close after one cycle")
    samples = np.asarray(frames, dtype=np.float32)
    delta = np.abs(samples - np.roll(samples, -1, axis=0)).mean(axis=(1, 2, 3))
    if delta[-1] > np.percentile(delta[:-1], 95) or delta.max() > 12 or delta.min() < .1:
        raise ValueError(f"Unstable frame step: {delta.min():.2f}–{delta.max():.2f}; wrap {delta[-1]:.2f}")
    print(f"{len(frames)} frames; mean RGB step {delta.mean():.2f}/255; wrap {delta[-1]:.2f}/255")
    return frames


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shader", type=Path)
    parser.add_argument("texture", type=Path)
    args = parser.parse_args()
    Image.fromarray(np.concatenate(capture_frames(args.shader), axis=0)).save(args.texture)
