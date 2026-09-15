# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0", "scipy==1.18.1"]
# ///
"""Generate cloud sheets and photographic cumulus layers with `uv run scripts/generate-cloud-textures.py`."""

from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy.ndimage import map_coordinates

TEXTURE_WIDTH = 2160
TEXTURE_HEIGHT = 640
HERO_TEXTURE_HEIGHT = 320
HORIZON_TEXTURE_HEIGHT = 200
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'
ASSETS = Path(__file__).resolve().parent / 'assets'


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


def extract_hero_cloud(crop_rgb, core_thresh=32.0, min_thresh=6.0, min_body_lum=175.0):
	sig = np.max(crop_rgb, axis=2)
	alpha = np.zeros_like(sig)
	mask_core = sig >= core_thresh
	mask_fringe = (sig > min_thresh) & (sig < core_thresh)
	alpha[mask_core] = 255.0
	t = (sig[mask_fringe] - min_thresh) / (core_thresh - min_thresh)
	alpha[mask_fringe] = 255.0 * (t * t * (3.0 - 2.0 * t))

	height, width = alpha.shape
	unmult_rgb = np.zeros_like(crop_rgb)
	for c in range(3):
		chan = crop_rgb[:, :, c]
		chan_core = chan[mask_core]
		min_c = np.min(chan_core) if len(chan_core) > 0 else 0.0
		lifted = min_body_lum + (chan - min_c) * (255.0 - min_body_lum) / max(1.0, 255.0 - min_c)
		fringe_factor = np.clip(1.0 - alpha / 255.0, 0, 1)
		chan_clean = np.where(mask_core, lifted, lifted * (1.0 - fringe_factor) + 248.0 * fringe_factor)
		unmult_rgb[:, :, c] = np.where(alpha > 0, np.clip(chan_clean, 0, 255), 255.0)

	fade_rows = 4
	for r in range(fade_rows):
		row_idx = height - fade_rows + r
		factor = (r + 1) / float(fade_rows)
		alpha[row_idx, :] *= factor

	alpha[-1, :] = 0.0
	alpha[:, :4] *= np.linspace(0, 1, 4)[np.newaxis, :]
	alpha[:, -4:] *= np.linspace(1, 0, 4)[np.newaxis, :]
	alpha[:4, :] *= np.linspace(0, 1, 4)[:, np.newaxis]

	rgba = np.dstack([unmult_rgb, alpha]).astype(np.uint8)
	ys, xs = np.nonzero(alpha > 1)
	if len(xs) == 0:
		return Image.fromarray(rgba, 'RGBA')

	margin = 6
	left = max(0, xs.min() - margin)
	top = max(0, ys.min() - margin)
	right = min(width, xs.max() + margin + 1)
	bottom = min(height, ys.max() + margin + 1)

	return Image.fromarray(rgba, 'RGBA').crop((left, top, right, bottom))


