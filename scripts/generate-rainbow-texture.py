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


def rainbow_texture():
	"""Render a primary and secondary rainbow arc with realistic spectral dispersion and inner glow."""
	x = np.arange(TEXTURE_WIDTH, dtype=np.float32)
	y = np.arange(TEXTURE_HEIGHT, dtype=np.float32)
	xx, yy = np.meshgrid(x, y)

	# Center of the circular rainbow arc anchored at the bottom center of the texture.
	xc = TEXTURE_WIDTH / 2.0
	yc = float(TEXTURE_HEIGHT)
	r = np.sqrt((xx - xc) ** 2 + (yy - yc) ** 2)

	# Primary bow radius: inner edge 905 px, outer edge 1015 px (thickness 110 px).
	r_in = 905.0
	r_out = 1015.0
	u_primary = (r - r_in) / (r_out - r_in)

	# Spectral color stops from inner violet to outer red.
	stops_u = np.array([0.0, 0.16, 0.33, 0.50, 0.68, 0.85, 1.0], dtype=np.float32)
	stops_rgb = np.array([
		[150, 60, 230],   # Violet
		[40, 100, 245],   # Blue
		[0, 210, 230],    # Cyan
		[50, 220, 80],    # Green
		[255, 230, 30],   # Yellow
		[255, 130, 20],   # Orange
		[255, 40, 40]     # Red
	], dtype=np.float32)

	rgb_primary = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	for i in range(3):
		rgb_primary[..., i] = np.interp(u_primary, stops_u, stops_rgb[:, i])

	clamped_u = np.clip(u_primary, 0.0, 1.0)
	alpha_primary = np.maximum(np.sin(clamped_u * np.pi), 0.0) ** 1.1 * 0.70

	# Smooth inner glow representing forward-scattered raindrop light inside the primary bow.
	glow_dist = np.clip((r_in + 30.0 - r) / 320.0, 0.0, 1.0)
	inner_glow = np.maximum(np.sin(glow_dist * np.pi * 0.5), 0.0) ** 2.0 * 0.10 * (r <= r_in + 30.0)

	# Secondary bow with reversed spectral colors (red inside, violet outside) and lower intensity.
	r_sec_in = 1110.0
	r_sec_out = 1195.0
	u_sec = (r - r_sec_in) / (r_sec_out - r_sec_in)
	clamped_u_sec = np.clip(u_sec, 0.0, 1.0)
	alpha_sec = np.maximum(np.sin(clamped_u_sec * np.pi), 0.0) ** 1.3 * 0.16

	rgb_sec = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	for i in range(3):
		rgb_sec[..., i] = np.interp(1.0 - u_sec, stops_u, stops_rgb[:, i])

	mask_primary = (u_primary >= 0.0) & (u_primary <= 1.0)
	mask_sec = (u_sec >= 0.0) & (u_sec <= 1.0)

	final_rgb = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	final_alpha = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH), dtype=np.float32)

	final_rgb += rgb_primary * (alpha_primary * mask_primary)[..., None]
	final_alpha += alpha_primary * mask_primary

	final_rgb += rgb_sec * (alpha_sec * mask_sec)[..., None]
	final_alpha += alpha_sec * mask_sec

	glow_rgb = np.array([255, 252, 245], dtype=np.float32)
	final_rgb += glow_rgb * inner_glow[..., None]
	final_alpha += inner_glow

	has_alpha = final_alpha > 1e-4
	final_rgb[has_alpha] /= final_alpha[has_alpha, None]

	# Feather the base near the bottom so it fades softly into the horizon.
	fade_bottom = np.clip((TEXTURE_HEIGHT - yy) / 180.0, 0.0, 1.0)
	final_alpha *= fade_bottom

	final_alpha = np.clip(final_alpha, 0.0, 1.0)
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
