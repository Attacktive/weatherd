# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.4", "pillow==13.0.0"]
# ///
"""Generate original sun artwork with `uv run scripts/generate-starburst-sun.py`."""

from pathlib import Path

import numpy as np
from PIL import Image

TEXTURE_SIZE = 512
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi/sun_starburst.png'


def starburst_texture():
	"""
	Compose a clipped-white core, six soft tapered rays, and a feathered bloom.

	All pixels are generated analytically; no supplied image or third-party artwork is sampled.
	The core is centered, with its nearly opaque radius approximately 0.1 of the half-width.
	Lens reflections deliberately belong to the renderer, not this texture.
	"""
	axis = (np.arange(TEXTURE_SIZE, dtype=np.float64) + 0.5 - TEXTURE_SIZE / 2) / (TEXTURE_SIZE / 2)
	xx, yy = np.meshgrid(axis, axis)
	radius = np.hypot(xx, yy)
	energy = 8.0 * np.exp(-(radius / 0.105) ** 4)
	energy += 0.38 * np.exp(-(radius / 0.28) ** 2)
	energy += 0.10 * np.exp(-(radius / 0.58) ** 2)
	for angle, reach in ((-92, 0.65), (-31, 0.54), (13, 0.60), (72, 0.68), (141, 0.56), (196, 0.62)):
		radians = np.deg2rad(angle)
		along = xx * np.cos(radians) + yy * np.sin(radians)
		across = -xx * np.sin(radians) + yy * np.cos(radians)
		width = np.maximum(0.018, 0.15 * (1.0 - np.maximum(along, 0.0) / reach))
		energy += 0.90 * np.exp(-0.5 * (across / width) ** 2 - (along / reach) ** 2) * np.clip(along / 0.08, 0.0, 1.0)

	remaining = np.clip((1.0 - radius) / 0.22, 0.0, 1.0)
	edge = remaining * remaining * (3.0 - 2.0 * remaining)
	alpha = (1.0 - np.exp(-energy)) * edge
	image = Image.new('RGBA', (TEXTURE_SIZE, TEXTURE_SIZE), (255, 255, 255, 0))
	image.putalpha(Image.fromarray(np.rint(alpha * 255.0).astype(np.uint8)))
	return image


def main():
	OUTPUT.parent.mkdir(parents=True, exist_ok=True)
	image = starburst_texture()
	image.save(OUTPUT, optimize=True)
	print(f'{OUTPUT.name}: {image.width}x{image.height}, {OUTPUT.stat().st_size} bytes')


if __name__ == '__main__':
	main()
