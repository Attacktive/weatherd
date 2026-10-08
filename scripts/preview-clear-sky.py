# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0"]
# ///
"""Preview the clear-sky cloud decks with `uv run scripts/preview-clear-sky.py [out.png]`."""

# This is a design aid, not a test.
# Mirrors SceneRenderer.drawDryClouds, the portrait overcast handoff, CloudLayer sprite geometry, and the default phase sky.
# Only the resting frame is drawn: wind is zero, so drift, bob and swell all sit at their timeSeconds = 0 values.
# Placement jitter, mirroring, and morphology-variant choice vary by epoch day on-device; this preview uses nominal anchor centers plus a fixed representative variant layout because size/count tuning does not depend on that daily variation.
# Whenever cloud geometry, coverage, opacity, or material grading changes in Kotlin, mirror it here; Kotlin wins any disagreement.

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

# Mirrors CloudLayer.cumulusStyle; established cloud opacity never tracks coverage.
NEAR_BASE_HEIGHT_TO_WIDTH = 0.40
NEAR_BASE_HEIGHT_TO_DECK = 0.44
NEAR_ALPHA = 248
NEAR_ALPHA_SCALE = 0.92
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

# Day preserves baked shading; phase material grading runs before depth tint.
CUMULUS_TINT = np.array([255, 255, 255], dtype=np.float32)
MATERIAL_GRADE = (np.zeros(3), np.zeros(3), 0.0)
CIRRUS_TINT = np.array([249, 251, 255], dtype=np.float32)
CEILING_TINT = np.array([120, 128, 140], dtype=np.float32)

NEAR_SPRITES = (
	'cloud_cumulus_hero_broad.webp',
	'cloud_cumulus_hero_broad_alt.webp',
	'cloud_cumulus_hero_soft_broad.webp',
	'cloud_cumulus_hero_soft_broad_alt.webp',
)
FAR_SPRITES = ('cloud_cumulus_far_veil_broad.png', 'cloud_cumulus_far_veil_layered.png')
# Portrait banks preserve the source 3:1 proportions; the lower pass reuses the veil.
OVERCAST_PASSES = (
	('cloud_overcast_veil.webp', 1.35, -0.08, 0.22, 0.46, 0.96),
	('cloud_overcast_support.webp', 1.50, 0.11, 0.67, 0.72, 0.94),
	('cloud_overcast_hero.webp', 3.0 / 1.25, 0.29, 0.16, 0.88 * 0.96, 1.00),
	('cloud_overcast_veil.webp', 1.50, 0.47, 0.91, 0.50, 0.91),
	('cloud_overcast_veil.webp', 1.80, 0.64, 0.43, 0.50, 0.91),
)

PORTRAIT_OVERCAST_SPAN_SCALE = 1.25

CIRRUS_SPRITES = ('cloud_cirrus_sparse.webp', 'cloud_cirrus_scattered.webp', 'cloud_cirrus_broken.webp')
CIRRUS_FLOOR = 0.1
CIRRUS_VIEWPORTS = 3.0
CIRRUS_TOP = 0.04
CIRRUS_HEIGHT = 0.30
CIRRUS_ALPHA = 230

# One nominal master layout, with representative morphology rather than Android's seeded jitter/mirroring.
ANCHOR_GROUPS = (
	((0.25, 0.31, 0.92), (0.75, 0.62, 0.92)),
	((0.50, 0.80, 1.00),),
	((0.12, 0.54, 1.08), (0.88, 0.90, 0.92)),
	((0.55, 0.44, 1.12), (0.12, 0.73, 1.08), (0.74, 0.22, 1.00)),
)