def extract_horizon_band():
	horizon_plate = Image.open(ASSETS / 'cumulus_horizon_plate.png')
	arr = np.array(horizon_plate)[:, :, :3].astype(float)
	crop = arr[315:455, :]

	sig = np.max(crop, axis=2)
	alpha = np.zeros_like(sig)
	mask_core = sig >= 32.0
	mask_fringe = (sig > 6.0) & (sig < 32.0)
	alpha[mask_core] = 190.0
	t = (sig[mask_fringe] - 6.0) / (32.0 - 6.0)
	alpha[mask_fringe] = 190.0 * (t * t * (3.0 - 2.0 * t))

	img_a = Image.fromarray(alpha.astype(np.uint8))
	img_a_soft = img_a.filter(ImageFilter.GaussianBlur(radius=2.2))
	alpha_soft = np.array(img_a_soft).astype(float)

	fade_w = min(120, crop.shape[1] // 6)
	lf = 0.5 * (1.0 - np.cos(np.linspace(0, np.pi, fade_w)))
	rf = 0.5 * (1.0 + np.cos(np.linspace(0, np.pi, fade_w)))
	alpha_soft[:, :fade_w] *= lf[np.newaxis, :]
	alpha_soft[:, -fade_w:] *= rf[np.newaxis, :]

	unmult = np.zeros_like(crop)
	for c in range(3):
		chan = crop[:, :, c]
		min_c = np.min(chan[alpha > 40])
		chan_lifted = 210.0 + (chan - min_c) * (255.0 - 210.0) / max(1.0, (255.0 - min_c))
		fringe = np.clip(1.0 - alpha_soft / 190.0, 0, 1)
		unmult[:, :, c] = np.where(alpha_soft > 0, np.clip(chan_lifted * (1.0 - fringe) + 245.0 * fringe, 0, 255), 255.0)

	h = alpha_soft.shape[0]
	for r in range(6):
		alpha_soft[h - 6 + r, :] *= (r / 6.0)

	return Image.fromarray(np.dstack([unmult.astype(np.uint8), alpha_soft.astype(np.uint8)]), 'RGBA')


def paste_safe(canvas, sprite, cx, cy, scale=1.0):
	w = int(sprite.width * scale)
	h = int(sprite.height * scale)
	scaled = sprite.resize((w, h), Image.Resampling.LANCZOS)
	x = int(cx - w / 2)
	y = int(cy - h / 2)
	canvas.paste(scaled, (x, y), scaled)
	if x + w > TEXTURE_WIDTH:
		canvas.paste(scaled, (x - TEXTURE_WIDTH, y), scaled)
	if x < 0:
		canvas.paste(scaled, (x + TEXTURE_WIDTH, y), scaled)


def load_hero_clouds():
	hero_plate = Image.open(ASSETS / 'cumulus_hero_plate.png')
	arr = np.array(hero_plate)[:, :, :3].astype(float)
	cloud_a = extract_hero_cloud(arr[230:520, 25:485])
	cloud_b = extract_hero_cloud(arr[230:520, 505:900])
	cloud_c = extract_hero_cloud(arr[230:520, 910:1345])
	return [cloud_a, cloud_b, cloud_c]


def cumulus_sparse_texture():
	clouds = load_hero_clouds()
	canvas = Image.new('RGBA', (TEXTURE_WIDTH, HERO_TEXTURE_HEIGHT), (0, 0, 0, 0))
	paste_safe(canvas, clouds[0], 330, 156, scale=0.46)
	paste_safe(canvas, clouds[1], 900, 118, scale=0.34)
	paste_safe(canvas, clouds[2], 1510, 168, scale=0.39)
	paste_safe(canvas, clouds[1], 2040, 150, scale=0.30)
	return canvas


def cumulus_near_texture():
	clouds = load_hero_clouds()
	canvas = Image.new('RGBA', (TEXTURE_WIDTH, HERO_TEXTURE_HEIGHT), (0, 0, 0, 0))
	paste_safe(canvas, clouds[0], 250, 164, scale=0.58)
	paste_safe(canvas, clouds[1].transpose(Image.Transpose.FLIP_LEFT_RIGHT), 700, 126, scale=0.44)
	paste_safe(canvas, clouds[2], 1170, 170, scale=0.68)
	paste_safe(canvas, clouds[1], 1570, 132, scale=0.46)
	paste_safe(canvas, clouds[0].transpose(Image.Transpose.FLIP_LEFT_RIGHT), 2010, 176, scale=0.52)
	return canvas


def cumulus_mid_texture():
	clouds = load_hero_clouds()
	canvas = Image.new('RGBA', (TEXTURE_WIDTH, HERO_TEXTURE_HEIGHT), (0, 0, 0, 0))
	paste_safe(canvas, clouds[1], 210, 132, scale=0.40)
	paste_safe(canvas, clouds[2].transpose(Image.Transpose.FLIP_LEFT_RIGHT), 660, 160, scale=0.46)
	paste_safe(canvas, clouds[0], 1110, 122, scale=0.38)
	paste_safe(canvas, clouds[1], 1530, 154, scale=0.42)
	paste_safe(canvas, clouds[2], 1990, 130, scale=0.40)
	return canvas


def cumulus_horizon_texture():
	horizon_band = extract_horizon_band()
	canvas = Image.new('RGBA', (TEXTURE_WIDTH, HORIZON_TEXTURE_HEIGHT), (0, 0, 0, 0))
	w_seg = 1450
	h_seg = int(horizon_band.height * w_seg / horizon_band.width)
	scaled_seg = horizon_band.resize((w_seg, h_seg), Image.Resampling.LANCZOS)
	paste_safe(canvas, scaled_seg, 540, 100, scale=1.0)
	paste_safe(canvas, scaled_seg, 1620, 100, scale=1.0)
	return canvas


def main():
	OUTPUT.mkdir(parents=True, exist_ok=True)
	for name, seed, distant in (('cloud_sheet_far', 823, True), ('cloud_sheet_near', 1759, False)):
		image = cloud_texture(seed, distant)
		path = OUTPUT / f'{name}.png'
		image.save(path, optimize=True)
		print(f'{path.relative_to(OUTPUT.parents[4])}: {image.width}x{image.height}, {path.stat().st_size} bytes')

	cumulus_layers = (
		('cloud_cumulus_sparse', cumulus_sparse_texture),
		('cloud_cumulus_mid', cumulus_mid_texture),
		('cloud_cumulus_near', cumulus_near_texture),
		('cloud_cumulus_horizon', cumulus_horizon_texture),
	)
	for name, builder in cumulus_layers:
		image = builder()
		path = OUTPUT / f'{name}.png'
		image.save(path, optimize=True)
		print(f'{path.relative_to(OUTPUT.parents[4])}: {image.width}x{image.height}, {path.stat().st_size} bytes')


if __name__ == '__main__':
	main()
