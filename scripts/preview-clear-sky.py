# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Preview the clear-sky cloud decks with `uv run scripts/preview-clear-sky.py [out.png]`."""

# This is a design aid, not a test.
# It re-implements just enough of SceneRenderer.drawScatteredClouds, CloudLayer fair-weather sprite geometry, and skyGradientFor to judge a cloud change in seconds instead of a build-and-install round trip.
# Only the resting frame is drawn: wind is zero, so drift, bob and swell all sit at their timeSeconds = 0 values.
# Placement jitter, mirroring, and sprite choice vary by epoch day on-device; this preview uses nominal anchor centers plus a fixed representative sprite layout because size/count tuning does not depend on that daily variation.
# Whenever the cloud geometry, coverage ramp, alpha ramp, or cumulus tint changes in Kotlin, mirror it here, and treat any disagreement with the device as Kotlin being right.

import argparse
from dataclasses import dataclass
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

# Mirrors CloudLayer.cumulusStyle and nearCompositionAlpha.
NEAR_BASE_HEIGHT_TO_WIDTH = 0.22
NEAR_BASE_HEIGHT_TO_DECK = 0.48
NEAR_ALPHA = 248
NEAR_ALPHA_SCALE = 0.92
SOFT_HERO_VARIANT_START = 2
SOFT_HERO_SCALE = 0.84
SOFT_HERO_ALPHA_SCALE = 0.82
NEAR_BLEND_START = 0.50
FAR_BASE_HEIGHT_TO_WIDTH = 0.082
FAR_BASE_HEIGHT_TO_DECK = 0.24
FAR_WIDTH_SCALE = 1.72
FAR_HEIGHT_SCALE = 0.52
FAR_ALPHA_SCALE = 1.18 * 1.12
FAR_RISE = 0.12
FAR_TINT_LIFT = 0.16

# Mirrors ScenePalette.basePhaseGradient(DAY) and phaseGray(DAY), plus OVERCAST_GRAY_FLOOR and OVERCAST_GRAY_FULL.
SKY_TOP = np.array([74, 144, 217], dtype=np.float32)
SKY_BOTTOM = np.array([169, 214, 245], dtype=np.float32)
PHASE_GRAY = np.array([150, 160, 170], dtype=np.float32)
GRAY_FLOOR = 0.55
GRAY_FULL = 0.85

# Mirrors SceneRenderer.cumulusTint(DAY): the textures carry their own shading, so daylight passes through untouched.
CUMULUS_TINT = np.array([255, 255, 255], dtype=np.float32)

NEAR_SPRITES = (
	'cloud_cumulus_hero_broad.webp',
	'cloud_cumulus_hero_broad_alt.webp',
	'cloud_cumulus_hero_soft_broad.webp',
	'cloud_cumulus_hero_soft_broad_alt.webp',
)
FAR_SPRITES = ('cloud_cumulus_far_veil_broad.png', 'cloud_cumulus_far_veil_layered.png')

# The preview intentionally pins representative near-sprite choices instead of reproducing daily runtime randomness.
# Sparse includes both an original and a soft hero so low-coverage tuning exercises both families.
REPRESENTATIVE_SPARSE_VARIANTS = (2, 0)
REPRESENTATIVE_SCATTERED_VARIANTS = (1, 3, 0, 2, 1)
REPRESENTATIVE_BROKEN_VARIANTS = (3, 0, 2, 1, 3, 2, 0, 1, 2)

# Nominal CloudLayer anchors before its small seeded day-to-day jitter.
SPARSE_ANCHORS = (
	(0.18, 0.34, 1.00, 1.00),
	(0.72, 0.45, 0.82, 0.96),
)
SCATTERED_ANCHORS = (
	(0.05, 0.31, 0.78, 0.94),
	(0.27, 0.50, 0.92, 1.00),
	(0.50, 0.24, 0.76, 0.96),
	(0.73, 0.55, 0.86, 0.94),
	(0.94, 0.37, 0.72, 0.92),
)
BROKEN_ANCHORS = (
	(0.04, 0.43, 0.92, 1.00),
	(0.16, 0.24, 0.74, 0.96),
	(0.29, 0.57, 0.82, 1.00),
	(0.41, 0.34, 0.96, 1.00),
	(0.54, 0.20, 0.70, 0.94),
	(0.66, 0.52, 0.86, 1.00),
	(0.78, 0.30, 0.76, 0.94),
	(0.89, 0.60, 0.72, 0.90),
	(0.98, 0.41, 0.80, 0.92),
)
FAR_ANCHORS = (
	(0.06, 0.38, 0.92, 0.94),
	(0.23, 0.56, 0.78, 0.88),
	(0.42, 0.30, 0.84, 0.92),
	(0.61, 0.61, 0.74, 0.86),
	(0.79, 0.43, 0.90, 0.94),
	(0.95, 0.27, 0.70, 0.84),
)
@dataclass(frozen=True)
class CumulusProfile:
	kind: str
	sprite_names: tuple[str, ...]
	anchors: tuple[tuple[float, float, float, float], ...]
	viewports: float
	variant_indices: tuple[int, ...] | None = None


