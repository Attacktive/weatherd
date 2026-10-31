# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.4", "pillow==13.0.0"]
# ///
"""Generate original sun artwork with `uv run scripts/generate-starburst-sun.py`."""

from pathlib import Path

import numpy as np
from PIL import Image

TEXTURE_SIZE = 512
LIGHT_EXPOSURE = 1.3
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi/sun_starburst.png'


def starburst_texture():
	"""Compose white light, broad bloom, major rays, and short fine streaks."""
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
	alpha = (1.0 - np.exp(-energy * LIGHT_EXPOSURE)) * edge
	# Preserve the approved source's quantized core/glow before adding ray detail.
	alpha = np.rint(alpha * 255.0) / 255.0
	ray_energy = np.zeros_like(radius)
	for direction, reach, breadth, strength in ((-1.7, 167, 13, 1.3), (-0.7, 144, 18, 1.9), (0.16, 156, 14, 0.9), (1.28, 192, 20, 1.5), (2.32, 155, 23, 1.7), (-2.94, 167, 12, 0.8)):
		for offset, width_scale, weight in ((-0.085, 0.55, 0.28), (0.0, 1.0, 0.62), (0.07, 0.42, 0.32)):
			angle = direction + offset
			along = xx * np.cos(angle) + yy * np.sin(angle)
			across = -xx * np.sin(angle) + yy * np.cos(angle)
			width = breadth * width_scale * (1 - 0.35 * np.clip(along / reach, 0, 1))
			ray_energy += strength * weight * np.exp(-(across / width) ** 2 - (along / reach) ** 2) * np.clip(along / 24, 0, 1)

	# Fine rays supplement the major rays; they must not replace their silhouette.
	for index in range(24):
		direction = index * 2 * np.pi / 24 + 0.055 * np.sin(index * 2.3)
		reach = 74 + 28 * (0.5 + 0.5 * np.sin(index * 1.7))
		breadth = 2.8 + 2.2 * (0.5 + 0.5 * np.sin(index * 2.1))
		strength = 0.8 + 0.9 * (0.5 + 0.5 * np.cos(index * 1.3))
		along = xx * np.cos(direction) + yy * np.sin(direction)
		across = -xx * np.sin(direction) + yy * np.cos(direction)
		width = breadth * (1 - 0.3 * np.clip(along / reach, 0, 1))
		ray_energy += strength * np.exp(-(across / width) ** 2 - (along / reach) ** 2) * np.clip(along / 24, 0, 1)

	alpha = 1 - (1 - alpha) * np.exp(-ray_energy * edge * LIGHT_EXPOSURE)
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
