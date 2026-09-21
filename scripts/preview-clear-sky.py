# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Preview the clear-sky cloud decks with `uv run scripts/preview-clear-sky.py [out.png]`."""

# This is a design aid, not a test.
# It re-implements just enough of `SceneRenderer.drawScatteredClouds` and `skyGradientFor` to judge a texture change in seconds instead of a build-and-install round trip, and it will drift from the Kotlin if nobody keeps it honest.
# Only the resting frame is drawn: wind is zero, so drift, bob and swell all sit at their `timeSeconds = 0` values.
# Whenever the deck geometry, the alpha ramp or the cumulus tint changes in Kotlin, mirror it here, and treat any disagreement with the device as the Kotlin being right.

import argparse
from pathlib import Path

import numpy as np
from PIL import Image

DRAWABLE = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'
WIDTH = 1080
HEIGHT = 2340

# Mirrors CLOUD_SIZE_SCALE_RANGE and CLOUD_COUNT_SCALE_RANGE.
CLOUD_SIZE_SCALE_MIN = 0.5
CLOUD_SIZE_SCALE_MAX = 2.0
CLOUD_COUNT_SCALE_MIN = 0.5
CLOUD_COUNT_SCALE_MAX = 2.0

# Mirrors SceneRenderer: CLOUD_TEXTURE_VIEWPORTS, CUMULUS_FAR_VIEWPORTS, CLOUD_DECK_THRESHOLD, SCATTERED_CLOUD_FLOOR.
NEAR_VIEWPORTS = 4.0
FAR_VIEWPORTS = 2.5
DECK_THRESHOLD = 0.75
SCATTERED_FLOOR = 0.1

# Mirrors CloudLayer's fair-weather depth correction.
NEAR_HEIGHT_SCALE = 1.12
NEAR_RISE = 0.12
FAR_HEIGHT_SCALE = 0.85
FAR_DROP = 0.18
FAR_ALPHA_FLOOR = 35
FAR_ALPHA_SCALE = 0.75

# Mirrors ScenePalette.basePhaseGradient(DAY) and phaseGray(DAY), plus OVERCAST_GRAY_FLOOR and OVERCAST_GRAY_FULL.
SKY_TOP = np.array([74, 144, 217], dtype=np.float32)
SKY_BOTTOM = np.array([169, 214, 245], dtype=np.float32)
PHASE_GRAY = np.array([150, 160, 170], dtype=np.float32)
GRAY_FLOOR = 0.55
GRAY_FULL = 0.85

# Mirrors SceneRenderer.cumulusTint(DAY): the textures carry their own shading, so daylight passes through untouched.
CUMULUS_TINT = np.array([255, 255, 255], dtype=np.float32)

# Mirrors SceneRenderer.CUMULUS_COVERAGE_STEPS, in the same order, and the single far-deck texture beside it.
COVERAGE_STEPS = ('cloud_cumulus_sparse', 'cloud_cumulus_scattered', 'cloud_cumulus_broken')
FAR_TEXTURE = 'cloud_cumulus_far'


def lerp(a, b, fraction):
	return a + (b - a) * float(np.clip(fraction, 0, 1))


def effective_cloudiness(cloudiness, cloud_count_scale):
	clamped_scale = float(np.clip(cloud_count_scale, CLOUD_COUNT_SCALE_MIN, CLOUD_COUNT_SCALE_MAX))
	return float(np.clip(cloudiness * clamped_scale, 0, 1))


def sky(cloudiness):
	"""Mirrors skyGradientFor for a dry, fogless day: clear blue holds until the overcast threshold."""
	overcast = float(np.clip((cloudiness - GRAY_FLOOR) / (GRAY_FULL - GRAY_FLOOR), 0, 1))
	top = lerp(SKY_TOP, PHASE_GRAY, overcast)
	bottom = lerp(SKY_BOTTOM, PHASE_GRAY, overcast)
	ramp = np.linspace(0, 1, HEIGHT, dtype=np.float32)[:, None]
	column = top[None, :] * (1 - ramp) + bottom[None, :] * ramp
	return np.repeat(column[:, None, :], WIDTH, axis=1), top, bottom


def deck(name, deck_height, offset, viewports):
	"""Mirrors CloudLayer.draw: REPEAT in x and CLAMP in y under a scale-then-translate matrix."""
	texture = np.asarray(Image.open(DRAWABLE / f'{name}.png').convert('RGBA'), dtype=np.float32)
	rows, columns = texture.shape[:2]
	scale_x = WIDTH * viewports / columns
	scale_y = deck_height / rows
	xs = np.mod(np.round((np.arange(WIDTH, dtype=np.float32) - offset) / scale_x).astype(np.int64), columns)
	ys = np.clip(np.round(np.arange(deck_height, dtype=np.float32) / scale_y).astype(np.int64), 0, rows - 1)
	return texture[np.ix_(ys, xs)]


