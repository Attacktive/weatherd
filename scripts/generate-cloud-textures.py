# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0", "scipy==1.18.1"]
# ///
"""Generate procedural cloud layers with `uv run scripts/generate-cloud-textures.py`."""

from math import pi, sin
from pathlib import Path
from random import Random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy.ndimage import map_coordinates

# The generated fair-weather decks stay compact enough for several decoded variants to coexist without dominating bitmap heap.
CUMULUS_SIZE = (1620, 480)
CIRRUS_SIZE = (720, 192)

OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi'

# The two ends of the cumulus shading ramp: full sunlight on a crown, and skylight alone on a base in its own shadow.
SUNLIT = np.array([255, 255, 255], dtype=np.float32)
SHADOW = np.array([176, 195, 219], dtype=np.float32)

# How much of the sunlit value survives in a base the sun cannot reach at all, since the sky itself keeps lighting it.
AMBIENT = 0.34

# Roughly where the densest part of the noise field lands, measured from the generated textures rather than assumed.
# The coverage gain is normalized against it so every cut produces cloud of the same solidity.
FIELD_PEAK = 0.85

# The three coverage steps baked for the near deck, from a few isolated masses to a broken sheet.
# They share one seed on purpose: a lower cut takes more of the same field, so a mass grows rather than dissolving when the renderer cross-fades between two of them.
CUMULUS_SEED = 4021
CUMULUS_COVERAGE = (('cloud_cumulus_sparse', 0.56), ('cloud_cumulus_scattered', 0.44), ('cloud_cumulus_broken', 0.30))

# The far deck is one texture rather than three: it reads as distance, and distance is carried by haze and size, not by how much of it there is.
# Its own seed is what keeps it from ghosting the near deck, and its finer cells put more, smaller masses down by the horizon.
CUMULUS_FAR_SEED = 7703
CUMULUS_FAR_CELLS = (21, 7)

# High-cloud populations are separate additions rather than denser alpha on one sheet.
# The fixed seed keeps the generated fibrous streaks reproducible, while each population contributes eight new strands.
CIRRUS_SEED = 9117
CIRRUS_POPULATIONS = (
	('cloud_cirrus_sparse', 0, 8),
	('cloud_cirrus_scattered', 8, 16),
	('cloud_cirrus_broken', 16, 24),
)


def cirrus_streaks():
	rng = Random(CIRRUS_SEED)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
	streaks = []
	for _ in range(24):
		streaks.append((
			rng.uniform(0.12, 0.82) * CIRRUS_SIZE[1],
			rng.uniform(0, CIRRUS_SIZE[0]),
			rng.uniform(0.30, 0.62) * CIRRUS_SIZE[0],
			rng.uniform(-0.08, 0.08) * CIRRUS_SIZE[1],
			rng.uniform(0.014, 0.042) * CIRRUS_SIZE[1],
			rng.uniform(0.75, 1.45),
			rng.uniform(0, 2 * pi),
			rng.randint(34, 62),
			rng.randint(4, 7),
			rng.uniform(5, 12),
		))

	return streaks


def cirrus_filament_points(streak, filament_rng, filament, filament_count):
	y, x, length, slope, amplitude, frequency, phase, _, _, spread = streak
	start = filament_rng.uniform(0, 0.22)
	end = filament_rng.uniform(0.72, 1)
	offset = (filament - (filament_count - 1) / 2) * spread / max(filament_count - 1, 1) + filament_rng.uniform(-1.5, 1.5)
	phase_offset = filament_rng.uniform(-0.18, 0.18)
	points = []
	for sample in range(35):
		t = start + (end - start) * sample / 34
		points.append((
			x + length * t,
			y + slope * (t - 0.5) + amplitude * sin(2 * pi * frequency * t + phase + phase_offset) + offset * (0.45 + 0.8 * t),
		))

	return points


def draw_cirrus_filament(draw, width, streak, filament_rng, filament, filament_count):
	alpha = streak[7]
	points = cirrus_filament_points(streak, filament_rng, filament, filament_count)
	filament_alpha = max(14, min(72, round(alpha * filament_rng.uniform(0.55, 1))))
	filament_width = filament_rng.choice((1, 1, 1, 2))
	for shift in (-width, 0, width):
		draw.line(
			[(point_x + shift, point_y) for point_x, point_y in points],
			fill=filament_alpha,
			width=filament_width
		)


def cirrus_texture(start_index, end_index):
	width = CIRRUS_SIZE[0]
	mask = Image.new('L', CIRRUS_SIZE, 0)
	draw = ImageDraw.Draw(mask)
	streaks = cirrus_streaks()
	for index in range(start_index, end_index):
		streak = streaks[index]
		filament_count = streak[8]
		filament_rng = Random(CIRRUS_SEED * 100 + index)  # nosec B311 - deterministic artwork generation, not security-sensitive randomness.
		for filament in range(filament_count):
			draw_cirrus_filament(draw, width, streak, filament_rng, filament, filament_count)

	mask = mask.filter(ImageFilter.GaussianBlur(radius=1.15))
	image = Image.new('RGBA', CIRRUS_SIZE, (249, 251, 255, 0))
	image.putalpha(mask)

	return image


def smoothstep(low, high, values):
	amount = np.clip((values - low) / (high - low), 0, 1)
	return amount * amount * (3 - 2 * amount)


def noise(x, y, columns, rows, seed):
	"""Sample a smooth periodic field rather than stacking painted primitives."""
	grid = np.random.default_rng(seed).uniform(0, 1, (rows, columns)).astype(np.float32)
	coordinates = np.array([y * rows, x * columns], dtype=np.float32)
	return map_coordinates(grid, coordinates, order=3, mode='grid-wrap')


