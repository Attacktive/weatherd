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

PRIMARY_RADIUS = 2200.0
PRIMARY_BAND_WIDTH = 64.0
PRIMARY_PEAK_ALPHA = 0.27
PRIMARY_APEX_Y = 475.0
RAINBOW_CENTER_Y = PRIMARY_RADIUS + PRIMARY_APEX_Y

SECONDARY_RADIUS = 2400.0
SECONDARY_BAND_WIDTH = 92.0
SECONDARY_PEAK_ALPHA = 0.045

ATMOSPHERE_RGB = np.array([193, 207, 218], dtype=np.float32)
SPECTRAL_WEIGHT = 0.70
STOPS_U = np.array([0.0, 0.16, 0.33, 0.50, 0.68, 0.85, 1.0], dtype=np.float32)
STOPS_RGB = np.array([
	[150, 60, 230],
	[40, 100, 245],
	[0, 210, 230],
	[50, 220, 80],
	[255, 230, 30],
	[255, 130, 20],
	[255, 40, 40],
], dtype=np.float32)


def spectral_color(r, radius, band_width, reverse=False):
	"""Mix spectral color into cool cloud light."""
	u = (r - (radius - band_width * 2.0)) / (band_width * 4.0)
	if reverse:
		u = 1.0 - u

	rgb = np.zeros((TEXTURE_HEIGHT, TEXTURE_WIDTH, 3), dtype=np.float32)
	for i in range(3):
		rgb[..., i] = np.interp(u, STOPS_U, STOPS_RGB[:, i])

	return ATMOSPHERE_RGB + (rgb - ATMOSPHERE_RGB) * SPECTRAL_WEIGHT


def bow_alpha(r, radius, band_width, peak_alpha):
	"""Return a soft radial band without hard edges."""
	distance = (r - radius) / band_width
	return np.exp(-0.5 * distance * distance) * peak_alpha


def atmospheric_veil(theta):
	"""Vary the bow gently along its arc as cloud and haze would."""
	veil = 0.76 + 0.11 * np.sin(theta * 4.7 + 0.8) + 0.06 * np.sin(theta * 13.1 - 0.4)
	return np.clip(veil, 0.58, 0.93)


def rainbow_texture():
	"""Render a subdued rainbow emerging from cloud-brightened haze."""
	x = np.arange(TEXTURE_WIDTH, dtype=np.float32)
	y = np.arange(TEXTURE_HEIGHT, dtype=np.float32)
	xx, yy = np.meshgrid(x, y)
	xc = TEXTURE_WIDTH / 2.0
	yc = RAINBOW_CENTER_Y
	r = np.sqrt((xx - xc) ** 2 + (yy - yc) ** 2)
	theta = np.arctan2(yy - yc, xx - xc)
	veil = atmospheric_veil(theta)
	primary_alpha = bow_alpha(r, PRIMARY_RADIUS, PRIMARY_BAND_WIDTH, PRIMARY_PEAK_ALPHA) * veil
	secondary_alpha = bow_alpha(r, SECONDARY_RADIUS, SECONDARY_BAND_WIDTH, SECONDARY_PEAK_ALPHA) * veil
	primary_rgb = spectral_color(r, PRIMARY_RADIUS, PRIMARY_BAND_WIDTH)
	secondary_rgb = spectral_color(r, SECONDARY_RADIUS, SECONDARY_BAND_WIDTH, reverse=True)
	final_alpha = primary_alpha + secondary_alpha
	final_rgb = primary_rgb * primary_alpha[..., None] + secondary_rgb * secondary_alpha[..., None]
	has_alpha = final_alpha > 1e-4
	final_rgb[has_alpha] /= final_alpha[has_alpha, None]
	base_fade = np.clip((TEXTURE_HEIGHT - yy) / 260.0, 0.0, 1.0)
	final_alpha = np.clip(final_alpha * base_fade, 0.0, 1.0)
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
