# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Generate the original sun-halo texture with `uv run scripts/generate-rainbow-texture.py`."""

# Generates a chromatic optical halo with atmospheric dispersion and feathered boundaries.
# No photographs or third-party artwork are sampled.

from pathlib import Path

import numpy as np
from PIL import Image

TEXTURE_SIZE = 1536
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'

HALO_RADIUS = TEXTURE_SIZE * 0.34
HALO_SIGMA = TEXTURE_SIZE * 0.042
SPECTRAL_PEAK_ALPHA = 0.225
VEIL_PEAK_ALPHA = 0.025

ATMOSPHERE_RGB = np.array([198, 211, 224], dtype=np.float32)
SPECTRAL_WEIGHT = 0.80
STOPS_U = np.array([0.0, 0.17, 0.33, 0.50, 0.67, 0.84, 1.0], dtype=np.float32)
STOPS_RGB = np.array([
	[244, 118, 122],
	[246, 166, 118],
	[242, 218, 142],
	[160, 222, 174],
	[116, 210, 222],
	[110, 158, 236],
	[180, 142, 224],
], dtype=np.float32)


def spectral_color(radius):
	"""Blend a continuous warm-to-cool spectrum into atmospheric light."""
	u = np.clip(0.5 + (radius - HALO_RADIUS) / (HALO_SIGMA * 4.0), 0.0, 1.0)
	rgb = np.zeros((TEXTURE_SIZE, TEXTURE_SIZE, 3), dtype=np.float32)
	for channel in range(3):
		rgb[..., channel] = np.interp(u, STOPS_U, STOPS_RGB[:, channel])

	return ATMOSPHERE_RGB + (rgb - ATMOSPHERE_RGB) * SPECTRAL_WEIGHT


def halo_alpha(radius):
	"""Layer a broad spectral ring over a fainter atmospheric veil."""
	distance = (radius - HALO_RADIUS) / HALO_SIGMA
	spectral = np.exp(-0.5 * distance * distance) * SPECTRAL_PEAK_ALPHA
	veil = np.exp(-0.5 * (distance / 1.8) ** 2) * VEIL_PEAK_ALPHA
	return spectral + veil


def atmospheric_veil(theta):
	"""Make the lower-left arc gently stronger where the references reveal it through haze."""
	veil = 0.86 + 0.08 * np.cos(theta - np.pi * 0.75) + 0.025 * np.sin(theta * 3.0 + 0.4)
	return np.clip(veil, 0.72, 0.96)


def rainbow_texture():
	"""Render a broad, translucent chromatic halo centered in a square texture."""
	axis = np.arange(TEXTURE_SIZE, dtype=np.float32)
	xx, yy = np.meshgrid(axis, axis)
	center = TEXTURE_SIZE / 2.0
	radius = np.sqrt((xx - center) ** 2 + (yy - center) ** 2)
	theta = np.arctan2(yy - center, xx - center)
	final_alpha = np.clip(halo_alpha(radius) * atmospheric_veil(theta), 0.0, 1.0)
	final_alpha[final_alpha < 1.0 / 255.0] = 0.0
	final_rgb = spectral_color(radius)
	final_rgba = np.zeros((TEXTURE_SIZE, TEXTURE_SIZE, 4), dtype=np.uint8)
	has_alpha = final_alpha > 0.0
	final_rgba[has_alpha, :3] = np.clip(final_rgb[has_alpha], 0, 255).astype(np.uint8)
	final_rgba[..., 3] = (final_alpha * 255).astype(np.uint8)
	return Image.fromarray(final_rgba)


def main():
	OUTPUT.mkdir(parents=True, exist_ok=True)
	image = rainbow_texture()
	path = OUTPUT / 'rainbow.png'
	image.save(path, optimize=True)
	print(f'{path.relative_to(OUTPUT.parents[3])}: {image.width}x{image.height}, {path.stat().st_size} bytes')


if __name__ == '__main__':
	main()
