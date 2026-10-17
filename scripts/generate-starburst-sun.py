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
	"""Compose overlapping white light, broad bloom, and faint uneven streaks."""
	# All pixels are generated analytically; no supplied image or third-party artwork is sampled.
	# Reference-space half-width is 400 units; the luminous core spans approximately 40 units.
	# Lens reflections deliberately belong to the renderer, not this texture.
	axis = (np.arange(TEXTURE_SIZE, dtype=np.float64) + 0.5 - TEXTURE_SIZE / 2) * 800 / TEXTURE_SIZE
	xx, yy = np.meshgrid(axis, axis)
	radius = np.hypot(xx, yy)
	energy = 4.8 * np.exp(-(radius / 48) ** 2)
	for offset_x, offset_y, spread, strength in ((-20, -14, 38, 0.65), (22, 8, 42, 0.7), (-6, 30, 36, 0.4)):
		energy += strength * np.exp(-((xx - offset_x) ** 2 + (yy - offset_y) ** 2) / spread ** 2)

	energy += 0.8 * np.exp(-(radius / 94) ** 1.65)
	energy += 0.22 * np.exp(-(radius / 174) ** 1.6)
	for direction, reach, breadth, strength in ((-1.7, 167, 13, 0.28), (-0.7, 114, 18, 0.12), (0.16, 156, 14, 0.15), (1.28, 212, 20, 0.20), (2.32, 125, 23, 0.12), (-2.94, 167, 12, 0.16)):
		along = xx * np.cos(direction) + yy * np.sin(direction)
		across = -xx * np.sin(direction) + yy * np.cos(direction)
		energy += strength * np.exp(-(across / breadth) ** 2 - (along / reach) ** 2) * np.clip(along / 8, 0.0, 1.0)

	remaining = np.clip((400 - radius) / 80, 0.0, 1.0)
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