def fbm(x, y, columns, rows, seed, octaves=4, billow=False):
	"""Stack halving octaves of [noise]; `billow` folds each octave about its midpoint, which is what puts crenellation on a cumulus edge."""
	total = np.zeros_like(x)
	amplitude = 1.0
	norm = 0.0
	for octave in range(octaves):
		sample = noise(x, y, columns * 2 ** octave, rows * 2 ** octave, seed + octave)
		if billow:
			sample = 1 - np.abs(2 * sample - 1)

		total += amplitude * sample
		norm += amplitude
		amplitude *= 0.5

	return total / norm


def unit_grid(size):
	"""Texture coordinates normalized to the unit square, so every field below is written in size-independent terms."""
	width, height = size
	return np.meshgrid(
		np.arange(width, dtype=np.float32) / width,
		np.arange(height, dtype=np.float32) / height,
	)


def cumulus_thickness(seed, coverage_cut, cells=(12, 4)):
	"""How much cloud a sunbeam would have to cross at each texel: zero over clear sky, rising toward the middle of a mass."""
	x, y = unit_grid(CUMULUS_SIZE)

	# A gentle domain warp keeps the masses off the noise lattice, which is what stops them reading as a grid of blobs.
	wx = x + 0.035 * (noise(x, y, 6, 3, seed + 90) - 0.5)
	wy = y + 0.055 * (noise(x, y, 5, 4, seed + 91) - 0.5)

	# Square-ish cells: the texture is 3.375 times wider than tall, so the column count has to lead the row count by about the same factor.
	columns, rows = cells
	base = fbm(wx, wy, columns, rows, seed + 100)
	crenellation = fbm(wx, wy, round(columns * 2.25), rows * 2, seed + 200, billow=True)
	field = 0.72 * base + 0.28 * crenellation

	# A separate low-frequency mask opens real holes of clear sky, so the deck reads as separate clouds rather than one sheet with thin patches.
	gaps = smoothstep(0.30, 0.66, fbm(x, y, round(columns * 0.75), rows, seed + 300, octaves=3))
	field = field * (0.42 + 0.58 * gaps)

	# Feather both edges of the sheet down to exactly zero, so neither edge of a deck can cut a mass in half and leave a horizontal line across the sky.
	envelope = smoothstep(0.03, 0.30, y) * (1 - smoothstep(0.60, 0.97, y))

	# A higher cut leaves only the tips of the field standing, so the gain rises with it.
	# Without that, a sparse sky is not a few solid clouds but the same clouds gone translucent, which is the failure this whole texture set exists to avoid.
	gain = 5.0 / max(FIELD_PEAK - coverage_cut, 0.15)
	return np.maximum(field - coverage_cut, 0) * envelope * gain


def sun_occlusion(thickness, steps=9, rise=3.2, drift=1.1):
	"""March up-sun through the thickness field and accumulate what the light has to pass through to reach each texel."""
	occlusion = np.zeros_like(thickness)
	weight = 0.0
	for step in range(1, steps + 1):
		sample = np.roll(thickness, (int(round(step * rise)), int(round(step * drift))), axis=(0, 1))
		falloff = 1.0 / step
		occlusion += falloff * sample
		weight += falloff

	return occlusion / weight


def cumulus_texture(seed, coverage_cut, cells=(12, 4), density=1.35, shadow_strength=0.85):
	"""Build a fair-weather cumulus deck: opacity from Beer-Lambert absorption, shading from what the field itself casts."""
	thickness = cumulus_thickness(seed, coverage_cut, cells)

	# Beer-Lambert rather than a scaled density: a core saturates to fully opaque on its own, and an edge feathers out without needing a painted falloff.
	alpha = 1 - np.exp(-density * thickness)

	# Skylight fills the shaded side, so the darkest a base gets is a floor rather than black.
	light = AMBIENT + (1 - AMBIENT) * np.exp(-shadow_strength * sun_occlusion(thickness))

	# A thin wisp is lit through as well as from above, which is what keeps torn edges bright instead of gray.
	light = np.clip(light + 0.30 * np.exp(-2.4 * thickness), 0, 1)

	rgb = SHADOW[None, None, :] + (SUNLIT - SHADOW)[None, None, :] * light[:, :, None]
	width, height = CUMULUS_SIZE
	pixels = np.empty((height, width, 4), dtype=np.uint8)
	pixels[:, :, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
	pixels[:, :, 3] = np.clip(alpha * 255, 0, 255).astype(np.uint8)
	return Image.fromarray(pixels, 'RGBA')


def report(path, image):
	alpha = np.asarray(image.convert('RGBA'), dtype=np.float32)[:, :, 3] / 255.0
	print(f'{path.relative_to(OUTPUT.parents[4])}: {image.width}x{image.height}, {path.stat().st_size} bytes', end='')
	print(f', opaque={100 * (alpha > 0.9).mean():5.2f}% covered={100 * (alpha > 0.5).mean():5.2f}%')


def main():
	OUTPUT.mkdir(parents=True, exist_ok=True)
	for name, coverage_cut in CUMULUS_COVERAGE:
		imae = cumulus_texture(CUMULUS_SEED, coverage_cut)
		path = OUTPUT / f'{name}.png'
		image.save(path, optimize=True)
		report(path, image)

	image = cumulus_texture(CUMULUS_FAR_SEED 0.56, CUMULUS_FAR_CELLS)
	path = OUTPUT / 'cloud_cumulus_far.png'
	image.save(path, optimize=True)
	report(path, image)

	for name, start_index, end_index in CIRRUS_POPULATIONS:
		image = cirrus_texture(start_index, end_index)
		path = OUTPUT / f'{name}.webp'
		image.save(path, 'WEBP', lossless=True, quality=100, method=6)
		report(path, image)


if __name__ == '__main__':
	main()