MASTER_ANCHORS = tuple(((viewport + x) / 4, y, scale, 1.0) for group in ANCHOR_GROUPS for viewport in range(4) for x, y, scale in group)
MASTER_VARIANTS = tuple(index % 7 for index in range(32))
POPULATION_COUNTS = (8, 12, 20, 32)
FAR_ANCHORS = (
	(0.06, 0.38, 0.92, 0.94),
	(0.14, 0.51, 0.72, 0.78),
	(0.23, 0.56, 0.78, 0.88),
	(0.42, 0.30, 0.84, 0.92),
	(0.61, 0.61, 0.74, 0.86),
	(0.70, 0.54, 0.70, 0.80),
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


@dataclass(frozen=True)
class CumulusRenderStyle:
	base_height: float
	top_offset: float
	width_scale: float
	height_scale: float
	alpha_scale: float
	multiply: np.ndarray


@dataclass(frozen=True)
class HeroPart:
	sprite_index: int
	offset_x: float = 0.0
	offset_y: float = 0.0
	scale: float = 1.0
	width_scale: float = 1.0
	height_scale: float = 1.0


@dataclass(frozen=True)
class HeroVariant:
	parts: tuple[HeroPart, ...]


HERO_VARIANTS = (
	HeroVariant((HeroPart(0),)),
	HeroVariant((HeroPart(1),)),
	HeroVariant((HeroPart(2, scale=1.04, height_scale=1.10),)),
	HeroVariant((HeroPart(0, width_scale=1.12, height_scale=0.85),)),
	HeroVariant((HeroPart(3, scale=1.10, height_scale=1.18),)),
	HeroVariant((HeroPart(1, width_scale=0.90, height_scale=1.06),)),
	HeroVariant((
		HeroPart(2, offset_x=-0.28, offset_y=0.015, scale=0.70, width_scale=1.15, height_scale=1.10),
		HeroPart(3, offset_x=0.28, offset_y=-0.03, scale=0.78, width_scale=1.10, height_scale=1.18),
	)),
)


FAR_PROFILE = CumulusProfile('far', FAR_SPRITES, FAR_ANCHORS, FAR_VIEWPORTS)
COVERAGE_STEPS = tuple(
	CumulusProfile(kind, NEAR_SPRITES, MASTER_ANCHORS[:count], NEAR_VIEWPORTS, MASTER_VARIANTS[:count])
	for kind, count in zip(('sparse', 'scattered', 'partly', 'broken'), POPULATION_COUNTS)
)


def lerp(a, b, fraction):
	return a + (b - a) * float(np.clip(fraction, 0, 1))


def kotlin_round(value):
	return int(np.floor(value + 0.5))


def smoothstep(start, end, value):
	fraction = float(np.clip((value - start) / (end - start), 0, 1))
	return fraction * fraction * (3 - 2 * fraction)


def cloud_grade(phase, progress):
	night_shadow = np.array([30, 40, 61], dtype=np.float32)
	night_highlight = np.array([109, 125, 150], dtype=np.float32)
	warm_shadow = np.array([89, 121, 153], dtype=np.float32)
	warm_highlight = np.array([255, 246, 213], dtype=np.float32)
	if phase == 'dawn':
		warmth = smoothstep(0, 0.5, progress)
		return lerp(night_shadow, warm_shadow, warmth), lerp(night_highlight, warm_highlight, warmth), 1 - smoothstep(0.5, 0.85, progress)
	if phase == 'dusk':
		nightfall = smoothstep(0.65, 1, progress)
		return lerp(warm_shadow, night_shadow, nightfall), lerp(warm_highlight, night_highlight, nightfall), max(smoothstep(0.15, 0.5, progress), nightfall)
	if phase == 'night':
		return night_shadow, night_highlight, 1.0
	return warm_shadow, warm_highlight, 0.0


def grade_rgb(rgb):
	shadow, highlight, strength = MATERIAL_GRADE
	luma = rgb @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
	graded = shadow + (highlight - shadow) * ((luma[:, :, None] - 128) / 127)
	return rgb * (1 - strength) + graded * strength


def phase_sky(phase, progress):
	gradients = {
		'day': ((74, 144, 217), (169, 214, 245)),
		'dawn': ((52, 64, 107), (246, 169, 132)),
		'dusk': ((38, 49, 79), (232, 130, 91)),
		'night': ((11, 16, 38), (27, 36, 80)),
	}

	grays = {'day': (150, 160, 170), 'dawn': (120, 120, 140), 'dusk': (110, 110, 130), 'night': (28, 32, 42)}
	top, bottom = (np.array(color, dtype=np.float32) for color in gradients[phase])
	day_top, day_bottom = (np.array(color, dtype=np.float32) for color in gradients['day'])
	if phase == 'dawn':
		daylight = smoothstep(0.5, 0.85, progress)
		top, bottom = lerp(top, day_top, daylight), lerp(bottom, day_bottom, daylight)
	elif phase == 'dusk':
		warmth = smoothstep(0.15, 0.5, progress)
		nightfall = smoothstep(0.65, 1, progress)
		night_top, night_bottom = (np.array(color, dtype=np.float32) for color in gradients['night'])
		top = lerp(lerp(day_top, top, warmth), night_top, nightfall)
		bottom = lerp(lerp(day_bottom, bottom, warmth), night_bottom, nightfall)
	return top, bottom, np.array(grays[phase], dtype=np.float32)


def lift_toward_white(color, amount):
	return color + (255 - color) * amount


def effective_cloudiness(cloudiness, cloud_count_scale):
	clamped_scale = float(np.clip(cloud_count_scale, CLOUD_COUNT_SCALE_MIN, CLOUD_COUNT_SCALE_MAX))
	return float(np.clip(cloudiness * clamped_scale, 0, 1))


def near_cumulus_step(low_cloudiness):
	return float(np.clip((low_cloudiness - 0.10) / 0.65, 0, 1)) * 3


def sky(cloudiness):
	"""Mirrors skyGradientFor for the default dry, fogless phase."""
	overcast = float(np.clip((cloudiness - GRAY_FLOOR) / (GRAY_FULL - GRAY_FLOOR), 0, 1))
	top = lerp(SKY_TOP, PHASE_GRAY, overcast)
	bottom = lerp(SKY_BOTTOM, PHASE_GRAY, overcast)
	if cloudiness > DECK_THRESHOLD:
		top *= 0.93
		bottom *= 0.93

	ramp = np.linspace(0, 1, HEIGHT, dtype=np.float32)[:, None]
	column = top[None, :] * (1 - ramp) + bottom[None, :] * ramp
	return np.repeat(column[:, None, :], WIDTH, axis=1), top, bottom



def composite_sprite(destination, source, geometry, multiply, alpha, apply_grade=True):
	if alpha <= 0 or geometry.width <= 0 or geometry.height <= 0:
		return

	resized = source.resize((max(round(geometry.width), 1), max(round(geometry.height), 1)), Image.Resampling.BILINEAR)
	patch = np.asarray(resized, dtype=np.float32)
	material = grade_rgb(patch[:, :, :3]) if apply_grade else patch[:, :, :3]
	rgb = np.clip(material * (multiply[None, None, :] / 255.0), 0, 255)
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


def draw_bank(destination, sprite_name, viewports, geometry, multiply, alpha, apply_grade=True):
	period = WIDTH * viewports
	wrapped_offset = geometry.offset % period
	sprite = Image.open(DRAWABLE / sprite_name).convert('RGBA')
	for shift in (-1, 0, 1):
		left = wrapped_offset + shift * period
		center_x = left + period * 0.5
		if left >= WIDTH or left + period <= 0:
			continue

		sprite_geometry = SpriteGeometry(center_x, geometry.top + geometry.deck_height * 0.5, period, geometry.deck_height)
		composite_sprite(destination, sprite, sprite_geometry, multiply, alpha, apply_grade)


def draw_cirrus(destination, high_cloudiness, cloud_scale):
	if high_cloudiness <= CIRRUS_FLOOR or cloud_scale <= 0:
		return

	coverage = float(np.clip((high_cloudiness - CIRRUS_FLOOR) / (1 - CIRRUS_FLOOR), 0, 1))
	progress = coverage * len(CIRRUS_SPRITES)
	full_populations = min(int(progress), len(CIRRUS_SPRITES))
	partial = progress - full_populations
	alpha = min(max(kotlin_round(CIRRUS_ALPHA * cloud_scale), 0), 255)
	for index in range(full_populations):
		draw_bank(
			destination,
			CIRRUS_SPRITES[index],
			CIRRUS_VIEWPORTS,
			CumulusGeometry(HEIGHT * CIRRUS_HEIGHT, -WIDTH * 0.13, HEIGHT * CIRRUS_TOP, 1.0),
			CIRRUS_TINT,
			alpha,
			apply_grade=False
		)

	if full_populations < len(CIRRUS_SPRITES) and partial > 0:
		draw_bank(
			destination,
			CIRRUS_SPRITES[full_populations],
			CIRRUS_VIEWPORTS,
			CumulusGeometry(HEIGHT * CIRRUS_HEIGHT, -WIDTH * 0.13, HEIGHT * CIRRUS_TOP, 1.0),
			CIRRUS_TINT,
			kotlin_round(alpha * partial),
			apply_grade=False
		)


def cumulus_render_style(profile, geometry, multiply):
	if profile.kind == 'far':
		return CumulusRenderStyle(
			min(WIDTH * FAR_BASE_HEIGHT_TO_WIDTH, geometry.deck_height * FAR_BASE_HEIGHT_TO_DECK) * geometry.size_scale,
			geometry.deck_height * FAR_RISE,
			FAR_WIDTH_SCALE,
			FAR_HEIGHT_SCALE,
			FAR_ALPHA_SCALE,
			lift_toward_white(multiply, FAR_TINT_LIFT),
		)

	return CumulusRenderStyle(
		min(WIDTH * NEAR_BASE_HEIGHT_TO_WIDTH, geometry.deck_height * NEAR_BASE_HEIGHT_TO_DECK) * geometry.size_scale,
		0,
		1.0,
		1.0,
		NEAR_ALPHA_SCALE,
		multiply,
	)


def cumulus_parts(profile, index):
	if profile.variant_indices is None:
		return (HeroPart(index % len(profile.sprite_names)),)
	return HERO_VARIANTS[profile.variant_indices[index]].parts


def draw_cumulus_placement(destination, sprites, parts, anchor, geometry, style, period, alpha):
	x_fraction, y_fraction, placement_scale, alpha_scale = anchor
	base_center_x = (geometry.offset % period + period * x_fraction) % period
	base_center_y = geometry.top - style.top_offset + geometry.deck_height * y_fraction
	for part in parts:
		sprite = sprites[part.sprite_index]
		sprite_height = style.base_height * placement_scale * style.height_scale * part.scale * part.height_scale
		sprite_width = sprite_height * sprite.width / sprite.height * style.width_scale * part.width_scale
		center_x = base_center_x + part.offset_x * style.base_height * placement_scale
		center_y = base_center_y + part.offset_y * style.base_height * placement_scale
		sprite_alpha = int(alpha * style.alpha_scale * alpha_scale)
		for shift in (-1, 0, 1):
			wrapped_x = center_x + shift * period
			if wrapped_x + sprite_width * 0.5 < 0 or wrapped_x - sprite_width * 0.5 > WIDTH:
				continue

			sprite_geometry = SpriteGeometry(wrapped_x, center_y, sprite_width, sprite_height)
			composite_sprite(destination, sprite, sprite_geometry, style.multiply, sprite_alpha)


def draw_cumulus(destination, profile, geometry, multiply, alpha, start_index=0):
	"""Mirror CloudLayer's center-preserving sprite geometry; size changes each body, never the repeat span or anchor centers."""
	if alpha <= 0:
		return

	style = cumulus_render_style(profile, geometry, multiply)
	sprites = [Image.open(DRAWABLE / name).convert('RGBA') for name in profile.sprite_names]
	period = WIDTH * profile.viewports
	for index in range(start_index, len(profile.anchors)):
		draw_cumulus_placement(destination, sprites, cumulus_parts(profile, index), profile.anchors[index], geometry, style, period, alpha)


def preview_cloud_layers(cloudiness, cloud_count_scale, cloud_layers):
	effective = effective_cloudiness(cloudiness, cloud_count_scale)
	if cloud_layers is None:
		return effective, effective, effective, 0.0

	return (
		effective,
		effective_cloudiness(cloud_layers[0], cloud_count_scale),
		effective_cloudiness(cloud_layers[1], cloud_count_scale),
		effective_cloudiness(cloud_layers[2], cloud_count_scale),
	)


def draw_preview_far_clouds(destination, mid_cloudiness, cloud_scale, size_scale, far_color, cloud_top):
	if mid_cloudiness <= SCATTERED_FLOOR:
		return

	mid_coverage = float(np.clip((mid_cloudiness - SCATTERED_FLOOR) / (DECK_THRESHOLD - SCATTERED_FLOOR), 0, 1))
	far_coverage = mid_coverage * mid_coverage
	far_alpha = min(max(kotlin_round((60 + 120 * far_coverage) * cloud_scale), 0), 255)
	far_geometry = CumulusGeometry(HEIGHT * 0.34, -WIDTH * 0.34, cloud_top + HEIGHT * 0.22, size_scale)
	draw_cumulus(destination, FAR_PROFILE, far_geometry, far_color, far_alpha)


def draw_preview_banks(destination, opaque_cloudiness, cloud_scale):
	strength = smoothstep(0.70, 0.85, opaque_cloudiness)
	base = np.floor(lerp(np.array([120, 128, 140]), np.array([238, 242, 248]), 0.58) + 0.5)
	for sprite, span, top, phase, alpha, tint in OVERCAST_PASSES:
		span *= PORTRAIT_OVERCAST_SPAN_SCALE
		bank_height = WIDTH * span / 3
		geometry = CumulusGeometry(bank_height, -WIDTH * phase, HEIGHT * top, 1.0)
		draw_bank(destination, sprite, span, geometry, np.floor(base * tint + 0.5), kotlin_round(255 * alpha * cloud_scale * strength))


def draw_preview_near_clouds(destination, low_cloudiness, cloud_scale, size_scale, cloud_top):
	step = near_cumulus_step(low_cloudiness)
	lower = int(np.floor(step))
	blend = step - lower
	if low_cloudiness <= SCATTERED_FLOOR:
		return lower, blend

	birth = smoothstep(0.10, 0.20, low_cloudiness) if lower == 0 else 1.0
	near_geometry = CumulusGeometry(HEIGHT * 0.82, -WIDTH * 0.78, cloud_top, size_scale)
	for index, weight in ((lower, birth), (lower + 1, blend)):
		if index >= len(COVERAGE_STEPS) or weight <= 0:
			continue

		profile = COVERAGE_STEPS[index]
		alpha = min(max(kotlin_round(NEAR_ALPHA * cloud_scale * weight), 0), 255)
		start_index = POPULATION_COUNTS[index - 1] if index > lower else 0
		draw_cumulus(destination, profile, near_geometry, CUMULUS_TINT, alpha, start_index)

	return lower, blend


def clear_sky(cloudiness, cloud_scale=1.0, cloud_size_scale=1.0, cloud_count_scale=1.0, cloud_layers=None):
	"""Mirrors layered dry-cloud rendering at timeSeconds = 0 with no wind."""
	effective, low, mid, high = preview_cloud_layers(cloudiness, cloud_count_scale, cloud_layers)
	opaque = max(low, mid)
	canvas, top, _ = sky(opaque)
	ceiling_strength = float(np.clip((opaque - GRAY_FLOOR) / (GRAY_FULL - GRAY_FLOOR), 0, 1))
	ceiling_alpha = min(max(kotlin_round(190 * ceiling_strength * cloud_scale), 0), 255) / 255
	ceiling_opacity = np.clip(1 - np.arange(HEIGHT) / (HEIGHT * 0.6), 0, 1)[:, None, None] * ceiling_alpha
	canvas = canvas * (1 - ceiling_opacity) + CEILING_TINT * ceiling_opacity
	coverage = float(np.clip((low - SCATTERED_FLOOR) / (DECK_THRESHOLD - SCATTERED_FLOOR), 0, 1))
	size_scale = float(np.clip(cloud_size_scale, CLOUD_SIZE_SCALE_MIN, CLOUD_SIZE_SCALE_MAX))
	cloud_top = HEIGHT * -0.04
	far_color = lerp(CUMULUS_TINT, top, 0.35)

	draw_cirrus(canvas, high, cloud_scale)
	draw_preview_far_clouds(canvas, mid, cloud_scale, size_scale, far_color, cloud_top)
	lower, blend = draw_preview_near_clouds(canvas, low, cloud_scale, size_scale, cloud_top)
	draw_preview_banks(canvas, opaque, cloud_scale)

	luma = 0.2126 * canvas[:, :, 0] + 0.7152 * canvas[:, :, 1] + 0.0722 * canvas[:, :, 2]
	print(f'cloudiness={cloudiness:.2f} effective={effective:.2f} low={low:.2f} mid={mid:.2f} high={high:.2f} coverage={coverage:.2f} size={size_scale:.2f} count={cloud_count_scale:.2f} step={COVERAGE_STEPS[lower].kind}+{blend:.2f}', end=' ')
	print(f'luma p5={np.percentile(luma, 5):5.1f} p99={np.percentile(luma, 99):5.1f} above200={(luma > 200).mean() * 100:5.2f}%')
	return canvas

def main():
	parser = argparse.ArgumentParser(description='Preview Weatherd clear-sky cloud rendering.')
	parser.add_argument('out', nargs='?', type=Path, default=Path('clear-sky-preview.png'))
	parser.add_argument('--cloud-size', type=float, default=1.0, help='Cloud body size scale (0.5–2.0).')
	parser.add_argument('--cloud-count', type=float, default=1.0, help='Rendered cloud coverage scale (0.5–2.0).')
	parser.add_argument('--phase', choices=('dawn', 'day', 'dusk', 'night'), default='day')
	parser.add_argument('--progress', type=float, default=0.5)
	parser.add_argument('--cover', type=float, help='Export a single aspect-correct portrait for this cover.')
	args = parser.parse_args()
	global MATERIAL_GRADE, SKY_TOP, SKY_BOTTOM, PHASE_GRAY, CIRRUS_TINT, CEILING_TINT
	MATERIAL_GRADE = cloud_grade(args.phase, args.progress)
	SKY_TOP, SKY_BOTTOM, PHASE_GRAY = phase_sky(args.phase, args.progress)
	CIRRUS_TINT = np.array({'day': (249, 251, 255), 'dawn': (246, 230, 234), 'dusk': (238, 216, 226), 'night': (116, 132, 164)}[args.phase], dtype=np.float32)
	CEILING_TINT = np.array({'day': (120, 128, 140), 'dawn': (96, 90, 104), 'dusk': (78, 74, 92), 'night': (30, 36, 50)}[args.phase], dtype=np.float32)
	if args.cover is not None:
		canvas = clear_sky(args.cover, cloud_size_scale=args.cloud_size, cloud_count_scale=args.cloud_count)
		image = Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8))
		image.save(args.out)
		print(f'{args.out}: {image.width}x{image.height}')
		return


	legacy = [
		clear_sky(cloudiness, cloud_size_scale=args.cloud_size, cloud_count_scale=args.cloud_count)
		for cloudiness in (0.05, 0.20, 0.45, 0.70, 0.85, 0.90)
	]
	# Layered studies preserve the separate low/mid/high paths, including high-only cirrus.
	layered = [
		clear_sky(
			0.8,
			cloud_size_scale=args.cloud_size,
			cloud_count_scale=args.cloud_count,
			cloud_layers=layers
		)
		for layers in ((0.7, 0.05, 0.05), (0.05, 0.7, 0.05), (0.05, 0.05, 0.8))
	]
	strip = np.concatenate(legacy + layered, axis=1)
	image = Image.fromarray(np.clip(strip, 0, 255).astype(np.uint8))
	image = image.resize((image.width // 4, image.height // 4), Image.Resampling.LANCZOS)
	image.save(args.out)
	print(f'{args.out}: {image.width}x{image.height}')


if __name__ == '__main__':
	main()