def composite(destination, name, deck_height, offset, top, multiply, alpha, viewports):
	patch = deck(name, int(deck_height), offset, viewports)
	rgb = np.clip(patch[:, :, :3] * (multiply[None, None, :] / 255.0), 0, 255)
	opacity = patch[:, :, 3:4] / 255.0 * (alpha / 255.0)
	rows = slice(max(int(top), 0), min(int(top) + int(deck_height), HEIGHT))
	span = rows.stop - rows.start
	if span <= 0:
		return

	patch_start = max(-int(top), 0)
	patch_end = patch_start + span
	destination[rows] = rgb[patch_start:patch_end] * opacity[patch_start:patch_end] + destination[rows] * (1 - opacity[patch_start:patch_end])


def clear_sky(cloudiness, cloud_scale=1.0, cloud_size_scale=1.0, cloud_count_scale=1.0):
	"""Mirrors drawScatteredClouds at timeSeconds = 0 with no wind."""
	canvas, top, _ = sky(cloudiness)
	effective = effective_cloudiness(cloudiness, cloud_count_scale)
	coverage = float(np.clip((effective - SCATTERED_FLOOR) / (DECK_THRESHOLD - SCATTERED_FLOOR), 0, 1))
	size_scale = float(np.clip(cloud_size_scale, CLOUD_SIZE_SCALE_MIN, CLOUD_SIZE_SCALE_MAX))
	step = coverage * (len(COVERAGE_STEPS) - 1)
	lower = int(np.floor(step))
	blend = step - lower
	near_color = CUMULUS_TINT
	far_color = lerp(CUMULUS_TINT, top, 0.35)

	# SceneRenderer still places the base deck below the sun. CloudLayer then raises the near masses enough that drifting clouds can naturally cross its disc.
	cloud_top = max(HEIGHT * 0.10, HEIGHT * 0.17 + WIDTH * 0.072 * 1.8 - HEIGHT * 0.08)
	far_top = cloud_top + HEIGHT * 0.22
	far_height = HEIGHT * 0.34
	near_height = HEIGHT * 0.46
	near_alpha = round(248 * cloud_scale)

	# Mirrors CloudLayer's depth correction without changing the repeat-span contract.
	far_base_draw_height = far_height * FAR_HEIGHT_SCALE
	far_draw_height = far_base_draw_height * size_scale
	far_draw_top = far_top + far_height * FAR_DROP - (far_draw_height - far_base_draw_height) * 0.5
	far_alpha = max(round(((70 + 90 * coverage) * cloud_scale - FAR_ALPHA_FLOOR) * FAR_ALPHA_SCALE), 0)
	near_base_draw_height = near_height * NEAR_HEIGHT_SCALE
	near_draw_height = near_base_draw_height * size_scale
	near_draw_top = cloud_top - near_height * NEAR_RISE - (near_draw_height - near_base_draw_height) * 0.5

	# The far deck stays faint and low, especially in mostly-clear weather, so it reads as atmosphere rather than another foreground sheet.
	composite(canvas, FAR_TEXTURE, far_draw_height, -WIDTH * 0.34, far_draw_top, far_color, far_alpha, FAR_VIEWPORTS)

	for index, weight in ((lower, 1.0), (lower + 1, blend)):
		if index >= len(COVERAGE_STEPS) or weight < 0.02:
			continue

		composite(canvas, COVERAGE_STEPS[index], near_draw_height, -WIDTH * 0.78, near_draw_top, near_color, round(near_alpha * weight), NEAR_VIEWPORTS)

	luma = 0.2126 * canvas[:, :, 0] + 0.7152 * canvas[:, :, 1] + 0.0722 * canvas[:, :, 2]
	print(f'cloudiness={cloudiness:.2f} effective={effective:.2f} coverage={coverage:.2f} size={size_scale:.2f} count={cloud_count_scale:.2f} step={COVERAGE_STEPS[lower]}+{blend:.2f}', end=' ')
	print(f'luma p5={np.percentile(luma, 5):5.1f} p99={np.percentile(luma, 99):5.1f} above200={(luma > 200).mean() * 100:5.2f}%')
	return canvas


def main():
	parser = argparse.ArgumentParser(description='Preview Weatherd clear-sky cloud rendering.')
	parser.add_argument('out', nargs='?', type=Path, default=Path('clear-sky-preview.png'))
	parser.add_argument('--cloud-size', type=float, default=1.0, help='Cloud body size scale (0.5–2.0).')
	parser.add_argument('--cloud-count', type=float, default=1.0, help='Rendered cloud coverage scale (0.5–2.0).')
	args = parser.parse_args()

	strip = np.concatenate([
		clear_sky(cloudiness, cloud_size_scale=args.cloud_size, cloud_count_scale=args.cloud_count)
		for cloudiness in (0.15, 0.3, 0.45, 0.6, 0.75)
	], axis=1)
	image = Image.fromarray(np.clip(strip, 0, 255).astype(np.uint8))
	image = image.resize((image.width // 5, image.height // 5), Image.LANCZOS)
	image.save(args.out)
	print(f'{args.out}: {image.width}x{image.height}')


if __name__ == '__main__':
	main()