@dataclass(frozen=True)
class CumulusGeometry:
	deck_height: float
	offset: float
	top: float
	size_scale: float


@dataclass(frozen=True)
class SpriteGeometry:
	center_x: float
	center_y: float
	width: float
	height: float


FAR_PROFILE = CumulusProfile('far', FAR_SPRITES, FAR_ANCHORS, FAR_VIEWPORTS)
COVERAGE_STEPS = (
	CumulusProfile('sparse', NEAR_SPRITES, SPARSE_ANCHORS, NEAR_VIEWPORTS, REPRESENTATIVE_SPARSE_VARIANTS),
	CumulusProfile('scattered', NEAR_SPRITES, SCATTERED_ANCHORS, NEAR_VIEWPORTS, REPRESENTATIVE_SCATTERED_VARIANTS),
	CumulusProfile('broken', NEAR_SPRITES, BROKEN_ANCHORS, NEAR_VIEWPORTS, REPRESENTATIVE_BROKEN_VARIANTS),
)


def lerp(a, b, fraction):
	return a + (b - a) * float(np.clip(fraction, 0, 1))


def kotlin_round(value):
	return int(np.floor(value + 0.5))


def lift_toward_white(color, amount):
	return color + (255 - color) * amount


def effective_cloudiness(cloudiness, cloud_count_scale):
	clamped_scale = float(np.clip(cloud_count_scale, CLOUD_COUNT_SCALE_MIN, CLOUD_COUNT_SCALE_MAX))
	return float(np.clip(cloudiness * clamped_scale, 0, 1))


def sky(cloudiness):
	"""Mirrors skyGradientFor for a dry, fogless day."""
	overcast = float(np.clip((cloudiness - GRAY_FLOOR) / (GRAY_FULL - GRAY_FLOOR), 0, 1))
	top = lerp(SKY_TOP, PHASE_GRAY, overcast)
	bottom = lerp(SKY_BOTTOM, PHASE_GRAY, overcast)
	if cloudiness > DECK_THRESHOLD:
		top *= 0.93
		bottom *= 0.93

	ramp = np.linspace(0, 1, HEIGHT, dtype=np.float32)[:, None]
	column = top[None, :] * (1 - ramp) + bottom[None, :] * ramp
	return np.repeat(column[:, None, :], WIDTH, axis=1), top, bottom


def near_composition_alpha(kind, alpha):
	if kind == 'sparse':
		return alpha

	floor = int(255 * NEAR_BLEND_START)
	if alpha <= floor:
		return 0

	return min(int((alpha - floor) / (1 - NEAR_BLEND_START)), 255)


def composite_sprite(destination, source, geometry, multiply, alpha):
	if alpha <= 0 or geometry.width <= 0 or geometry.height <= 0:
		return

	resized = source.resize((max(round(geometry.width), 1), max(round(geometry.height), 1)), Image.Resampling.BILINEAR)
	patch = np.asarray(resized, dtype=np.float32)
	rgb = np.clip(patch[:, :, :3] * (multiply[None, None, :] / 255.0), 0, 255)
	opacity = patch[:, :, 3:4] / 255.0 * (alpha / 255.0)

	left = round(geometry.center_x - patch.shape[1] * 0.5)
	top = round(geometry.center_y - patch.shape[0] * 0.5)
	right = left + patch.shape[1]
	bottom = top + patch.shape[0]

	dst_left = max(left, 0)
	dst_top = max(top, 0)
	dst_right = min(right, WIDTH)
	dst_bottom = min(bottom, HEIGHT)
	if dst_left >= dst_right or dst_top >= dst_bottom:
		return

	src_left = dst_left - left
	src_top = dst_top - top
	src_right = src_left + (dst_right - dst_left)
	src_bottom = src_top + (dst_bottom - dst_top)
	patch_rgb = rgb[src_top:src_bottom, src_left:src_right]
	patch_opacity = opacity[src_top:src_bottom, src_left:src_right]
	destination[dst_top:dst_bottom, dst_left:dst_right] = patch_rgb * patch_opacity + destination[dst_top:dst_bottom, dst_left:dst_right] * (1 - patch_opacity)


