# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Generate original rainbow texture with `uv run scripts/generate-rainbow-texture.py`."""

# Generates an optical spectral rainbow arc with atmospheric dispersion and feathered boundaries.
# No photographs or third-party artwork are sampled.

from pathlib import Path

import numpy as np
from PIL import Image

TEXTURE_WIDTH = 2160
TEXTURE_HEIGHT = 1440
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'

PRIMARY_R_IN = 905.0
PRIMARY_R_OUT = 1015.0

SECONDARY_R_IN = 1110.0
SECONDARY_R_OUT = 1195.0

STOPS_U = np.array([0.0, 0.16, 0.33, 0.50, 0.68, 0.85, 1.0], dtype=np.float32)
STOPS_RGB = np.array([
	[150, 60, 230],   # Violet
	[40, 100, 245],   # Blue
	[0, 210, 230],    # Cyan
	[50, 220, 80],    # Green
	[255, 230, 30],   # Yellow
	[255, 130, 20],   # Orange
	[255, 40, 40]     # Red
], dtype=np.float32)


def primary_bow(r):
	"""Compute spectral color, alpha envelope, and valid mask for the primary bow."""
	u = (r - PRIMARY_R_IN) / (PRIMARY_R_OUT - PRIMARY_R_IN)
	rgb = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	for i in range(3):
		rgb[..., i] = np.interp(u, STOPS_U, STOPS_RGB[:, i])

	clamped_u = np.clip(u, 0.0, 1.0)
	alpha = np.maximum(np.sin(clamped_u * np.pi), 0.0) ** 1.1 * 0.70
	mask = (u >= 0.0) & (u <= 1.0)
	return rgb, alpha, mask


def secondary_bow(r):
	"""Compute reversed spectral color, alpha envelope, and valid mask for the secondary bow."""
	u = (r - SECONDARY_R_IN) / (SECONDARY_R_OUT - SECONDARY_R_IN)
	rgb = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	for i in range(3):
		rgb[..., i] = np.interp(1.0 - u, STOPS_U, STOPS_RGB[:, i])

	clamped_u = np.clip(u, 0.0, 1.0)
	alpha = np.maximum(np.sin(clamped_u * np.pi), 0.0) ** 1.3 * 0.16
	mask = (u >= 0.0) & (u <= 1.0)
	return rgb, alpha, mask


def inner_glow(r):
	"""Forward-scattered raindrop light concentrated beneath the primary bow."""
	glow_dist = np.clip((PRIMARY_R_IN + 30.0 - r) / 320.0, 0.0, 1.0)
	alpha = np.maximum(np.sin(glow_dist * np.pi * 0.5), 0.0) ** 2.0 * 0.10 * (r <= PRIMARY_R_IN + 30.0)
	rgb = np.array([255, 252, 245], dtype=np.float32)
	return rgb, alpha


def rainbow_texture():
	"""Render a primary and secondary rainbow arc with realistic spectral dispersion and inner glow."""
	x = np.arange(TEXTURE_WIDTH, dtype=np.float32)
	y = np.arange(TEXTURE_HEIGHT, dtype=np.float32)
	xx, yy = np.meshgrid(x, y)

	# Center of the circular rainbow arc anchored at the bottom center of the texture.
	xc = TEXTURE_WIDTH / 2.0
	yc = float(TEXTURE_HEIGHT)
	r = np.sqrt((xx - xc) ** 2 + (yy - yc) ** 2)

	rgb_primary, alpha_primary, mask_primary = primary_bow(r)
	rgb_sec, alpha_sec, mask_sec = secondary_bow(r)
	glow_rgb, alpha_glow = inner_glow(r)

	final_rgb = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	final_rgb += rgb_primary * (alpha_primary * mask_primary)[..., None]
	final_rgb += rgb_sec * (alpha_sec * mask_sec)[..., None]
	final_rgb += glow_rgb * alpha_glow[..., None]

	final_alpha = alpha_primary * mask_primary + alpha_sec * mask_sec + alpha_glow
	has_alpha = final_alpha > 1e-4
	final_rgb[has_alpha] /= final_alpha[has_alpha, None]

	# Feather the base near the bottom so it fades softly into the horizon.
	fade_bottom = np.clip((TEXTURE_HEIGHT - yy) / 180.0, 0.0, 1.0)
	final_alpha = np.clip(final_alpha * fade_bottom, 0.0, 1.0)

	final_rgba = np.empty((TEXTURE_HEIGHT, TEXTURE_WIDTH, 4), dtype=np.uint8)
	final_rgba[..., :3] = np.clip(final_rgb, 0, 255).astype(np.uint8)
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
