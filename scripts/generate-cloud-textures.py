# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0", "scipy==1.18.1"]
# ///
"""Generate procedural cloud layers with `uv run scripts/generate-cloud-textures.py`."""

from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates

TEXTURE_WIDTH = 2160
TEXTURE_HEIGHT = 640
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'


def smoothstep(low, high, values):
	amount = np.clip((values - low) / (high - low), 0, 1)
	return amount * amount * (3 - 2 * amount)


def noise(x, y, columns, rows, seed):
	"""Sample a smooth periodic field rather than stacking painted primitives."""
	grid = np.random.default_rng(seed).uniform(0, 1, (rows, columns)).astype(np.float32)
	coordinates = np.array([y * rows, x * columns], dtype=np.float32)
	return map_coordinates(grid, coordinates, order=3, mode='grid-wrap')


def cloud_texture(seed, distant):
	"""Stretch turbulent density along the wind and feather the sheet into clear sky."""
	x, y = np.meshgrid(
		np.arange(TEXTURE_WIDTH, dtype=np.float32) / TEXTURE_WIDTH,
		np.arange(TEXTURE_HEIGHT, dtype=np.float32) / TEXTURE_HEIGHT,
	)

	bend = noise(x, y, 7, 5, seed + 1) - 0.5
	curl = noise(x, y, 19, 10, seed + 2) - 0.5
	wind_x = x + 0.022 * bend
	wind_y = y + 0.10 * bend + 0.025 * curl + 0.05 * np.sin(2 * np.pi * x)
	strands = np.zeros_like(x)
	for octave, weight in enumerate((0.52, 0.27, 0.14, 0.07)):
		strands += weight * noise(wind_x, wind_y, 16 * 2 ** octave, 34 * 2 ** octave, seed + 10 + octave)

	patches = 0.25 + 0.75 * smoothstep(0.26, 0.76, noise(x, y, 8, 4, seed + 20))
	fine = noise(wind_x, wind_y, 210, 220, seed + 21)
	filaments = smoothstep(0.32, 0.80, strands + (fine - 0.5) * 0.24) ** 1.1
	veil = smoothstep(0.28, 0.85, noise(wind_x, wind_y, 13, 13, seed + 22))
	envelope = 1 - smoothstep(0.45, 0.97, y + 0.18 * bend)
	envelope *= 1 - smoothstep(0.80, 0.97, y)
	envelope *= smoothstep(-0.08, 0.32, y)
	if distant:
		density = (0.35 * filaments + 0.65 * veil) * patches * envelope
	else:
		density = (0.88 * filaments + 0.12 * veil) * patches * envelope

	alpha = np.clip(density * 1.6, 0, 1)
	shade = np.clip(1 - 0.065 * smoothstep(0.30, 0.90, veil), 0, 1)
	pixels = np.empty((TEXTURE_HEIGHT, TEXTURE_WIDTH, 4), dtype=np.uint8)
	pixels[:, :, :3] = (shade[:, :, None] * 255).astype(np.uint8)
	pixels[:, :, 3] = (alpha * 255).astype(np.uint8)
	return Image.fromarray(pixels)


def atmospheric_cloud_texture(seed):
	"""Build soft, broad cloud banks for fair-weather skies."""
	x, y = np.meshgrid(
		np.arange(TEXTURE_WIDTH, dtype=np.float32) / TEXTURE_WIDTH,
		np.arange(TEXTURE_HEIGHT, dtype=np.float32) / TEXTURE_HEIGHT,
	)

	macro = noise(x, y, 18, 6, seed + 30)
	middle = noise(x, y, 40, 12, seed + 31)
	detail = noise(x, y, 110, 32, seed + 32)
	bend = noise(x, y, 14, 7, seed + 33) - 0.5
	field = 0.64 * macro + 0.25 * middle + 0.11 * detail
	field += 0.05 * bend

	banks = smoothstep(0.42, 0.68, field)
	envelope = smoothstep(0.04, 0.16, y)
	envelope *= 1 - smoothstep(0.67, 0.92, y)
	density = banks * envelope
	density *= 0.72 + 0.28 * smoothstep(0.30, 0.78, detail)

	alpha = np.clip(density * 0.88, 0, 0.82)
	underside = smoothstep(0.28, 0.88, y)
	shade = np.clip(0.985 - 0.16 * underside - 0.025 * smoothstep(0.25, 0.75, middle), 0, 1)
	pixels = np.empty((TEXTURE_HEIGHT, TEXTURE_WIDTH, 4), dtype=np.uint8)
	pixels[:, :, 0] = (shade * 255).astype(np.uint8)
	pixels[:, :, 1] = np.clip(shade * 255 + 2, 0, 255).astype(np.uint8)
	pixels[:, :, 2] = np.clip(shade * 255 + 12, 0, 255).astype(np.uint8)
	pixels[:, :, 3] = (alpha * 255).astype(np.uint8)
	return Image.fromarray(pixels, 'RGBA')


def main():
	OUTPUT.mkdir(parents=True, exist_ok=True)
	for name, seed, distant in (('cloud_sheet_far', 823, True), ('cloud_sheet_near', 1759, False)):
		image = cloud_texture(seed, distant)
		path = OUTPUT / f'{name}.png'
		image.save(path, optimize=True)
		print(f'{path.relative_to(OUTPUT.parents[4])}: {image.width}x{image.height}, {path.stat().st_size} bytes')

	image = atmospheric_cloud_texture(2917)
	path = OUTPUT / 'cloud_atmosphere.png'
	image.save(path, optimize=True)
	print(f'{path.relative_to(OUTPUT.parents[4])}: {image.width}x{image.height}, {path.stat().st_size} bytes')



if __name__ == '__main__':
	main()