def draw_cumulus(destination, profile, geometry, multiply, alpha):
	"""Mirror CloudLayer's center-preserving sprite geometry; size changes each body, never the repeat span or anchor centers."""
	period = WIDTH * profile.viewports
	if profile.kind == 'far':
		base_height = min(WIDTH * FAR_BASE_HEIGHT_TO_WIDTH, geometry.deck_height * FAR_BASE_HEIGHT_TO_DECK) * geometry.size_scale
		top_offset = geometry.deck_height * FAR_RISE
		width_scale = FAR_WIDTH_SCALE
		height_scale = FAR_HEIGHT_SCALE
		style_alpha = FAR_ALPHA_SCALE
		multiply = lift_toward_white(multiply, FAR_TINT_LIFT)
		composition_alpha = alpha
	else:
		base_height = min(WIDTH * NEAR_BASE_HEIGHT_TO_WIDTH, geometry.deck_height * NEAR_BASE_HEIGHT_TO_DECK) * geometry.size_scale
		top_offset = 0
		width_scale = 1.0
		height_scale = 1.0
		style_alpha = NEAR_ALPHA_SCALE
		composition_alpha = near_composition_alpha(profile.kind, alpha)

	if composition_alpha <= 0:
		return

	sprites = [Image.open(DRAWABLE / name).convert('RGBA') for name in profile.sprite_names]
	wrapped_offset = geometry.offset % period
	for index, (x_fraction, y_fraction, placement_scale, alpha_scale) in enumerate(profile.anchors):
		if profile.variant_indices is None:
			variant_index = index % len(sprites)
		else:
			variant_index = profile.variant_indices[index]

		sprite = sprites[variant_index]
		if profile.kind != 'far' and variant_index >= SOFT_HERO_VARIANT_START:
			placement_scale *= SOFT_HERO_SCALE
			alpha_scale *= SOFT_HERO_ALPHA_SCALE

		sprite_height = base_height * placement_scale * height_scale
		sprite_width = sprite_height * sprite.width / sprite.height * width_scale
		center_x = (wrapped_offset + period * x_fraction) % period
		center_y = geometry.top - top_offset + geometry.deck_height * y_fraction
		sprite_alpha = int(composition_alpha * style_alpha * alpha_scale)

		for shift in (-1, 0, 1):
			wrapped_x = center_x + shift * period
			if wrapped_x + sprite_width * 0.5 < 0 or wrapped_x - sprite_width * 0.5 > WIDTH:
				continue

			sprite_geometry = SpriteGeometry(wrapped_x, center_y, sprite_width, sprite_height)
			composite_sprite(destination, sprite, sprite_geometry, multiply, sprite_alpha)


def clear_sky(cloudiness, cloud_scale=1.0, cloud_size_scale=1.0, cloud_count_scale=1.0):
	"""Mirrors drawScatteredClouds at timeSeconds = 0 with no wind."""
	effective = effective_cloudiness(cloudiness, cloud_count_scale)
	canvas, top, _ = sky(effective)
	coverage = float(np.clip((effective - SCATTERED_FLOOR) / (DECK_THRESHOLD - SCATTERED_FLOOR), 0, 1))
	size_scale = float(np.clip(cloud_size_scale, CLOUD_SIZE_SCALE_MIN, CLOUD_SIZE_SCALE_MAX))
	step = coverage * (len(COVERAGE_STEPS) - 1)
	lower = int(np.floor(step))
	blend = step - lower
	near_color = CUMULUS_TINT
	far_color = lerp(CUMULUS_TINT, top, 0.35)

	# Mirrors SceneRenderer's portrait geometry at this preview's fixed 1080x2340 surface.
	cloud_top = max(HEIGHT * 0.10, HEIGHT * 0.17 + WIDTH * 0.072 * 1.8 - HEIGHT * 0.08)
	far_top = cloud_top + HEIGHT * 0.22
	far_height = HEIGHT * 0.34
	near_height = HEIGHT * 0.46
	near_alpha = min(max(kotlin_round(NEAR_ALPHA * cloud_scale), 0), 255)
	far_alpha = min(max(kotlin_round((70 + 90 * coverage) * cloud_scale), 0), 255)

	far_geometry = CumulusGeometry(far_height, -WIDTH * 0.34, far_top, size_scale)
	draw_cumulus(canvas, FAR_PROFILE, far_geometry, far_color, far_alpha)

	for index, weight in ((lower, 1.0), (lower + 1, blend)):
		if index >= len(COVERAGE_STEPS) or weight < 0.02:
			continue

		profile = COVERAGE_STEPS[index]
		alpha = near_alpha if weight == 1.0 else min(max(kotlin_round(NEAR_ALPHA * cloud_scale * weight), 0), 255)
		near_geometry = CumulusGeometry(near_height, -WIDTH * 0.78, cloud_top, size_scale)
		draw_cumulus(canvas, profile, near_geometry, near_color, alpha)

	luma = 0.2126 * canvas[:, :, 0] + 0.7152 * canvas[:, :, 1] + 0.0722 * canvas[:, :, 2]
	print(f'cloudiness={cloudiness:.2f} effective={effective:.2f} coverage={coverage:.2f} size={size_scale:.2f} count={cloud_count_scale:.2f} step={COVERAGE_STEPS[lower].kind}+{blend:.2f}', end=' ')
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
	image = image.resize((image.width // 5, image.height // 5), Image.Resampling.LANCZOS)
	image.save(args.out)
	print(f'{args.out}: {image.width}x{image.height}')


if __name__ == '__main__':
	main()
