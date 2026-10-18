# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.4", "pillow==13.0.0"]
# ///
"""Project NASA's lunar albedo map with `uv run scripts/generate-moon-texture.py`."""

import hashlib
from pathlib import Path

import numpy as np
from PIL import Image

# Pristine NASA SVS CGI Moon Kit source, downloaded without modification:
# https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_2k.jpg
# Source and credit: https://svs.gsfc.nasa.gov/4720/ — NASA's Scientific Visualization Studio.
# NASA SVS content is public domain unless otherwise noted: https://svs.gsfc.nasa.gov/help/
SOURCE_SHA256 = 'f7130a1822681fa7512d7dcfd40db8c10b9ba4f06777910348698260ed7a2170'
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'scripts/assets/moon/lroc_color_2k.jpg'
OUTPUT = ROOT / 'app/src/main/res/drawable-nodpi/moon_surface.png'
TEXTURE_SIZE = 512
SUPERSAMPLING = 2


def moon_texture():
	"""Orthographically project the near side, north up, without baked phase lighting."""
	if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != SOURCE_SHA256:
		raise ValueError('The original NASA lunar map has changed; check its provenance before rebuilding.')

	with Image.open(SOURCE) as image:
		source = np.asarray(image.convert('RGB'), dtype=np.float64)

	size = TEXTURE_SIZE * SUPERSAMPLING
	axis = (np.arange(size, dtype=np.float64) + 0.5) * 2 / size - 1
	xx, yy = np.meshgrid(axis, axis)
	distance_squared = xx * xx + yy * yy
	visible = distance_squared < 1
	zz = np.sqrt(np.maximum(0, 1 - distance_squared))
	longitude = np.arctan2(xx, zz)
	latitude = np.arcsin(np.clip(-yy, -1, 1))
	# The map's central meridian is 0 degrees; Mare Crisium stays on the right.
	u = (longitude / (2 * np.pi) + 0.5) * source.shape[1] - 0.5
	v = (0.5 - latitude / np.pi) * source.shape[0] - 0.5
	x0 = np.floor(u).astype(np.int32) % source.shape[1]
	y0 = np.clip(np.floor(v).astype(np.int32), 0, source.shape[0] - 1)
	x1 = (x0 + 1) % source.shape[1]
	y1 = np.minimum(y0 + 1, source.shape[0] - 1)
	fx = (u - np.floor(u))[..., None]
	fy = (v - np.floor(v))[..., None]
	top = source[y0, x0] * (1 - fx) + source[y0, x1] * fx
	bottom = source[y1, x0] * (1 - fx) + source[y1, x1] * fx
	rgb = np.rint(top * (1 - fy) + bottom * fy).astype(np.uint8)
	rgba = np.dstack((rgb, visible.astype(np.uint8) * 255))
	return Image.fromarray(rgba).resize((TEXTURE_SIZE, TEXTURE_SIZE), Image.Resampling.LANCZOS)


def main():
	OUTPUT.parent.mkdir(parents=True, exist_ok=True)
	image = moon_texture()
	image.save(OUTPUT, optimize=True)
	print(f'{OUTPUT.name}: {image.width}x{image.height}, {OUTPUT.stat().st_size} bytes')
	print(f'Source SHA-256: {SOURCE_SHA256}')
	print(f'Output SHA-256: {hashlib.sha256(OUTPUT.read_bytes()).hexdigest()}')


if __name__ == '__main__':
	main()
